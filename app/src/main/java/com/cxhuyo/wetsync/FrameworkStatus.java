package com.cxhuyo.wetsync;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 框架状态单例：模块进程与 LSPosed 之间唯一的通信出口。
 *
 * 背景：模块 App 自己跑在普通应用进程里，没有任何 Xposed 环境，不能直接问
 * 「我被注入了吗」。libxposed 从 API 101 起提供了 service 通道 —— 模块在清单里声明
 * {@code io.github.libxposed.service.XposedProvider}，管理器发现后会把自己的 binder
 * 递进来，模块侧用 {@link XposedServiceHelper} 拿到 {@link XposedService}，之后就能：
 *
 *   · getApiVersion()      → 框架是否在线（>=101 才算连上）
 *   · getFrameworkName()   → 框架名称/版本
 *   · getScope()           → 本模块被授权的作用域（判断宿主是否勾选）
 *   · getRunningTargets()  → 当前真正被注入的进程
 *   · requestScope(...)    → 直接向管理器发作用域申请，由管理器弹通知/对话框，
 *                            不需要模块自己去拉起管理器界面
 *
 * 这里的写法与 LuckyTool 的状态检测一致（反编译 com.luckyzyx.luckytool 1.x 可见
 * 同样的 XposedServiceHelper.registerListener + IdentityHashMap + api>=101 判定）。
 */
final class FrameworkStatus {

    interface Listener {
        void onFrameworkChanged();
    }

    private static final CopyOnWriteArraySet<Listener> LISTENERS = new CopyOnWriteArraySet<>();

    private static volatile XposedService service;
    private static volatile int apiVersion = -1;
    /** 本进程内是否曾经拿到过 binder（管理器断开后仍保留，用于区分「没连过」和「连过又断」） */
    private static volatile boolean everBound;
    private static volatile String frameworkName = "";
    private static volatile long frameworkVersionCode = -1;
    private static volatile String frameworkVersion = "";

    /** 作用域是「粘性」的：管理器可能在授权后断开 binder，这里保留最后一次已知结果 */
    private static volatile boolean scopeGranted;
    private static volatile long scopeCheckedAt;

    private static final String PREF = "framework_status";
    private static volatile SharedPreferences prefs;

    private FrameworkStatus() {}

