package com.cxhuyo.wetsync;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 模块主页：运行在模块自己的进程里，不往小布输入法里塞任何界面。
 *
 * 视觉按 ColorOS / COUI 的系统设置页写：大标题 + 圆角卡片 + 分组行 + 胶囊状态。
 * 全部用 framework 控件自绘，不引用宿主资源。
 *
 * 状态来源见 {@link FrameworkStatus}：模块进程通过 libxposed service 询问管理器，
 * 拿 API 版本、框架版本、作用域与已注入进程；作用域申请也走同一条 binder 通道，
 * 管理器会把它变成一条「作用域请求」通知，全程不需要打开管理器界面。
 */
public class StatusActivity extends Activity implements FrameworkStatus.Listener {

    static final String HOST_PKG = "com.oplus.keyboard";
    private static final String ACTION_PROBE = "com.cxhuyo.wetsync.PROBE";
    private static final String ACTION_PROBE_BACK = "com.cxhuyo.wetsync.PROBE_BACK";

    /** 模块编译时使用的 libxposed API 与最低要求 */
    private static final int API_TARGET = 102;
    private static final int API_MIN = 101;

    /** 实测通过的宿主版本区间（1.9.x，versionCode 1519000–1519999） */
    private static final int HOST_COMPAT_MIN = 1519000;
    private static final int HOST_COMPAT_MAX = 1519999;
    private static final String HOST_COMPAT_LABEL = "1.9.x";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private int cBg, cCard, cText, cSub, cAccent, cOk, cErr, cDivider;

    private TextView pillState;
    private TextView vScope, vModule, vApi, vFramework, vHost;
    private TextView vHint;

    private BroadcastReceiver probeRx;
    private long probeSeq;
    private long probeStartedAt;
    private long lastProbeReplyAt;

    /** 自动申请作用域只做一次，避免每次打开都弹通知 */
    private boolean autoScopeRequested;
    private volatile boolean hostRunningCache;

    /** 「关于」红点：记录已看过的安装时间，装 / 更新 / 重装都会变 */
    private static final String UI_PREFS = "ui_state";
    private static final String KEY_ABOUT_SEEN_UPDATE_TIME = "about_seen_update_time";
    private View aboutDot;

