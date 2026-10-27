package io.dsh.campusrun;

import android.app.Application;
import android.content.Context;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** v16：广告中台总开关 + 阻止广告 SDK 初始化。 */
public class AdGate {
    static final String TAG = "CampusRunMod";
    static final String P = "com.zjwh.android_wh_physicalfitness.advertise.";
    static final String CTRL = P + "AdvertiseControl";
    static final String MGR = P + "AdManager";
    static final String[] PROVIDERS = {
        P + "adgain.AdGainAdProvider",
        P + "guandian.GuandianAdProvider",
        P + "tianmu.TianMuAdProvider",
        P + "xyz.XyzAdProvider",
        P + "lingye.LingYeAdProvider"
    };
    static final AtomicBoolean ctrlDone = new AtomicBoolean();
    static final AtomicBoolean mgrDone = new AtomicBoolean();
    static final AtomicInteger provDone = new AtomicInteger();

    static void log(String s) { try { XposedBridge.log(TAG + "[gate]: " + s); } catch (Throwable t) {} }

    public static void install() {
        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", Application.class.getClassLoader(), "attachBaseContext", Context.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        Object self = p.thisObject;
                        if (self instanceof Application) runSync(((Application) self).getClassLoader());
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) { log("attach hook fail " + t); }
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try { runSync(((Application) p.thisObject).getClassLoader()); } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) { log("app onCreate hook fail " + t); }
        log("installed");
    }

    static final String[] PROVIDER_BLOCK = {
        "com.byazt.", "com.byted.", "com.bytedance.", "com.kwad.",
        "com.huawei.hms.support.api.push", "com.huawei.openalliance.ad",
        "com.acse.ottn", "cn.fly.", "com.igexin.", "com.tencent.bugly"
    };

    static boolean blockProvider(String name) {
        if (name == null) return false;
        for (String p : PROVIDER_BLOCK) if (name.startsWith(p)) return true;
        return false;
    }

    static void hookProviders() {
        ClassLoader cl = AdGate.class.getClassLoader();
        XposedHelpers.findAndHookMethod("android.app.ActivityThread", cl, "installContentProviders",
            Context.class, java.util.List.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        Object o = p.args[1];
                        if (!(o instanceof java.util.List)) return;
                        java.util.List list = (java.util.List) o;
                        java.util.Iterator it = list.iterator();
                        while (it.hasNext()) {
                            Object pi = it.next();
                            String n = String.valueOf(pi);
                            if (blockProvider(n)) { it.remove(); log("provider skipped " + n.substring(0, Math.min(60, n.length()))); }
                        }
                    } catch (Throwable ignored) {}
                }
            });
    }

    public static void run(final ClassLoader cl) {
        new Thread(new Runnable() {
            public void run() { runSync(cl); }
        }, "dsh-gate").start();
    }

    static void runSync(ClassLoader cl) {
        if (cl == null) return;
        tryLoad(cl, CTRL);
        tryLoad(cl, MGR);
        for (String n : PROVIDERS) tryLoad(cl, n);
    }

    static void tryLoad(ClassLoader cl, String n) {
        try { hookFor(Class.forName(n, false, cl)); } catch (Throwable ignored) {}
    }

    static void hookFor(Class<?> c) {
        String n = c.getName();
        if (CTRL.equals(n)) hookCtrl(c);
        else if (MGR.equals(n)) hookMgr(c);
        else if (n.startsWith(P)) hookProvider(c);
    }

    static void hookCtrl(Class<?> c) {
        if (!ctrlDone.compareAndSet(false, true)) return;
        int a = 0, b = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().startsWith("canLoad") && m.getReturnType() == boolean.class) {
                if (hookFalse(m)) a++;
            } else if (m.getName().equals("requestAdConfig")) {
                if (hookVoid(m)) b++;
            }
        }
        log("AdvertiseControl canLoad=" + a + " requestAdConfig=" + b);
    }

    static void hookMgr(Class<?> c) {
        if (!mgrDone.compareAndSet(false, true)) return;
        int a = 0, b = 0, d = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (Modifier.isAbstract(m.getModifiers())) continue;
            String name = m.getName();
            if ((name.equals("checkCanLoad") || name.equals("isSlotAllowed")) && m.getReturnType() == boolean.class) {
                if (hookFalse(m)) a++;
            } else if (name.equals("init") || name.equals("requestAdConfig")) {
                if (hookVoid(m)) b++;
            } else if (name.equals("loadSplash") || name.equals("loadInteraction") || name.equals("loadRunHistoryAd")) {
                if (hookVoid(m)) d++;
            }
        }
        log("AdManager bool=" + a + " void=" + b + " load=" + d);
    }

    static void hookProvider(Class<?> c) {
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals("init")) continue;
            if (hookVoid(m)) n++;
        }
        if (n > 0) log("provider " + c.getSimpleName() + " init blocked=" + n + "/" + provDone.incrementAndGet());
    }

    static boolean hookFalse(Method m) {
        try {
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(Boolean.FALSE); }
            });
            return true;
        } catch (Throwable t) { return false; }
    }

    static boolean hookVoid(Method m) {
        try {
            final Class<?> rt = m.getReturnType();
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setResult(rt == boolean.class ? Boolean.FALSE : (rt == int.class ? Integer.valueOf(0) : null));
                }
            });
            return true;
        } catch (Throwable t) { return false; }
    }
}