    /** 在 Application.onCreate 里调用一次，越早越好：绑定是异步的，早注册早拿到 binder */
    static void init(Context app) {
        try {
            prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            // 上次已知的授权结果：管理器不一定每次都把 binder 递过来，
            // 记住它，页面在拿不到服务时也不至于把「已授权」显示成「未检测到」。
            scopeGranted = prefs.getBoolean("scope_" + StatusActivity.HOST_PKG, false);
            scopeCheckedAt = prefs.getLong("scope_at", 0L);
        } catch (Throwable ignore) {}
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override public void onServiceBind(XposedService s) {
                    service = s;
                    everBound = true;
                    refresh();
                    notifyListeners();
                }

                @Override public void onServiceDied(XposedService s) {
                    // 只清连接标记，保留最后一次读到的 api / 框架 / 作用域：
                    // 管理器经常在授完权就回收 binder，清空会让页面从「已激活」跳回「未检测到」。
                    if (service == s) service = null;
                    notifyListeners();
                }
            });
            MainHookLogger.log("event=app_fw_listener_registered");
        } catch (Throwable t) {
            MainHookLogger.log("event=app_fw_listener_fail " + t);
        }
    }

    static void addListener(Listener l) {
        if (l != null) LISTENERS.add(l);
    }

    static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    private static void notifyListeners() {
        for (Listener l : LISTENERS) {
            try { l.onFrameworkChanged(); } catch (Throwable ignore) {}
        }
    }

    /** 框架是否在线：拿到 service 且 API >= 101（与 LuckyTool 的判定一致） */
    static boolean alive() {
        XposedService s = service;
        return s != null && apiVersion >= 101;
    }

    static boolean connected() {
        return service != null;
    }

    /** 本进程内连上过管理器（含已断开），用来决定文案是「未授权」还是「未连接框架」 */
    static boolean everBound() {
        return everBound;
    }

    static int apiVersion() {
        return apiVersion;
    }

    static String frameworkLine() {
        if (service == null) return "";
        StringBuilder sb = new StringBuilder(
                frameworkName == null || frameworkName.isEmpty() ? "LSPosed" : frameworkName);
        if (frameworkVersion != null && !frameworkVersion.isEmpty()) sb.append(' ').append(frameworkVersion);
        if (frameworkVersionCode > 0) sb.append("（").append(frameworkVersionCode).append("）");
        return sb.toString();
    }

    static boolean scopeGranted() {
        return scopeGranted;
    }

    private static void setScope(boolean granted) {
        scopeGranted = granted;
        scopeCheckedAt = System.currentTimeMillis();
        try {
            SharedPreferences p = prefs;
            if (p != null) {
                p.edit().putBoolean("scope_" + StatusActivity.HOST_PKG, granted)
                        .putLong("scope_at", scopeCheckedAt).apply();
            }
        } catch (Throwable ignore) {}
    }

    static long scopeCheckedAt() {
        return scopeCheckedAt;
    }

    /** 重新读一遍框架信息 + 作用域 + 运行中的目标进程（binder 调用，别放主线程） */
    static void refresh() {
        XposedService s = service;
        if (s == null) return;
        try {
            apiVersion = s.getApiVersion();
        } catch (Throwable t) {
            apiVersion = -1;
        }
        try {
            frameworkName = s.getFrameworkName();
            frameworkVersion = s.getFrameworkVersion();
            frameworkVersionCode = s.getFrameworkVersionCode();
        } catch (Throwable ignore) {}
        try {
            List<String> scope = s.getScope();
            setScope(scope != null && scope.contains(StatusActivity.HOST_PKG));
        } catch (Throwable ignore) {}
        notifyListeners();
    }

    /** 宿主进程是否真的被注入（getRunningTargets 里有 com.oplus.keyboard） */
    static boolean hostRunning() {
        XposedService s = service;
        if (s == null) return false;
        try {
            List<HookedTarget> targets = s.getRunningTargets();
            if (targets == null) return false;
            for (HookedTarget ht : targets) {
                String pn = ht.getProcessName();
                if (pn != null && (pn.equals(StatusActivity.HOST_PKG)
                        || pn.startsWith(StatusActivity.HOST_PKG + ":"))) {
                    return true;
                }
            }
        } catch (Throwable ignore) {}
        return false;
    }

    interface ScopeCallback {
        void onApproved(List<String> scope);
        void onFailed(String message);
        /** 服务不可用，调用方自己决定怎么提示 */
        void onUnavailable();
    }

    /**
     * 向管理器发作用域申请。管理器会把它变成一条「作用域请求」通知（用户点一下即授权），
     * 整个过程不需要模块去拉起管理器界面。
     */
    static boolean requestScope(final String pkg, final ScopeCallback cb) {
        final XposedService s = service;
        if (s == null) {
            if (cb != null) cb.onUnavailable();
            return false;
        }
        try {
            s.requestScope(Collections.singletonList(pkg),
                    new XposedService.OnScopeEventListener() {
                        @Override public void onScopeRequestApproved(List<String> scope) {
                            setScope(scope == null || scope.isEmpty() || scope.contains(pkg));
                            notifyListeners();
                            if (cb != null) cb.onApproved(scope);
                        }

                        @Override public void onScopeRequestFailed(String message) {
                            // 管理器有时会先写 scope、再回一个 failed（例如「已有待处理请求」
                            // 或「作用域已授权」），直接提示失败会误导用户。
                            // 这里等 400ms 再读一次真实作用域，以 getScope() 为准。
                            MainHookLogger.log("event=app_scope_req_failed msg=" + message);
                            new Thread(() -> {
                                boolean granted = false;
                                List<String> now = null;
                                try { Thread.sleep(400); } catch (InterruptedException ignore) {}
                                try {
                                    now = s.getScope();
                                    granted = now != null && now.contains(pkg);
                                } catch (Throwable ignore) {}
                                if (granted) {
                                    setScope(true);
                                    notifyListeners();
                                    if (cb != null) cb.onApproved(now);
                                } else if (cb != null) {
                                    cb.onFailed(message == null || message.isEmpty()
                                            ? "未知原因" : message);
                                }
                            }, "WetScopeCheck").start();
                        }
                    });
            return true;
        } catch (Throwable t) {
            MainHookLogger.log("event=app_scope_req_fail " + t);
            if (cb != null) cb.onUnavailable();
            return false;
        }
    }
}
