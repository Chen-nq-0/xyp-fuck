package io.dsh.campusrun;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseBooleanArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class AdBlockModule implements IXposedHookLoadPackage {
    static final String TAG = "CampusRunMod";
    static final String PKG = "com.zjwh.android_wh_physicalfitness";
    static final String VERSION = "v17";

    static final String[] AD_DOMAINS = {
        "gdt.qq.com", "sdk.e.qq.com", "e.qq.com", "pgdt.gtimg.cn", "mi.gdt.qq.com",
        "v2mi.gdt.qq.com", "v3mi.gdt.qq.com", "gdtimg.com", "ugdtimg.com", "adsmind.gdtimg.com",
        "pangolin-sdk-toutiao.com", "api-access.pangolin-sdk-toutiao.com", "pglstatp-toutiao.com",
        "mssdk.volces.com", "lf-cdn-tos.bytescm.com", "gromore.pangolin-sdk-toutiao.com",
        "open.e.kuaishou.com", "open.kwaizt.com", "kwaizt.com", "kskwai.com", "gskwai.com",
        "kuaishouzt.com", "kwai.com", "kwai.net", "kwaicdn.com", "yximgs.com", "kspkg.com",
        "g-api.kwai.com", "g-album.kwai.com", "kwimgs.com", "adkwai.com", "gifshow.com",
        "kuaishou.com", "kuaishou.cn",
        "mobads.baidu.com", "cpro.baidustatic.com", "pos.baidu.com",
        "adgain.cn", "xadsmartget.adgain.cn", "yfanads.com", "yfanads.cn", "tracker.yfanads.com",
        "tianmu.mobi", "sdk.tianmu.mobi", "x.jd.com", "ad.jd.com", "union.jd.com", "im-x.jd.com",
        "dsp-x.jd.com", "xlog.jd.com", "janapi.jd.com", "cps.jd.com", "click.jd.com",
        "sdkconfig.ad.xiaomi.com", "ads.heytapmobi.com", "ads-adnet-drcn.cloud.honor.com",
        "ads.huawei.com", "csjplatform.com", "pangle.cn", "tanx.com", "alimama.com",
        "umeng.com", "umengcloud.com", "zztfly.com", "idazhe.net", "sf3-ttcdn-tos.pstatp.com",
        "p2lmad.kwai.com", "pstatp.com"
    };

    static final String[] AD_VIEW_NAMES = {
        "ad_container", "ad_content", "ad_image_blur", "ad_desc_small", "ad_close_area",
        "ad_log", "tev_countdown", "media_view_frame", "animation_view_big", "animation_view_big_area",
        "splash_ad", "feed_ad", "interstitial_ad", "native_ad", "mimo_ad", "market_ad",
        "ad_frame", "ad_layout", "ad_spot", "native_ad_container"
    };

    static final String[] AD_CLASS_PREFIXES = {
        "com.miui.zeus", "com.miui.mimo", "com.xiaomi.ad", "com.miui.systemAdSolution",
        "com.kwad", "com.qq.e", "com.bytedance", "com.byazt", "com.byaztp",
        "com.baidu.mobads", "com.tianmu", "com.yfanads", "com.adgain", "com.jd.ad"
    };

    static final SparseBooleanArray adIds = new SparseBooleanArray(24);
    static volatile boolean idsReady = false;
    static volatile Handler mainHandler = null;
    static volatile boolean splashHooked = false;
    static final AtomicInteger hideLogs = new AtomicInteger();
    static final AtomicInteger blockLogs = new AtomicInteger();
    static final Set<String> logged = new HashSet<>();

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if (!PKG.equals(lpparam.packageName)) return;
        try { mainHandler = new Handler(Looper.getMainLooper()); } catch (Throwable ignored) {}
        try { AdGate.install(); } catch (Throwable t) { log("gate fail " + t); }
        try { AdGate.run(lpparam.classLoader); } catch (Throwable ignored) {}
        installActivityHook();
        log("MODULE " + VERSION + " ready (" + lpparam.processName + ")");
    }

    // ---------- DNS：用无分配的 regionMatches 做后缀匹配 ----------
    static void installDnsBlocker() {
        try {
            XC_MethodHook cb = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object a0 = param.args[0];
                    if (!(a0 instanceof String)) return;
                    String host = (String) a0;
                    if (host.length() < 4 || !isAdHost(host)) return;
                    InetAddress lo = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
                    if (param.method.getName().equals("getAllByName")) param.setResult(new InetAddress[]{lo});
                    else param.setResult(lo);
                }
            };
            XposedHelpers.findAndHookMethod(InetAddress.class, "getAllByName", String.class, cb);
            XposedHelpers.findAndHookMethod(InetAddress.class, "getByName", String.class, cb);
        } catch (Throwable t) { log("dns hook failed " + t); }
    }

    static boolean isAdHost(String h) {
        int hl = h.length();
        for (String d : AD_DOMAINS) {
            int dl = d.length();
            if (hl == dl) {
                if (h.regionMatches(true, 0, d, 0, dl)) return true;
            } else if (hl > dl && h.charAt(hl - dl - 1) == '.' && h.regionMatches(true, hl - dl, d, 0, dl)) {
                return true;
            }
        }
        return false;
    }

    // ---------- 用 Instrumentation.callActivityOnCreate 替代全局 loadClass hook ----------
    static void installActivityHook() {
        try {
            XposedHelpers.findAndHookMethod(Instrumentation.class, "callActivityOnCreate", Activity.class, Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity a = (Activity) param.args[0];
                        if (a == null) return;
                        Class<?> c = a.getClass();
                        if (c.getName().endsWith("HomeActivity")) {
                            final Activity fa = a;
                            try {
                                mainHandler.postDelayed(new Runnable() { public void run() { AdGate.run(fa.getClassLoader()); } }, 2000);
                            } catch (Throwable ignored) {}
                        }
                        if (!c.getName().endsWith("mvi.splash.SplashActivity")) return;
                        if (splashHooked) return;
                        splashHooked = true;
                        hookSplashAd(c);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) { log("activity hook failed " + t); }
    }


    static void loadNative(Activity a, int tag) {
        try {
            android.content.pm.ApplicationInfo ai = a.getPackageManager().getApplicationInfo("io.dsh.campusrun", 0);
            java.io.File src = new java.io.File(ai.nativeLibraryDir, "libshdump.so");
            java.io.File dst = new java.io.File(a.getFilesDir(), "libshdump" + tag + ".so");
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out2 = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out2.write(buf, 0, n);
            in.close(); out2.close();
            dst.setReadable(true, false); dst.setExecutable(true, false);
            log("native copy " + dst.getAbsolutePath() + " len=" + dst.length());
            try {
                java.lang.reflect.Method m = Runtime.class.getDeclaredMethod("load0", Class.class, String.class);
                m.setAccessible(true);
                try { m.invoke(null, a.getClass(), dst.getAbsolutePath()); }
                catch (Throwable t1) { m.invoke(Runtime.getRuntime(), a.getClass(), dst.getAbsolutePath()); }
                log("load0 ok");
            } catch (Throwable t) { log("nativeLoad fail " + t); }
        } catch (Throwable t) { log("native load fail " + t); }
    }

    static void installClassLoaderFallback() {
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object r = param.getResult();
                    if (!(r instanceof Class)) return;
                    Class<?> c = (Class<?>) r;
                    if (!c.getName().endsWith("mvi.splash.SplashActivity")) return;
                    if (splashHooked) return;
                    splashHooked = true;
                    hookSplashAd(c);
                }
            });
        } catch (Throwable ignored) {}
    }
    static void hookSplashAd(Class<?> c) {
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isAbstract(m.getModifiers())) continue;
                if (!m.getName().startsWith("loadSplashAd")) continue;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            blockLog("splash-ad");
                            Class<?> rt = ((Method) param.method).getReturnType();
                            if (rt == void.class || !rt.isPrimitive()) param.setResult(null);
                            else if (rt == boolean.class) param.setResult(false);
                            else param.setResult(0);
                            scheduleFastJump(param.thisObject, param.method.getDeclaringClass());
                        }
                    });
                } catch (Throwable ignored) {}
            }
            log("splash hook ok");
        } catch (Throwable t) { log("splash hook failed " + t); }
    }

    static void scheduleFastJump(final Object activity, final Class<?> cls) {
        Handler h = mainHandler;
        if (h == null || activity == null) return;
        h.postDelayed(new Runnable() {
            public void run() {
                try {
                    for (Method m : cls.getDeclaredMethods()) {
                        if (!m.getName().equals("startJump")) continue;
                        m.setAccessible(true);
                        Class<?>[] pt = m.getParameterTypes();
                        Object[] args = new Object[pt.length];
                        for (int i = 0; i < pt.length; i++) args[i] = defVal(pt[i]);
                        m.invoke(activity, args);
                        break;
                    }
                } catch (Throwable ignored) {}
            }
        }, 0);
    }

    // ---------- View 层：SparseBooleanArray 避免装箱 ----------
    static void installViewHooks() {
        try {
            XposedBridge.hookAllMethods(View.class, "setVisibility", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!idsReady) return;
                    Object self = param.thisObject;
                    if (!(self instanceof View)) return;
                    int id = ((View) self).getId();
                    if (id <= 0 || !adIds.get(id)) return;
                    Object a0 = param.args[0];
                    if (a0 instanceof Integer && ((Integer) a0) != View.GONE) {
                        param.args[0] = View.GONE;
                        if (hideLogs.incrementAndGet() <= 10) log("hide view id=" + id);
                    }
                }
            });
        } catch (Throwable t) { log("visibility hook failed " + t); }

        XC_MethodHook addHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object a0 = param.args[0];
                if (!(a0 instanceof View)) return;
                String cn = a0.getClass().getName();
                if (isAdViewClass(cn)) param.setResult(null);
            }
        };
        try { XposedHelpers.findAndHookMethod(ViewGroup.class, "addView", View.class, addHook); } catch (Throwable ignored) {}
        try { XposedHelpers.findAndHookMethod(ViewGroup.class, "addView", View.class, int.class, addHook); } catch (Throwable ignored) {}

        try {
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity a = (Activity) param.thisObject;
                        if (!idsReady) computeIds(a);
                        Handler h = mainHandler;
                        if (h == null) return;
                        h.postDelayed(new Runnable() { public void run() { hideAdViews(a); } }, 300);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) { log("resume hook failed " + t); }
    }

    static boolean isAdViewClass(String cn) {
        if (cn == null || !cn.startsWith("com.")) return false;
        for (String p : AD_CLASS_PREFIXES) if (cn.startsWith(p)) return true;
        return false;
    }

    static void computeIds(Activity a) {
        try {
            Resources r = a.getResources();
            String pkg = a.getPackageName();
            for (String n : AD_VIEW_NAMES) {
                int id = r.getIdentifier(n, "id", pkg);
                if (id != 0) adIds.put(id, true);
            }
            idsReady = true;
            log("ad ids=" + adIds.size());
        } catch (Throwable ignored) {}
    }

    static void hideAdViews(Activity a) {
        int n = adIds.size();
        for (int i = 0; i < n; i++) {
            int id = adIds.keyAt(i);
            try {
                View v = a.findViewById(id);
                if (v != null && v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
            } catch (Throwable ignored) {}
        }
    }

    // ---------- 广告弹窗（小米 Mimo 插屏等） ----------
    static void installDialogHook() {
        try {
            XposedBridge.hookAllMethods(Dialog.class, "show", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final Dialog d = (Dialog) param.thisObject;
                    Handler h = mainHandler;
                    if (h == null || d == null) return;
                    h.postDelayed(new Runnable() {
                        public void run() {
                            try {
                                if (isAdDialog(d)) { blockLog("dialog"); d.dismiss(); }
                            } catch (Throwable ignored) {}
                        }
                    }, 300);
                }
            });
        } catch (Throwable t) { log("dialog hook failed " + t); }
    }

    static boolean isAdDialog(Dialog d) {
        try {
            android.view.Window w = d.getWindow();
            if (w == null) return false;
            View root = w.getDecorView();
            return root != null && scan(root, 0);
        } catch (Throwable t) { return false; }
    }

    static boolean scan(View v, int depth) {
        if (v == null || depth > 20) return false;
        try {
            if (isAdViewClass(v.getClass().getName())) return true;
            if (v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null && cs.length() > 3) {
                    String t = cs.toString();
                    if (t.contains("北京小米移动软件") || t.contains("小米应用商店")) return true;
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                int n = g.getChildCount();
                for (int i = 0; i < n; i++) if (scan(g.getChildAt(i), depth + 1)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    static Object defVal(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        if (t == char.class) return (char) 0;
        return null;
    }

    static void blockLog(String what) {
        if (blockLogs.incrementAndGet() <= 20) log("block " + what);
    }

    static void log(String s) {
        try { XposedBridge.log(TAG + ": " + s); } catch (Throwable ignored) {}
    }
}