    // ================= 生命周期 =================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadColors();
        applySystemBars();
        setContentView(buildUi());
        registerProbeReceiver();
        FrameworkStatus.addListener(this);
        maybeForceOpenAbout();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 与 LuckyTool 一样：不做手动刷新，进页面后自己盯着。
        tick();
        updateAboutDot();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        FrameworkStatus.removeListener(this);
        ui.removeCallbacksAndMessages(null);
        if (probeRx != null) {
            try { unregisterReceiver(probeRx); } catch (Throwable ignore) {}
            probeRx = null;
        }
    }

    @Override
    public void onFrameworkChanged() {
        ui.post(() -> {
            render();
            maybeAutoRequestScope();
        });
    }

    private void loadColors() {
        cBg = getColor(R.color.bg);
        cCard = getColor(R.color.card);
        cText = getColor(R.color.text);
        cSub = getColor(R.color.sub);
        cAccent = getColor(R.color.accent);
        cOk = getColor(R.color.ok);
        cErr = getColor(R.color.err);
        cDivider = getColor(R.color.divider);
    }

    private void applySystemBars() {
        Window w = getWindow();
        w.setStatusBarColor(cBg);
        w.setNavigationBarColor(cBg);
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int flags = 0;
        if (!night) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flags);
    }

    // ================= 页面 =================

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(cBg);
        sv.setVerticalScrollBarEnabled(false);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(32));
        sv.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // targetSdk 35 起系统强制 edge-to-edge（Android 15+），标题会钻到状态栏下面。
        // 这里按 systemBars inset 补内边距；旧系统本来就不铺满，inset 为 0。
        sv.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = 0, bottom = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets sb = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars());
                top = sb.top;
                bottom = sb.bottom;
            }
            root.setPadding(dp(16), dp(18) + top, dp(16), dp(32) + bottom);
            return insets;
        });

        // ---- 标题 ----
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(30);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextColor(cText);
        title.setPadding(dp(6), 0, dp(6), 0);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.app_subtitle);
        subtitle.setTextSize(13);
        subtitle.setTextColor(cSub);
        subtitle.setPadding(dp(6), dp(6), dp(6), 0);
        root.addView(subtitle);

        // ---- 卡片一：运行状态 ----
        LinearLayout card1 = newCard();
        card1.addView(newRow("模块状态", null, pillState = pill("检测中", cSub)));
        card1.addView(divider());
        vScope = valueRow(card1, "作用域", HOST_PKG + "（小布输入法）");
        root.addView(card1);

        // ---- 卡片二：版本信息 ----
        LinearLayout card2 = newCard();
        vModule = valueRow(card2, "模块版本", BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）");
        card2.addView(divider());
        vApi = valueRow(card2, "API 版本", "libxposed API " + API_TARGET + " · 最低 " + API_MIN);
        card2.addView(divider());
        vFramework = valueRow(card2, "框架", "—");
        card2.addView(divider());
        vHost = valueRow(card2, "适配的小布输入法", hostVersionLine());
        root.addView(card2);

        // ---- 卡片三：操作 ----
        LinearLayout card3 = newCard();
        card3.addView(linkRow("申请作用域", "向 LSPosed 发送请求，在通知里点允许即可",
                v -> requestScope(false)));
        root.addView(card3);

        // ---- 卡片四：关于 ----
        LinearLayout card4 = newCard();
        card4.addView(buildAboutRow());
        root.addView(card4);

        // ---- 说明 ----
        vHint = new TextView(this);
        vHint.setTextSize(12);
        vHint.setTextColor(cSub);
        vHint.setLineSpacing(dp(4), 1f);
        vHint.setPadding(dp(10), dp(18), dp(10), 0);
        vHint.setText("勾选作用域后强行停止小布输入法，下次弹出键盘时生效。");
        root.addView(vHint);

        // ---- 固定说明：入口在哪 ----
        TextView howTitle = new TextView(this);
        howTitle.setText("如何使用");
        howTitle.setTextSize(13);
        howTitle.setTextColor(cText);
        howTitle.setAlpha(0.9f);
        howTitle.setPadding(dp(10), dp(24), dp(10), 0);
        root.addView(howTitle);

        TextView how = new TextView(this);
        how.setText("小布输入法 → 输入法设置 →「跨设备同步」\n"
                + "剪贴板、常用语、个人词库的开关和关联设备都在那一页。");
        how.setTextSize(12);
        how.setTextColor(cSub);
        how.setLineSpacing(dp(4), 1f);
        how.setPadding(dp(10), dp(6), dp(10), 0);
        root.addView(how);

        return sv;
    }

    private LinearLayout newCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cCard);
        bg.setCornerRadius(dp(20));
        card.setBackground(bg);
        card.setPadding(0, dp(4), 0, dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        card.setLayoutParams(lp);
        return card;
    }

    /** 普通行：左侧标题，右侧值 */
    private TextView valueRow(LinearLayout card, String title, String value) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(14);
        tv.setTextColor(cSub);
        tv.setSingleLine(false);
        tv.setMaxLines(2);
        tv.setGravity(Gravity.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(16);
        tv.setLayoutParams(lp);
        card.addView(newRow(title, null, tv));
        return tv;
    }

    /** 可点击行：右侧一个 chevron */
    private View linkRow(String title, String summary, View.OnClickListener click) {
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(cSub);
        View row = newRow(title, summary, arrow);
        row.setBackground(ripple());
        row.setOnClickListener(click);
        return row;
    }

    private View newRow(String title, String summary, View trailing) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(18), dp(10), dp(16), dp(10));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(cText);
        col.addView(t);

        if (summary != null && !summary.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(summary);
            s.setTextSize(12);
            s.setTextColor(cSub);
            s.setPadding(0, dp(2), 0, 0);
            col.addView(s);
        }
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (trailing != null) row.addView(trailing);
        return row;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(cDivider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.6f)));
        lp.leftMargin = dp(18);
        lp.rightMargin = dp(18);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView pill(String text, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(11));
        bg.setColor((color & 0x00FFFFFF) | 0x22000000);
        tv.setBackground(bg);
        tv.setPadding(dp(10), dp(4), dp(10), dp(4));
        return tv;
    }

    private void setPill(String text, int color) {
        if (pillState == null) return;
        pillState.setText(text);
        pillState.setTextColor(color);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(11));
        bg.setColor((color & 0x00FFFFFF) | 0x22000000);
        pillState.setBackground(bg);
    }

    private Drawable ripple() {
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            try { return getDrawable(tv.resourceId); } catch (Throwable ignore) {}
        }
        return null;
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    void toast(String s) {
        try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); } catch (Throwable ignore) {}
    }

    // ================= 关于 =================

    private View buildAboutRow() {
        LinearLayout trailing = new LinearLayout(this);
        trailing.setOrientation(LinearLayout.HORIZONTAL);
        trailing.setGravity(Gravity.CENTER_VERTICAL);

        aboutDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xFFF03A3A);
        aboutDot.setBackground(dot);
        aboutDot.setContentDescription("有更新提示");
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(9), dp(9));
        dotLp.rightMargin = dp(8);
        trailing.addView(aboutDot, dotLp);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(cSub);
        trailing.addView(arrow);

        View row = newRow("关于", "版本信息与赞赏支持", trailing);
        row.setBackground(ripple());
        row.setOnClickListener(v -> openAbout());
        updateAboutDot();
        return row;
    }

    /** 首次安装、覆盖更新、重装（lastUpdateTime 变化）都会自动打开关于页 */
    private void maybeForceOpenAbout() {
        if (!aboutShouldPrompt()) return;
        ui.postDelayed(this::openAbout, 350);
    }

    private void openAbout() {
        markAboutSeen();
        try {
            startActivity(new Intent(this, AboutActivity.class));
        } catch (Throwable t) {
            MainHookLogger.log("event=about_open_fail " + t);
        }
    }

    private boolean aboutShouldPrompt() {
        return lastUpdateTime() != aboutSeenTime();
    }

    private void updateAboutDot() {
        if (aboutDot == null) return;
        aboutDot.setVisibility(aboutShouldPrompt() ? View.VISIBLE : View.GONE);
    }

    private void markAboutSeen() {
        try {
            getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit()
                    .putLong(KEY_ABOUT_SEEN_UPDATE_TIME, lastUpdateTime()).apply();
        } catch (Throwable ignore) {}
        updateAboutDot();
    }

    private long aboutSeenTime() {
        try {
            return getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                    .getLong(KEY_ABOUT_SEEN_UPDATE_TIME, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private long lastUpdateTime() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ================= 版本 =================

    private String hostVersionLine() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(HOST_PKG, 0);
            boolean ok = pi.versionCode >= HOST_COMPAT_MIN && pi.versionCode <= HOST_COMPAT_MAX;
            return pi.versionName + (ok ? " · 已验证" : " · 未验证（按 " + HOST_COMPAT_LABEL + " 适配）");
        } catch (Throwable t) {
            return "未安装";
        }
    }

    // ================= 框架状态 =================

    private int tickCount;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refreshFramework();
            // 广播探活不能太频繁：没连上管理器时每 5 秒一次；
            // 连上之后每 15 秒一次，只为补充「宿主进程是否在跑」这一项判断。
            if (!FrameworkStatus.connected() || (++tickCount % 3) == 0) probe();
            ui.postDelayed(this, 5000);
        }
    };

    private void tick() {
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    /** binder 调用放后台线程，避免管理器卡住时阻塞 UI */
    private void refreshFramework() {
        new Thread(() -> {
            FrameworkStatus.refresh();
            hostRunningCache = FrameworkStatus.hostRunning();
            ui.post(() -> {
                render();
                maybeAutoRequestScope();
            });
        }, "WetSyncFw").start();
    }

    /** 首次打开且确实没授权时，自动发一次作用域请求（只发一次） */
    private void maybeAutoRequestScope() {
        if (autoScopeRequested) return;
        if (!FrameworkStatus.connected()) return;
        if (FrameworkStatus.scopeGranted()) return;
        autoScopeRequested = true;
        ui.postDelayed(() -> {
            if (!FrameworkStatus.scopeGranted()) requestScope(true);
        }, 600);
    }

    // ================= 广播探活（兜底） =================

    private void registerProbeReceiver() {
        probeRx = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null || !ACTION_PROBE_BACK.equals(intent.getAction())) return;
                lastProbeReplyAt = System.currentTimeMillis();
                render();
            }
        };
        IntentFilter f = new IntentFilter(ACTION_PROBE_BACK);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(probeRx, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(probeRx, f);
        } catch (Throwable t) {
            MainHookLogger.log("event=app_probe_reg_fail " + t);
        }
    }

    private void probe() {
        final long seq = ++probeSeq;
        probeStartedAt = System.currentTimeMillis();
        try {
            Intent i = new Intent(ACTION_PROBE);
            i.setPackage(HOST_PKG);
            i.putExtra("from", getPackageName());
            sendBroadcast(i);
        } catch (Throwable t) {
            MainHookLogger.log("event=app_probe_send_fail " + t);
        }
        ui.postDelayed(() -> {
            if (seq != probeSeq) return;
            if (lastProbeReplyAt < probeStartedAt) render();
        }, 1500);
    }

    // ================= 渲染 =================

    private void render() {
        boolean connected = FrameworkStatus.connected();
        boolean everBound = FrameworkStatus.everBound();
        boolean alive = FrameworkStatus.alive();
        boolean lowApi = everBound && FrameworkStatus.apiVersion() > 0 && FrameworkStatus.apiVersion() < API_MIN;
        boolean scoped = FrameworkStatus.scopeGranted();
        boolean probeAlive = System.currentTimeMillis() - lastProbeReplyAt < 5000;
        boolean running = hostRunningCache || probeAlive;

        if (scoped && running) {
            setPill("已激活", cOk);
        } else if (scoped) {
            setPill("已授权", cAccent);
        } else if (alive || everBound) {
            setPill(lowApi ? "框架版本过低" : "未授权", cErr);
        } else if (running) {
            setPill("已注入", cOk);
        } else {
            setPill("未检测到", cErr);
        }

        if (vScope != null) {
            vScope.setText(HOST_PKG + (scoped ? " · 已勾选" : " · 未勾选"));
        }
        int api = FrameworkStatus.apiVersion();
        if (vApi != null) {
            vApi.setText(api > 0
                    ? "libxposed API " + api + " · 模块目标 " + API_TARGET
                    : "libxposed API " + API_TARGET + " · 最低 " + API_MIN);
        }
        if (vFramework != null) {
            String fw = FrameworkStatus.frameworkLine();
            vFramework.setText(fw == null || fw.isEmpty() ? "—" : fw);
        }

        if (vHint == null) return;
        if (scoped && running) {
            vHint.setText("模块已注入当前输入法进程，复制内容会立即参与跨设备同步。");
        } else if (scoped) {
            vHint.setText("作用域已勾选，但小布输入法进程还没起来。\n"
                    + "随便点开一个输入框把键盘弹出来，本页会自动更新。");
        } else if (alive || everBound) {
            vHint.setText("点「申请作用域」后，LSPosed 会推送一条授权通知，\n"
                    + "在通知里点允许即可，不需要打开管理器。授权后强行停止小布输入法一次。");
        } else if (running) {
            vHint.setText("已确认模块注入到了小布输入法进程，但还没连上管理器服务，\n"
                    + "读不到作用域。把管理器启动一次后重开本应用即可。");
        } else {
            vHint.setText("自动检测中：还没连上 LSPosed 服务。请确认模块已在管理器里启用，\n"
                    + "本页每 5 秒自动重试，也可以直接点「申请作用域」。");
        }
    }

    // ================= 作用域 =================

    private void requestScope(boolean silent) {
        // 已经授权过就不再发请求，避免管理器重复推「作用域请求」通知
        if (FrameworkStatus.scopeGranted()) {
            if (!silent) toast("作用域已授权，无需重复申请");
            return;
        }
        final boolean sent = FrameworkStatus.requestScope(HOST_PKG, new FrameworkStatus.ScopeCallback() {
            @Override public void onApproved(List<String> scope) {
                ui.post(() -> {
                    toast("作用域已授权，强行停止小布输入法后生效");
                    render();
                });
            }

            @Override public void onFailed(String message) {
                ui.post(() -> toast("作用域申请失败：" + message));
            }

            @Override public void onUnavailable() {
                ui.post(StatusActivity.this::render);
            }
        });
        // 只给一条 toast：不弹对话框，也不在失败时先说「已发送」
        if (!silent) {
            toast(sent ? "已向 LSPosed 发送作用域请求"
                    : "还没连上 LSPosed，先在管理器里启用本模块");
        }
    }
}
