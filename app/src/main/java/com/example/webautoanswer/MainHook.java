package com.example.webautoanswer;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * LSPosed / Xposed 入口类。
 *
 * 作用：在目标 App 的 WebView 页面加载完成时，把 assets/inject.js 注入进去。
 * 模块自己不做业务逻辑，所有答题逻辑都在 inject.js 里，改脚本不需要重新打包模块。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[WebAutoAnswer]";

    /** 模块自身包名，用于通过 Context 兜底读取 assets。 */
    private static final String MODULE_PKG = "com.example.webautoanswer";

    /**
     * 目标 App 包名。
     * 用 MT 管理器打开云端学习 APK 看 AndroidManifest.xml 的 package 属性，
     * 必须与这里完全一致。常见值 "com.tbc.android.defaults"，务必自行确认。
     */
    private static final String TARGET_PKG = "com.tbc.android.defaults";

    /** 脚本只读一次，进程内缓存。 */
    private static volatile String sJsCode = null;

    /** 已经 Hook 过的 WebViewClient 类，避免重复挂载。 */
    private static final Set<Class<?>> sHookedClientClasses =
            Collections.synchronizedSet(new HashSet<Class<?>>());

    /** TBS 内核 Hook 状态，避免反复 findClass 刷日志。 */
    private static volatile boolean sTbsHooked = false;
    private static int sTbsAttempts = 0;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log(TAG + " 命中目标: " + lpparam.packageName);

        forceJavaScriptEnabled();
        hookWebViewClientSetter();
        hookBaseWebViewClient();
        tryHookTbs(lpparam.classLoader);
    }

    /* ------------------------------------------------------------------ */
    /* 1) 强制开启 WebView JavaScript                                     */
    /* ------------------------------------------------------------------ */

    private void forceJavaScriptEnabled() {
        try {
            XposedHelpers.findAndHookMethod(
                    WebSettings.class, "setJavaScriptEnabled", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.args[0] = true;
                        }
                    });
            XposedBridge.log(TAG + " Hook WebSettings#setJavaScriptEnabled 成功");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Hook setJavaScriptEnabled 失败: " + t);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 2) 系统 WebView：拦截 setWebViewClient，动态 Hook 真实客户端类      */
    /* ------------------------------------------------------------------ */

    private void hookWebViewClientSetter() {
        try {
            XposedHelpers.findAndHookMethod(
                    WebView.class, "setWebViewClient", WebViewClient.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object client = (param.args == null || param.args.length == 0)
                                    ? null : param.args[0];
                            if (client != null) {
                                hookClientHierarchy(client.getClass());
                            }
                        }
                    });
            XposedBridge.log(TAG + " Hook WebView#setWebViewClient 成功");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Hook setWebViewClient 失败: " + t);
        }
    }

    /** 兜底：App 直接用系统 WebViewClient（或根本不 set 客户端）时也能注入。 */
    private void hookBaseWebViewClient() {
        hookDeclaredOnPageFinished(WebViewClient.class);
    }

    /**
     * 沿继承链逐层 Hook onPageFinished。
     * 直接 Hook 基类无效，因为 App 的 WebViewClient 子类自己重写了 onPageFinished，
     * 走的是子类方法，基类实现根本不会被调用。
     */
    private void hookClientHierarchy(Class<?> clientClass) {
        Class<?> c = clientClass;
        while (c != null && c != Object.class) {
            hookDeclaredOnPageFinished(c);
            c = c.getSuperclass();
        }
    }

    private void hookDeclaredOnPageFinished(Class<?> clazz) {
        if (clazz == null || !sHookedClientClasses.add(clazz)) {
            return;
        }
        boolean hookedAny = false;
        Method[] methods;
        try {
            methods = clazz.getDeclaredMethods();
        } catch (Throwable t) {
            return;
        }
        for (Method m : methods) {
            if (!"onPageFinished".equals(m.getName())) {
                continue;
            }
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != 2 || pts[1] != String.class) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length < 2) {
                            return;
                        }
                        inject(param.args[0], String.valueOf(param.args[1]));
                    }
                });
                hookedAny = true;
            } catch (Throwable t) {
                XposedBridge.log(TAG + " Hook " + clazz.getName() + "#onPageFinished 失败: " + t);
            }
        }
        if (hookedAny) {
            XposedBridge.log(TAG + " Hook 客户端成功: " + clazz.getName());
        }
    }

    /* ------------------------------------------------------------------ */
    /* 3) 腾讯 X5 / TBS 内核                                              */
    /* ------------------------------------------------------------------ */

    private void tryHookTbs(ClassLoader cl) {
        if (sTbsHooked || cl == null) {
            return;
        }
        if (sTbsAttempts >= 3) {
            return;
        }
        sTbsAttempts++;

        Class<?> tbsWebView;
        Class<?> tbsClient;
        try {
            tbsWebView = XposedHelpers.findClass("com.tencent.smtt.sdk.WebView", cl);
            tbsClient = XposedHelpers.findClass("com.tencent.smtt.sdk.WebViewClient", cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 未发现 TBS 内核（若目标未用 X5 则属正常）: " + t);
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(
                    tbsWebView, "setWebViewClient", tbsClient,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object client = (param.args == null || param.args.length == 0)
                                    ? null : param.args[0];
                            if (client != null) {
                                hookClientHierarchy(client.getClass());
                            }
                        }
                    });
            sTbsHooked = true;
            XposedBridge.log(TAG + " Hook TBS WebView#setWebViewClient 成功");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Hook TBS setWebViewClient 失败: " + t);
        }

        // 兜底：TBS 基类
        hookDeclaredOnPageFinished(tbsClient);
    }

    /* ------------------------------------------------------------------ */
    /* 4) 注入                                                            */
    /* ------------------------------------------------------------------ */

    private static void inject(final Object webView, final String url) {
        if (webView == null) {
            return;
        }
        final String js = getJs();
        if (js == null || js.length() == 0) {
            return;
        }

        Runnable task = new Runnable() {
            @Override
            public void run() {
                if (webView instanceof WebView) {
                    try {
                        ((WebView) webView).evaluateJavascript(js, null);
                        XposedBridge.log(TAG + " 系统 WebView 注入完成: " + url);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " 系统 WebView 注入失败: " + t);
                    }
                    return;
                }
                // TBS 等第三方内核
                try {
                    XposedHelpers.callMethod(webView, "evaluateJavascript", js, null);
                    XposedBridge.log(TAG + " TBS WebView 注入完成: " + url);
                } catch (Throwable t) {
                    try {
                        XposedHelpers.callMethod(webView, "loadUrl", "javascript:" + js);
                        XposedBridge.log(TAG + " TBS WebView loadUrl 注入完成: " + url);
                    } catch (Throwable t2) {
                        XposedBridge.log(TAG + " TBS WebView 注入失败: " + t2);
                    }
                }
            }
        };

        if (webView instanceof View) {
            boolean posted = ((View) webView).post(task);
            if (!posted) {
                task.run();
            }
        } else {
            task.run();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 5) 读取 assets/inject.js                                           */
    /* ------------------------------------------------------------------ */

    private static String getJs() {
        if (sJsCode != null) {
            return sJsCode;
        }
        synchronized (MainHook.class) {
            if (sJsCode != null) {
                return sJsCode;
            }
            String js = null;
            try {
                js = readFromModuleApk();
            } catch (Throwable t) {
                XposedBridge.log(TAG + " 从模块 APK 读取 inject.js 失败: " + t);
            }
            if (js == null || js.length() == 0) {
                try {
                    js = readFromModuleAssets();
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " 通过模块 Context 读取 inject.js 失败: " + t);
                }
            }
            if (js == null || js.length() == 0) {
                XposedBridge.log(TAG + " inject.js 未找到或为空，跳过注入");
                return null;
            }
            sJsCode = js;
            XposedBridge.log(TAG + " inject.js 加载完成，长度=" + js.length());
            return sJsCode;
        }
    }

    /** 方式一：模块 APK 就是脚本来源（LSPosed 下模块类从 APK 加载）。 */
    private static String readFromModuleApk() throws Exception {
        String raw = MainHook.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        if (raw == null || raw.length() == 0) {
            return null;
        }
        String apkPath = URLDecoder.decode(raw, "UTF-8");
        XposedBridge.log(TAG + " 模块 APK 路径: " + apkPath);

        ZipFile zip = null;
        InputStream is = null;
        try {
            zip = new ZipFile(apkPath);
            ZipEntry entry = zip.getEntry("assets/inject.js");
            if (entry == null) {
                XposedBridge.log(TAG + " APK 内没有 assets/inject.js");
                return null;
            }
            is = zip.getInputStream(entry);
            return readAll(is);
        } finally {
            if (is != null) {
                try { is.close(); } catch (Throwable ignored) { }
            }
            if (zip != null) {
                try { zip.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /** 方式二：从模块自己的 Context 读 assets（APK 路径拿不到时兜底）。 */
    private static String readFromModuleAssets() throws Exception {
        Object app = AndroidAppHelper.currentApplication();
        if (!(app instanceof Context)) {
            return null;
        }
        Context moduleCtx = ((Context) app)
                .createPackageContext(MODULE_PKG, Context.CONTEXT_IGNORE_SECURITY);
        if (moduleCtx == null) {
            return null;
        }
        InputStream is = moduleCtx.getAssets().open("inject.js");
        try {
            return readAll(is);
        } finally {
            try { is.close(); } catch (Throwable ignored) { }
        }
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        byte[] data = bos.toByteArray();
        return new String(data, "UTF-8");
    }
}
