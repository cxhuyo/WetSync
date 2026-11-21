package com.cxhuyo.wtsync;


import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * WetypeXiaobuSync —— 方案 B 独立客户端 + 小布注入（scope: com.oplus.keyboard）。
 */
public class MainHook extends XposedModule {

    private static final String TAG = "WTSYNC";

    public static final String TARGET_PACKAGE = "com.oplus.keyboard";

    public MainHook() { super(); }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        MainHookLogger.bind(this);
        // 进程名形如 com.tencent.mm:push —— 取冒号前的包名，
        // 让日志兜底目录落到「当前进程自己」的私有目录，避免跨 app 写失败。
        String pn = param.getProcessName();
        int ci = pn == null ? -1 : pn.indexOf(':');
        MainHookLogger.setHost(ci > 0 ? pn.substring(0, ci) : pn);
        MainHookLogger.log("event=module_loaded process=" + pn);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String pkgName = param.getPackageName();
        MainHookLogger.setHost(pkgName);
        // 微信进程探针：诊断「图片在剪贴板里、长按粘贴却无反应」。
        // 只挂系统类的观察 hook（永远 chain.proceed，不改变行为），
        // 把微信粘贴时真实看到/做到的事写进日志，定位卡在哪一环。
        if (!TARGET_PACKAGE.equals(pkgName)) {
            return;
        }
        MainHookLogger.log("event=package_ready");
        try {
            bindContextAsync(param.getClassLoader());
            // 剪贴板图片走 file:// URI（LSPosed 模块无法注册 ContentProvider）。
            // ClipData.Item.getUri() 会按进程级 VmPolicy 检查 file:// 暴露，
            // 而消费方（小布自己的监听协程）在另一个时机会再调一次 getUri()，
            // 那时我们临时放行的策略早已恢复 → 抛 FileUriExposedException → 整条链路断掉。
            // 所以在这里一次性关掉该检测，代价是本进程内 file:// 违规不再报警（release 应用本不依赖）。
            try {
                android.os.StrictMode.setVmPolicy(
                        new android.os.StrictMode.VmPolicy.Builder().build());
                MainHookLogger.log("event=vm_policy_relaxed");
            } catch (Throwable ignore) {}

            startMainThreadWatchdog();
            XiaobuInject.locate(param.getClassLoader());
            // 词库读写诊断（仅当 files/wtsync/dict_diag.on 存在时执行一次，全后台）
            try { DictDiag.schedule(); } catch (Throwable ignore) {}
            XiaobuSettingsHook.install(this, param.getClassLoader());
            XiaobuSettingsHook.installMainOverlay(this, param.getClassLoader());
            java.lang.reflect.Method spc = android.content.ClipboardManager.class
                    .getDeclaredMethod("setPrimaryClip", android.content.ClipData.class);
            hook(spc).intercept(chain -> {
                try {
                    android.content.ClipData cd = (android.content.ClipData) chain.getArg(0);
                    // getItemCount()>0 必查：空 ClipData 的 getItemAt(0) 会抛异常，
                    // 异常逃出 hook 会打断调用方（输入法进程内任何写剪贴板的组件），
                    // 这是「输入法偶发卡死」的重点嫌疑人之一。
                    if (cd != null && cd.getItemCount() > 0
                            && !XiaobuInject.isSynced(cd.getDescription())) {
                        CharSequence t = cd.getItemAt(0).getText();
                        if (t != null) {
                            MainHookLogger.log("event=clip_tx_local len=" + t.length());
                            WtClient.onLocalCopy();      // 本地复制 → 立即推送
                        } else if (cd.getDescription() != null
                                && cd.getDescription().hasMimeType("image/*")
                                && cd.getItemAt(0).getUri() != null) {
                            // 图片：这里只做判定，解码/压缩/发送全在 onLocalImage 的后台线程里做。
                            // 主线程不能碰 BitmapFactory，4K 图解码会直接把输入法卡住。
                            MainHookLogger.log("event=clipimg_local mime="
                                    + cd.getDescription().getMimeType(0));
                            WtClient.onLocalImage(cd);
                        }
                    }
                } catch (Throwable hookErr) {
                    MainHookLogger.log("event=clip_hook_err " + hookErr);
                }
                return chain.proceed();
            });
            // 输入法窗口一出现，就是系统放开 WRITE_CLIPBOARD 的时刻。
            // 键盘收着时收到的图片会挂在待写队列里，这里立刻补写进剪贴板，
            // 用户接着长按「粘贴」就能拿到新图——全程不需要改 appops、不需要 root。
            try {
                java.lang.reflect.Method ows = android.inputmethodservice.InputMethodService.class
                        .getDeclaredMethod("onWindowShown");
                hook(ows).intercept(chain -> {
                    Object r = chain.proceed();
                    try { WtClient.onImeShown(); } catch (Throwable ignore) {}
                    return r;
                });
                MainHookLogger.log("event=hook_ime_shown");
            } catch (Throwable t) {
                MainHookLogger.log("event=hook_ime_shown_fail " + t);
            }

            MainHookLogger.log("All hooks installed successfully");

        } catch (Throwable t) {
            MainHookLogger.log("hook install failed: " + stackOf(t));
        }
    }

    private static volatile boolean ENGINE_STARTED = false;
    public static volatile android.app.Activity lastActivity;

    private static volatile boolean WATCHDOG_STARTED = false;

    /**
     * 主线程卡顿看护：后台线程每 8s 向主线程投递一个探针，若 4s 内未被执行，
     * 说明宿主主线程（输入法主线程）被阻塞，立即把主线程当前栈写进日志。
     * 目的：把「输入法偶发卡死」变成可定位的现场证据，并在同一进程里不至于继续恶化。
     */
    public static void startMainThreadWatchdog() {
        if (WATCHDOG_STARTED) return;
        WATCHDOG_STARTED = true;
        Thread t = new Thread(() -> {
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            final long[] beat = {-1L};
            while (true) {
                try {
                    beat[0] = -1L;
                    long postAt = System.currentTimeMillis();
                    h.post(() -> beat[0] = System.currentTimeMillis());
                    Thread.sleep(4000);
                    long b = beat[0];
                    if (b < 0) {
                        // 探针未执行：主线程堵了
                        long waited = System.currentTimeMillis() - postAt;
                        MainHookLogger.log("event=main_blocked waited=" + waited + "ms");
                        StackTraceElement[] st = android.os.Looper.getMainLooper().getThread().getStackTrace();
                        for (int i = 0; i < Math.min(st.length, 24); i++) {
                            MainHookLogger.log("M|" + st[i]);
                        }
                    } else if (b - postAt > 1500) {
                        MainHookLogger.log("event=main_slow latency=" + (b - postAt) + "ms");
                    }
                    Thread.sleep(4000);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable ignore) {
                    try { Thread.sleep(8000); } catch (InterruptedException e) { return; }
                }
            }
        }, "WtWatchdog");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }


    /** Activity 就绪后启动引擎（幂等） */
    public static void ensureEngine(android.content.Context ctx) {
        try {
            MainHookLogger.log("event=engine_call");
            if (ctx != null) XiaobuInject.bindContext(ctx);
            if (!ENGINE_STARTED) {
                ENGINE_STARTED = true;
                MainHookLogger.log("event=engine_start");
                // 清场：历史版本写进相册的残留（Pictures/WtSyncClip），以及上次进程留在
                // 私有缓存里的那张图（新进程的剪贴板里已经没有它了）。
                try {
                    final android.content.Context c = ctx;
                    Thread purge = new Thread(() -> {
                        ClipImageStore.purgeLegacyGallery(c);
                        ClipImageStore.purgePrivate(c);
                    });
                    purge.setDaemon(true);
                    purge.start();
                } catch (Throwable ignore) {}
                new WtClient().start();
            }
        } catch (Throwable t) { MainHookLogger.log("event=engine_err " + t); }
    }


    /** Application 可能尚未创建, 轮询等待后绑定 context 并注册配置接收器 */
    private void bindContextAsync(final ClassLoader cl) {
        new Thread(() -> {
            for (int i = 0; i < 60; i++) {
                try {
                    Object app = cl.loadClass("android.app.ActivityThread")
                            .getDeclaredMethod("currentApplication").invoke(null);
                    if (app instanceof android.content.Context) {
                        XiaobuInject.bindContext((android.content.Context) app);
                        MainHookLogger.log("event=ctx_bound");
                        registerConfigReceiver();
                        ensureEngine((android.content.Context) app);
                        return;
                    }
                } catch (Throwable ignore) {}
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
            MainHookLogger.log("event=ctx_timeout");
        }, "WtCtx").start();
    }

    /** 一站式配置: 接收设置页广播 → 落盘凭证 → 重启客户端 / 提交匹配码 */
    private void registerConfigReceiver() {
        final android.content.Context ctx = XiaobuInject.ctx;
        if (ctx == null) { MainHookLogger.log("event=cfg_recv_noctx"); return; }
        android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                String a = i.getAction();
                MainHookLogger.log("event=cfg action=" + a);
                try {
                    java.io.File dir = new java.io.File(ctx.getFilesDir(), "wtsync");
                    dir.mkdirs();
                    if ("com.cxhuyo.wtsync.CONFIG".equals(a)) {
                        write(new java.io.File(dir, "uin.txt"), i.getStringExtra("uin"));
                        write(new java.io.File(dir, "group.txt"), i.getStringExtra("group"));
                        write(new java.io.File(dir, "token.txt"), i.getStringExtra("token"));
                        WtClient.restart();
                        reply(ctx, "配置已生效, 客户端已重启");
                    } else if ("com.cxhuyo.wtsync.BIND".equals(a)) {
                        if (WtClient.active != null) WtClient.active.bindCode(i.getStringExtra("code"));
                        reply(ctx, "匹配码已提交");
                    } else if ("com.cxhuyo.wtsync.STATUS".equals(a)) {
                        long u = WtClient.active != null ? 1 : 0;
                        reply(ctx, "宿主进程存活, 客户端=" + (u == 1 ? "运行中" : "未启动(缺凭证)"));
                    } else if ("com.cxhuyo.wtsync.SETID".equals(a)) {
                        // 一站式写入身份凭证（uin/name/code/priv），立即重启引擎
                        java.io.File sd = new java.io.File(ctx.getFilesDir(), "wtsync");
                        sd.mkdirs();
                        String id = nz(i.getStringExtra("uin")) + "\n" + nz(i.getStringExtra("name")) + "\n"
                                + nz(i.getStringExtra("code")) + "\n" + nz(i.getStringExtra("priv")) + "\n";
                        write(new java.io.File(sd, "identity.txt"), id);
                        MainHookLogger.log("event=setid_written len=" + id.length());
                        WtClient.restart();
                        reply(ctx, "身份已写入, 客户端已重启");
                    }
                } catch (Throwable t) { MainHookLogger.log("event=cfg_err " + t); }
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction("com.cxhuyo.wtsync.CONFIG");
        f.addAction("com.cxhuyo.wtsync.BIND");
        f.addAction("com.cxhuyo.wtsync.STATUS");
        f.addAction("com.cxhuyo.wtsync.SETID");
        if (android.os.Build.VERSION.SDK_INT >= 33)
            ctx.registerReceiver(r, f, 2); // RECEIVER_EXPORTED
        else
            ctx.registerReceiver(r, f);
        MainHookLogger.log("event=cfg_receiver_ok");
    }

    private static void write(java.io.File f, String s) {
        if (s == null) return;
        try { java.io.FileOutputStream o = new java.io.FileOutputStream(f);
              o.write(s.getBytes("UTF-8")); o.close(); } catch (Exception ignore) {}
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static void reply(android.content.Context ctx, String msg) {
        try {
            android.content.Intent i = new android.content.Intent("com.cxhuyo.wtsync.STATUS_BACK");
            i.setPackage("com.cxhuyo.wtsync");
            i.putExtra("msg", msg);
            ctx.sendBroadcast(i);
        } catch (Throwable ignore) {}
    }

    /** 文本摘要（诊断日志用，避免刷屏） */
    private static String brief(CharSequence s) {
        if (s == null) return "null";
        String t = s.toString().replace('\n', ' ');
        return t.length() > 36 ? t.substring(0, 36) + "…" : t;
    }

    /** 调用栈前几帧（定位是谁写剪贴板的） */
    private static String frames() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < Math.min(st.length, 7); i++) {
                if (i > 1) sb.append(" <- ");
                sb.append(st[i].getClassName()).append('#').append(st[i].getMethodName());
            }
            return sb.toString();
        } catch (Throwable t) { return "?"; }
    }

    static String stackOf(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
