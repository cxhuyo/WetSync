package com.cxhuyo.wtsync;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 统一日志出口: logcat + 落盘。
 *
 * 关键约束（输入法卡死的防护）：**任何线程调用 log() 都不做文件 I/O**。
 * 旧实现每次 log 都在 MainHookLogger.class 上加锁并 open/write/flush/close，
 * 一旦落盘线程卡住（存储慢、日志文件过大），等待该锁的主线程（输入法主线程）
 * 会一起卡住 —— 表现就是「输入法卡死，点输入框唤不起」。
 * 现在改为：log() 只做一次无阻塞 offer（队列满则丢弃），由单独线程批量落盘。
 */
final class MainHookLogger {

    static final String TAG = "LspModTemplate";

    private static volatile MainHook module;
    private static volatile File logFile;
    /** 宿主包名：日志兜底目录必须落在「当前进程自己」的私有目录里。 */
    private static volatile String hostPkg;

    /** 由 MainHook 在 moduleLoaded / packageReady 时调用（微信等非输入法宿主必须设置）。 */
    static void setHost(String pkg) {
        if (pkg != null && !pkg.isEmpty()) hostPkg = pkg;
    }

    /** 有界队列：满了直接丢弃，绝不阻塞调用方 */
    private static final ArrayBlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(4096);
    private static volatile boolean writerStarted = false;

    static void bind(MainHook m) {
        module = m;
    }

    /** 非阻塞：调用线程只做字符串拼接 + offer */
    static void log(String msg) {
        String line;
        try {
            line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + msg;
        } catch (Throwable t) {
            line = msg;
        }
        try {
            Log.i(TAG, line);
        } catch (Throwable ignore) {}
        try {
            QUEUE.offer(line);
            startWriter();
        } catch (Throwable ignore) {}
    }

    private static synchronized void startWriter() {
        if (writerStarted) return;
        writerStarted = true;
        Thread t = new Thread(MainHookLogger::drainLoop, "WtLog");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void drainLoop() {
        StringBuilder sb = new StringBuilder(8192);
        FileOutputStream out = null;
        long lastFlush = 0;
        while (true) {
            try {
                String first = QUEUE.poll(1, TimeUnit.SECONDS);
                if (first == null) {
                    if (out != null && System.currentTimeMillis() - lastFlush > 1500) {
                        out.flush();
                        lastFlush = System.currentTimeMillis();
                    }
                    continue;
                }
                sb.setLength(0);
                sb.append(first).append('\n');
                String more;
                int n = 0;
                while (n++ < 200 && (more = QUEUE.poll()) != null) {
                    sb.append(more).append('\n');
                }
                if (out == null) {
                    File f = resolve();
                    if (f == null) continue;
                    if (f.length() > 4L * 1024 * 1024) {
                        // 简单滚动：超过 4MB 清空，避免无限增长拖慢写入
                        try { new FileOutputStream(f, false).close(); } catch (Throwable ignore) {}
                    }
                    out = new FileOutputStream(f, true);
                }
                out.write(sb.toString().getBytes("UTF-8"));
                out.flush();
                lastFlush = System.currentTimeMillis();
            } catch (Throwable t) {
                // 写失败：丢弃当前流，下次重开。**必须清掉缓存路径**，
                // 否则一旦首次解析到不可写目录（例如微信进程里解析成输入法的
                // /data/data/com.oplus.keyboard/files），就会永久粘死在那儿，
                // 后续即使宿主目录可写也再也不会尝试 —— 日志全程静默丢失。
                try { if (out != null) out.close(); } catch (Throwable ignore) {}
                out = null;
                logFile = null;
                try { Thread.sleep(500); } catch (InterruptedException ignore) {}
            }
        }
    }

    private static File resolve() {
        File cached = logFile;
        if (cached != null) return cached;
        synchronized (MainHookLogger.class) {
            if (logFile != null) return logFile;
            for (File f : candidates()) {
                try {
                    File dir = f.getParentFile();
                    if (dir == null) continue;
                    if (!dir.isDirectory()) dir.mkdirs();
                    if (dir.isDirectory() && dir.canWrite()) {
                        logFile = f;
                        return f;
                    }
                } catch (Throwable ignore) {}
            }
            // 都不行就不缓存，等宿主 Context/hostPkg 就绪后重试
            return null;
        }
    }

    private static File[] candidates() {
        java.util.ArrayList<File> list = new java.util.ArrayList<>(4);
        // 1) 外部存储（PC 端 adb 可直接 cat；仅当进程真有写权限时才用）
        try {
            File ext = android.os.Environment.getExternalStorageDirectory();
            if (ext != null) list.add(new File(new File(ext, "wtsync"), "module.log"));
        } catch (Throwable ignore) {}
        // 2) 宿主 Context（输入法进程里由 XiaobuInject.ctx 提供；
        //    微信等其它进程没有 bind 过，用 ActivityThread 反射拿 Application）
        android.content.Context c = null;
        try { c = XiaobuInject.ctx; } catch (Throwable ignore) {}
        if (c == null) {
            try {
                Object app = Class.forName("android.app.ActivityThread")
                        .getDeclaredMethod("currentApplication").invoke(null);
                if (app instanceof android.content.Context) c = (android.content.Context) app;
            } catch (Throwable ignore) {}
        }
        if (c != null) {
            try { list.add(new File(new File(c.getFilesDir(), "wtsync"), "module.log")); } catch (Throwable ignore) {}
        }
        // 3) 按宿主包名拼路径（Context 尚未就绪时的兜底）
        if (hostPkg != null) {
            list.add(new File("/data/data/" + hostPkg + "/files/wtsync/module.log"));
        }
        // 4) 最后才退回输入法目录（仅输入法进程可用）
        list.add(new File("/data/data/com.oplus.keyboard/files/wtsync/module.log"));
        return list.toArray(new File[0]);
    }

    private MainHookLogger() {
    }
}
