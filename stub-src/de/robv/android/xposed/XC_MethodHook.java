package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Compile-only stub. Real implementation is provided by the Xposed framework at runtime.
 * Signatures verified against LSPosed framework.dex on-device.
 */
public class XC_MethodHook {
    public XC_MethodHook() { }

    public XC_MethodHook(int priority) { }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable { }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable { }

    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;

        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; }
        public Object getResultOrThrowable() throws Throwable { return result; }
        public Throwable getThrowable() { return null; }
        public boolean hasThrowable() { return false; }
        public void setThrowable(Throwable throwable) { }
    }

    public static class Unhook {
        public void unhook() { }
    }
}
