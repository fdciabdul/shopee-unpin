# ShopeeUnpin

LSPosed module: kills SSL pinning in the Shopee app
(`com.shopee.id`, `com.shopee.lite.id`) so HTTPS traffic shows up in plaintext
in HTTPToolkit / Reqable / mitmproxy.

## Bypass layers

| # | Target | Effect |
|---|--------|--------|
| 1 | `TrustManagerImpl.checkTrusted{,Recursive}` (Conscrypt) | Disables chain validation, including custom TrustManagers that delegate to Conscrypt |
| 2 | `OkHostnameVerifier.verify` (OkHttp) | Hostname check always true |
| 3 | `CertificatePinner.check` (OkHttp v3 + v2) | SPKI pinning off |
| 4 | `SSLContext.init` | Replaces app TrustManagers with a naive one |
| 5 | `WebViewClient.onReceivedSslError` | Calls `handler.proceed()` |

Every hook is best-effort: a class/method missing on a given ROM or app
version is logged and skipped, never fatal.

## Install

1. Install the APK, enable the module in LSPosed, tick the Shopee scope.
2. Reboot / soft-reboot.
3. Check the LSPosed log: you should see `ShopeeUnpin: hooking ...`.
4. Capture: traffic should be plaintext.

Note: LSPosed injection into Shopee Lite is flaky on some devices
(the daemon silently skips it). If the module doesn't attach on Lite, try
`com.shopee.id` or re-apply the exact same scope.

## Build

```sh
./gradlew assembleDebug   # APK at app/build/outputs/apk/debug/
```

No local Android SDK? Push to a branch — GitHub Actions builds it
automatically and attaches the APK to Artifacts. Tags like `v*` attach the
APK to the GitHub Release.

## Verify the hooks are live (optional, rooted + Frida)

Attach to the Shopee process; `Module.enumerateExports("libssl.so")` should
resolve, and fresh TLS handshakes should MITM cleanly. Otherwise just check
that login/API traffic shows up plaintext in HTTPToolkit.
