package io.dsh.campusrun;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class ProbeModule implements IXposedHookLoadPackage {
    static final String TAG = "CampusRunMod";
    static final String PKG = "com.zjwh.android_wh_physicalfitness";

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if (!PKG.equals(lpparam.packageName)) return;
        log("INJECTED pkg=" + lpparam.packageName + " process=" + lpparam.processName);

        try {
            XposedHelpers.findAndHookMethod(Application.class, "attachBaseContext", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    log("HOOK attachBaseContext ok, proc=" + lpparam.processName);
                }
            });
        } catch (Throwable t) {
            log("hook attachBaseContext FAILED: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Application app = (Application) param.thisObject;
                    log("HOOK onCreate ok, app=" + app.getClass().getName());
                    try {
                        File f = new File(app.getFilesDir(), "mod-injected.txt");
                        FileOutputStream fos = new FileOutputStream(f, true);
                        fos.write(("injected ts=" + System.currentTimeMillis() + "\n").getBytes());
                        fos.close();
                        log("WROTE " + f.getAbsolutePath());
                    } catch (Throwable t) {
                        log("write failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            log("hook onCreate FAILED: " + t);
        }
    }

    static void log(String s) {
        Log.i(TAG, s);
        XposedBridge.log(TAG + ": " + s);
    }
}
