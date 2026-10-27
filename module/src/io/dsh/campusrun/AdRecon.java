package io.dsh.campusrun;

import android.app.Activity;
import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

public class AdRecon {
    static final String TAG = "CampusRunMod";
    static final String[] CLASSES = {
        "com.zjwh.android_wh_physicalfitness.advertise.AdManager",
        "com.zjwh.android_wh_physicalfitness.advertise.AdvertiseControl",
        "com.zjwh.android_wh_physicalfitness.advertise.AdConstant",
        "com.zjwh.android_wh_physicalfitness.advertise.AdPosition",
        "com.zjwh.android_wh_physicalfitness.advertise.AdPlatformType",
        "com.zjwh.android_wh_physicalfitness.advertise.AdMatchStrategyResolver",
        "com.zjwh.android_wh_physicalfitness.advertise.AdPvTrackIds",
        "com.zjwh.android_wh_physicalfitness.advertise.IAdRequestCallback",
        "com.zjwh.android_wh_physicalfitness.advertise.IAdRenderCallback",
        "com.zjwh.android_wh_physicalfitness.advertise.IAdDisplayCallback",
        "com.zjwh.android_wh_physicalfitness.advertise.IAdCallback",
        "com.zjwh.android_wh_physicalfitness.advertise.adgain.AdGainAdProvider",
        "com.zjwh.android_wh_physicalfitness.advertise.guandian.GuandianAdProvider",
        "com.zjwh.android_wh_physicalfitness.advertise.lingye.LingYeAdProvider",
        "com.zjwh.android_wh_physicalfitness.advertise.tianmu.TianMuAdProvider",
        "com.zjwh.android_wh_physicalfitness.advertise.xyz.XyzAdProvider",
        "com.zjwh.android_wh_physicalfitness.advertise.banner.BannerSlide",
        "com.zjwh.android_wh_physicalfitness.advertise.banner.BannerSlideComposer",
        "com.zjwh.android_wh_physicalfitness.advertise.banner.SdkNativeAdPayload",
        "com.zjwh.android_wh_physicalfitness.mvi.splash.SplashActivity",
        "com.zjwh.android_wh_physicalfitness.mvi.splash.AdActivity",
        "com.zjwh.android_wh_physicalfitness.mvi.home.HomeActivity",
    };

    static void log(String s) { try { XposedBridge.log(TAG + "[recon]: " + s); } catch (Throwable t) {} }

    static String sig(Method m) {
        StringBuilder b = new StringBuilder("(");
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) { if (i > 0) b.append(','); b.append(ps[i].getSimpleName()); }
        b.append(") -> ").append(m.getReturnType().getSimpleName());
        return b.toString();
    }

    static String args(Object[] a) {
        if (a == null) return "[]";
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) b.append(", ");
            Object o = a[i];
            String s = String.valueOf(o);
            if (s.length() > 200) s = s.substring(0, 200) + "...";
            b.append(s);
        }
        return b.append(']').toString();
    }

    static void run(final Activity a) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                StringBuilder sb = new StringBuilder();
                ClassLoader cl = a.getClassLoader();
                sb.append("classloader=").append(cl).append('\n');
                for (String n : CLASSES) {
                    try {
                        Class<?> c = Class.forName(n, false, cl);
                        sb.append("\n== ").append(n).append(" super=").append(c.getSuperclass()).append('\n');
                        for (Method m : c.getDeclaredMethods()) {
                            sb.append("  m ").append(Modifier.toString(m.getModifiers())).append(' ').append(m.getName()).append(sig(m)).append('\n');
                        }
                        for (Field f : c.getDeclaredFields()) {
                            sb.append("  f ").append(f.getName()).append(" : ").append(f.getType().getSimpleName()).append('\n');
                        }
                        hookClass(c);
                    } catch (Throwable e) {
                        sb.append("\n!! ").append(n).append(" : ").append(e).append('\n');
                    }
                }
                try {
                    File out = new File(a.getFilesDir(), "recon.txt");
                    FileOutputStream fos = new FileOutputStream(out);
                    fos.write(sb.toString().getBytes());
                    fos.close();
                    log("recon done -> " + out.getAbsolutePath() + " bytes=" + sb.length());
                } catch (Throwable e) { log("recon write fail " + e); }
            }
        }, "dsh-recon");
        t.setDaemon(true);
        t.start();
    }

    static void hookClass(Class<?> c) {
        for (final Method m : c.getDeclaredMethods()) {
            if (Modifier.isAbstract(m.getModifiers())) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        log("call " + m.getDeclaringClass().getSimpleName() + "." + m.getName() + args(p.args));
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Object r = p.getResult();
                        if (r != null) {
                            String s = String.valueOf(r);
                            if (s.length() > 400) s = s.substring(0, 400) + "...";
                            log("ret  " + m.getDeclaringClass().getSimpleName() + "." + m.getName() + " = " + s);
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }
    }
}
