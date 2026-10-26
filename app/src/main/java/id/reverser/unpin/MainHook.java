package id.reverser.unpin;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;

import android.net.http.SslError;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.lang.reflect.Member;
 import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * SSL unpin for Shopee (com.shopee.id, com.shopee.lite.id).
 *
 * Layers (every hook is best-effort: missing class/method on a given
 * ROM or app version is logged and skipped, never fatal):
 *
 * 1. Conscrypt TrustManagerImpl.checkTrusted{,Recursive} -> trust all.
 *    Kills default chain validation AND any custom TrustManager that
 *    delegates to Conscrypt (the Java check runs inside the native
 *    verify callback that these methods back).
 * 2. OkHttp OkHostnameVerifier.verify -> true (hostname check off).
 * 3. OkHttp CertificatePinner.check (v3 + v2) -> no-op (SPKI pin off).
 * 4. SSLContext.init -> swap app TrustManagers for a naive one.
 *    Catches fully-custom managers that never touch Conscrypt.
 * 5. WebViewClient.onReceivedSslError -> handler.proceed().
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "ShopeeUnpin";
    private static final Set<String> TARGETS = new HashSet<>(Arrays.asList(
            "com.shopee.id",
            "com.shopee.lite.id"
    ));

    private static void log(String s) {
        XposedBridge.log(TAG + ": " + s);
    }

    /** Trusts everything. Used as a replacement in SSLContext.init. */
    private static final X509TrustManager NAIVE_TRUST_MANAGER = new X509TrustManager() {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGETS.contains(lpparam.packageName)) {
            return;
        }
        log("hooking " + lpparam.packageName + " (process " + lpparam.processName + ")");
        hookConscrypt(lpparam);
        hookOkHostnameVerifier(lpparam);
        hookCertificatePinner(lpparam);
        hookSslContextInit(lpparam);
        hookWebViewClient(lpparam);
    }

    // ---- 1. Conscrypt -------------------------------------------------------
    private void hookConscrypt(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> tm = findClass("com.android.org.conscrypt.TrustManagerImpl", lpparam.classLoader);
            XC_MethodHook trustAll = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Method m = (Method) param.method;
                    if (List.class.isAssignableFrom(m.getReturnType())) {
                        param.setResult(new ArrayList<>());
                    } else {
                        // void methods: skipping the call is the bypass.
                        param.setResult(null);
                    }
                }
            };
            int n = 0;
            for (Method m : tm.getDeclaredMethods()) {
                String name = m.getName();
                if (!Modifier.isStatic(m.getModifiers())
                        && (name.equals("checkTrustedRecursive") || name.equals("checkTrusted"))) {
                    XposedBridge.hookMethod(m, trustAll);
                    n++;
                }
            }
            log("Conscrypt TrustManagerImpl hooked (" + n + " methods)");
        } catch (Throwable t) {
            log("Conscrypt skip: " + t);
        }
    }

    // ---- 2. OkHttp hostname verifier ----------------------------------------
    private void hookOkHostnameVerifier(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final Class<?> verifier =
                    findClass("okhttp3.internal.tls.OkHostnameVerifier", lpparam.classLoader);
            XC_MethodHook alwaysTrue = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(true);
                }
            };
            int n = 0;
            for (Method m : verifier.getDeclaredMethods()) {
                if (m.getName().equals("verify") && m.getReturnType() == boolean.class) {
                    XposedBridge.hookMethod(m, alwaysTrue);
                    n++;
                }
            }
            log("OkHostnameVerifier hooked (" + n + " overloads)");
        } catch (Throwable t) {
            log("OkHostnameVerifier skip: " + t);
        }
    }

    // ---- 3. OkHttp CertificatePinner ----------------------------------------
    private void hookCertificatePinner(XC_LoadPackage.LoadPackageParam lpparam) {
        String[] classes = {
                "okhttp3.CertificatePinner",
                "com.squareup.okhttp.CertificatePinner",
        };
        for (String cn : classes) {
            try {
                final Class<?> pinner = findClass(cn, lpparam.classLoader);
                XC_MethodHook noop = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(null); // both check() overloads return void
                    }
                };
                int n = 0;
                for (Method m : pinner.getDeclaredMethods()) {
                    if (m.getName().equals("check") && m.getReturnType() == void.class) {
                        XposedBridge.hookMethod(m, noop);
                        n++;
                    }
                }
                if (n > 0) log("CertificatePinner hooked: " + cn + " (" + n + ")");
            } catch (Throwable t) {
                log("CertificatePinner skip " + cn + ": " + t);
            }
        }
    }

    // ---- 4. SSLContext.init: swap custom TrustManagers -----------------------
    private void hookSslContextInit(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            findAndHookMethod(SSLContext.class, "init",
                    KeyManager[].class, TrustManager[].class, SecureRandom.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            TrustManager[] orig = (TrustManager[]) param.args[1];
                            if (orig != null && orig.length > 0) {
                                param.args[1] = new TrustManager[] { NAIVE_TRUST_MANAGER };
                                log("SSLContext.init TrustManagers replaced");
                            }
                        }
                    });
            log("SSLContext.init hooked");
        } catch (Throwable t) {
            log("SSLContext.init skip: " + t);
        }
    }

    // ---- 5. WebView -----------------------------------------------------------
    private void hookWebViewClient(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            findAndHookMethod(WebViewClient.class, "onReceivedSslError",
                    WebView.class, SslErrorHandler.class, SslError.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                ((SslErrorHandler) param.args[1]).proceed();
                            } catch (Throwable ignored) { }
                            param.setResult(null);
                        }
                    });
            log("WebViewClient.onReceivedSslError hooked");
        } catch (Throwable t) {
            log("WebViewClient skip: " + t);
        }
    }
}
