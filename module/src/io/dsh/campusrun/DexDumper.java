package io.dsh.campusrun;

import android.app.Activity;
import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Adler32;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class DexDumper {
    static final String TAG = "CampusRunMod";
    static final AtomicInteger seq = new AtomicInteger();
    static final AtomicInteger found = new AtomicInteger();
    static final Set<Long> seen = new HashSet<>();
    static final AtomicBoolean installed = new AtomicBoolean(false);
    static final AtomicBoolean scanStarted = new AtomicBoolean(false);
    static volatile Context appCtx = null;

    static void log(String s) { try { XposedBridge.log(TAG + "[dump]: " + s); } catch (Throwable t) {} }

    public static void install(LoadPackageParam lp) {
        if (!installed.compareAndSet(false, true)) return;
        try { hookInMemoryDex(); } catch (Throwable t) { log("inmem hook fail " + t); }
        try { hookOpenInMemory(); } catch (Throwable t) { log("openinmem hook fail " + t); }
        try { hookDexClassLoader(); } catch (Throwable t) { log("dcl hook fail " + t); }
        try { hookDexFile(); } catch (Throwable t) { log("dexfile hook fail " + t); }
        log("installed");
    }

    public static void onActivity(Activity a) {
        try {
            if (appCtx == null && a != null) {
                Context c = a.getApplicationContext();
                if (c == null) c = a;
                appCtx = c;
                log("ctx ok " + c.getPackageName());
            }
        } catch (Throwable t) { log("ctx fail " + t); }
        startScan();
    }

    static void startScan() {
        if (true) { log("java scan disabled (native dumper active)"); return; }
        if (!scanStarted.compareAndSet(false, true)) return;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                for (int i = 0; i < 20 && appCtx == null; i++) {
                    try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                }
                if (appCtx == null) { log("scan aborted: no ctx"); return; }
                File out = dir(appCtx);
                if (out == null) { log("scan aborted: no dir"); return; }
                log("region dump start " + out.getAbsolutePath());
                try { dumpRegions(out); } catch (Throwable t) { log("region dump err " + t); }
                log("region dump done files=" + files + " totalMB=" + (bytesWritten / 1048576));
            }
        }, "dsh-dexdump");
        t.setDaemon(true);
        t.start();
    }

    static File dir(Context c) {
        try {
            File out = new File(c.getFilesDir(), "dexdump2");
            if (!out.exists()) out.mkdirs();
            return out;
        } catch (Throwable t) { log("dir fail " + t); return null; }
        }
    static volatile int files = 0;
    static volatile long bytesWritten = 0;

    // ---------- hooks ----------
    static void hookInMemoryDex() {
        Class<?> c = XposedHelpers.findClass("dalvik.system.InMemoryDexClassLoader", DexDumper.class.getClassLoader());
        XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    File out = appCtx == null ? null : dir(appCtx);
                    if (out == null) return;
                    for (Object a : param.args) {
                        if (a instanceof ByteBuffer) dumpBuffer((ByteBuffer) a, out, "inmem");
                        else if (a instanceof ByteBuffer[]) {
                            for (ByteBuffer b : (ByteBuffer[]) a) dumpBuffer(b, out, "inmemarr");
                        }
                    }
                } catch (Throwable t) { log("inmem dump err " + t); }
            }
        });
    }

    static void hookOpenInMemory() {
        Class<?> c = XposedHelpers.findClass("dalvik.system.DexFile", DexDumper.class.getClassLoader());
        for (final String name : new String[]{"openInMemoryDexFile", "openInMemoryDexFiles"}) {
            try {
                XposedBridge.hookAllMethods(c, name, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            File out = appCtx == null ? null : dir(appCtx);
                            if (out == null) return;
                            for (Object a : param.args) {
                                if (a instanceof ByteBuffer) dumpBuffer((ByteBuffer) a, out, "opened");
                                else if (a instanceof ByteBuffer[]) {
                                    for (ByteBuffer b : (ByteBuffer[]) a) dumpBuffer(b, out, "openedarr");
                                }
                            }
                        } catch (Throwable t) { log("open dump err " + t); }
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    static void hookDexClassLoader() {
        Class<?> c = XposedHelpers.findClass("dalvik.system.DexClassLoader", DexDumper.class.getClassLoader());
        XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object a0 = param.args.length > 0 ? param.args[0] : null;
                    if (a0 instanceof String) copyDexFile(new File((String) a0), "dcl");
                } catch (Throwable t) { log("dcl err " + t); }
            }
        });
    }

    static void hookDexFile() {
        Class<?> c = XposedHelpers.findClass("dalvik.system.DexFile", DexDumper.class.getClassLoader());
        try {
            XposedHelpers.findAndHookMethod(c, "loadDex", String.class, String.class, int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object a0 = param.args[0];
                        if (a0 instanceof String) copyDexFile(new File((String) a0), "loaddex");
                    } catch (Throwable t) { log("loaddex err " + t); }
                }
            });
        } catch (Throwable ignored) {}
    }

    static void copyDexFile(File f, String tag) {
        try {
            if (f == null || !f.isFile() || f.length() < 4096 || f.length() > 200L * 1024 * 1024) return;
            File out = appCtx == null ? null : dir(appCtx);
            if (out == null) return;
            byte[] buf = new byte[(int) f.length()];
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            try { raf.readFully(buf); } finally { raf.close(); }
            writeDex(buf, tag + "-" + f.getName(), out);
        } catch (Throwable t) { log("copy err " + t); }
    }

    static void dumpBuffer(ByteBuffer bb, File out, String tag) {
        try {
            if (bb == null) return;
            ByteBuffer d = bb.duplicate();
            d.order(ByteOrder.LITTLE_ENDIAN);
            int cap = d.capacity();
            if (cap < 4096) return;
            int magicAt = -1;
            for (int i = 0; i <= Math.min(cap - 8, 65536); i++) {
                if (d.get(i) == 'd' && d.get(i + 1) == 'e' && d.get(i + 2) == 'x' && d.get(i + 3) == 10) { magicAt = i; break; }
            }
            if (magicAt < 0) return;
            if (magicAt + 112 > cap) return;
            long fileSize = d.getInt(magicAt + 32) & 0xffffffffL;
            long classDefs = d.getInt(magicAt + 96) & 0xffffffffL;
            long hdr = d.getInt(magicAt + 36) & 0xffffffffL;
            log("buf dex off=" + magicAt + " size=" + fileSize + " classes=" + classDefs);
            if (hdr != 112 || fileSize < 4096 || fileSize > 200L * 1024 * 1024) return;
            if (magicAt + fileSize > cap) return;
            byte[] data = new byte[(int) fileSize];
            for (int i = 0; i < fileSize; i++) data[i] = d.get(magicAt + i);
            writeDex(data, tag, out);
        } catch (Throwable t) { log("buffer err " + t); }
    }

    static void writeDex(byte[] data, String tag, File out) {
        try {
            if (data.length < 112) return;
            Adler32 a = new Adler32();
            a.update(data, 12, data.length - 12);
            long sum = a.getValue();
            long expect = le32(data, 8);
            int classes = (int) le32(data, 96);
            if (sum != expect) { log("bad sum " + tag + " got=" + sum + " want=" + expect); return; }
            if (!seen.add(sum)) return;
            File f = new File(out, "dex_" + seq.incrementAndGet() + "_" + data.length + "_c" + classes + "_" + tag + ".dex");
            FileOutputStream fos = new FileOutputStream(f);
            try { fos.write(data); } finally { fos.close(); }
            found.incrementAndGet();
            files++;
            bytesWritten += data.length;
            log("SAVED " + f.getName());
        } catch (Throwable t) { log("write err " + t); }
    }

    // ---------- 整段区域 dump（r--p 匿名/memfd，256KB~128MB） ----------
    static void dumpRegions(File out) throws Exception {
        if (true) {
            int n = 0;
            BufferedReader br0 = new BufferedReader(new FileReader("/proc/self/maps"));
            while (br0.readLine() != null) n++;
            br0.close();
            log("maps only count=" + n);
            File link = new File(out, "mlink");
            try { link.delete(); } catch (Throwable ignored) {}
            try { java.nio.file.Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get("/proc/self/mem")); log("symlink ok"); } catch (Throwable t) { log("symlink fail " + t); }
            RandomAccessFile m2 = new RandomAccessFile(link, "r");
            log("mem open probe ok");
            m2.close();
            log("mem closed");
            return;
        }
        BufferedReader br = new BufferedReader(new FileReader("/proc/self/maps"));
        RandomAccessFile mem = new RandomAccessFile("/proc/self/mem", "r");
        StringBuilder index = new StringBuilder();
        try {
            String line;
            int idx = 0;
            while ((line = br.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 2) continue;
                if (p[1].length() < 4 || p[1].charAt(0) != 'r' || p[1].charAt(1) != '-') continue;
                String rng = p[0];
                int dash = rng.indexOf('-');
                if (dash < 0) continue;
                long s = Long.parseLong(rng.substring(0, dash), 16);
                long e = Long.parseLong(rng.substring(dash + 1), 16);
                long size = e - s;
                String name = p.length > 5 ? p[5] : "";
                if (size < 262144L || size > 128L * 1024 * 1024) continue;
                if (!interesting(name)) continue;
                idx++;
                File f = new File(out, "reg_" + idx + "_" + Long.toHexString(s) + "_" + size + ".bin");
                long wrote = copyRange(mem, s, size, f);
                files++;
                bytesWritten += wrote;
                index.append(idx).append(' ').append(Long.toHexString(s)).append(' ').append(size)
                     .append(' ').append(p[1]).append(' ').append(name).append(' ').append(wrote).append('\n');
                log("region " + idx + " " + (size / 1024) + "KB wrote=" + (wrote / 1024) + "KB");
            }
        } finally {
            try { br.close(); } catch (Throwable ignored) {}
            try { mem.close(); } catch (Throwable ignored) {}
        }
        FileOutputStream fos = new FileOutputStream(new File(out, "maps.txt"));
        try { fos.write(index.toString().getBytes()); } finally { fos.close(); }
    }

    static boolean interesting(String n) {
        if (n.length() == 0) return true;
        if (n.startsWith("[anon")) {
            if (n.contains("jit-code") || n.contains("gfx-") || n.contains("GL_") || n.contains("vulkan")) return false;
            if (n.contains("cfi") || n.contains("stack")) return false;
            return true;
        }
        if (n.startsWith("/memfd:") || n.startsWith("/dev/ashmem")) return true;
        return false;
    }

    static long copyRange(RandomAccessFile mem, long start, long size, File f) {
        byte[] buf = new byte[1024 * 1024];
        long wrote = 0;
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            long pos = start;
            long end = start + size;
            while (pos < end) {
                int want = (int) Math.min(buf.length, end - pos);
                int n;
                try { mem.seek(pos); n = mem.read(buf, 0, want); } catch (Throwable t) { break; }
                if (n <= 0) break;
                fos.write(buf, 0, n);
                wrote += n;
                pos += n;
            }
        } catch (Throwable t) {
            log("copyRange err " + t);
        } finally {
            if (fos != null) { try { fos.close(); } catch (Throwable ignored) {} }
        }
        return wrote;
    }

    static long le32(byte[] b, int o) {
        return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8) | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24);
    }
}
