package com.cxhuyo.wetsync;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ScrollView;

/**
 * 「跨设备同步」二级页 —— 完全复用小布输入法原生控件与布局：
 *  - 行布局直接 inflate 宿主的 coui_preference_withlittlewiget_assignment_in_right（带右箭头）
 *    与 coui_switch_with_divider_preference（带开关）
 *  - 开关用宿主 com.coui.appcompat.couiswitch.COUISwitch（@android:id/checkbox）
 *  - 右箭头用宿主 assignment_icon
 *  - 卡片底色/圆角/边距按实测值（#1A1A1A、22dp、16dp）重建，分组时首/中/尾分别取圆角
 *
 * 功能：跨设备复制粘贴（剪贴板同步开关）、本机信息、查看匹配码、关联设备。
 * 不含：AI 录音笔同步、隔空传送。
 */
public final class SyncPage {

    // ---- 配色：从宿主实况视图采样，自适应明暗主题 ----
    private static final int TITLE_C = 0xFFE9E9E9;
    private static final int RIPPLE_C = 0x22FFFFFF;
    private static final float CARD_RADIUS_DP = 22f;
    private static final float MARGIN_DP = 16f;

    /** 主题采样结果：其它二级页（模块状态）复用同一套配色 */
    static final class Theme {
        int pageBg = 0xFF000000;
        int cardBg = 0xFF1A1A1A;
        int titleC = TITLE_C;
        int summaryC = 0xFF969696;
        boolean light;
    }

    static Theme sampleTheme(Activity act) {
        Theme t = new Theme();
        try {
            View root = act.getWindow().getDecorView();
            final float[] bestTitle = {0};
            final float[] bestSummary = {Float.MAX_VALUE};
            walk(root, v -> {
                android.graphics.drawable.Drawable bg = v.getBackground();
                if (bg instanceof android.graphics.drawable.ColorDrawable) {
                    int c = ((android.graphics.drawable.ColorDrawable) bg).getColor();
                    int lum = (c >> 16 & 0xff) + (c >> 8 & 0xff) + (c & 0xff);
                    if (lum < 24) t.pageBg = c;
                }
                if (v instanceof TextView) {
                    CharSequence s = ((TextView) v).getText();
                    if (s == null || s.length() == 0) return;
                    float sz = ((TextView) v).getTextSize();
                    // 标题字号最大（约 17sp），摘要次之（约 13sp）
                    if (sz > bestTitle[0] && sz < 200) {
                        bestTitle[0] = sz;
                        t.titleC = ((TextView) v).getCurrentTextColor();
                    }
                    if (sz > 20 && sz < bestSummary[0]) {
                        bestSummary[0] = sz;
                        t.summaryC = ((TextView) v).getCurrentTextColor();
                    }
                }
                if (bg instanceof GradientDrawable) {
                    GradientDrawable g = (GradientDrawable) bg;
                    if (g.getCornerRadius() > 0 && g.getColor() != null) {
                        t.cardBg = g.getColor().getDefaultColor();
                    }
                }
            });
        } catch (Throwable ignore) {}
        // 主题判定只看 Configuration.uiMode。不要从 DecorView 采样文字色来推断：
        // 宿主设置页里存在不随主题走的隐藏 TextView，实测深色模式下会采到 #000000，
        // 一旦判成浅色就会把行标题刷成纯黑，压在 #1A1A1A 卡片上完全看不见。
        int mode = android.content.res.Configuration.UI_MODE_NIGHT_NO;
        try {
            mode = act.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        } catch (Throwable ignore) {}
        boolean light = mode != android.content.res.Configuration.UI_MODE_NIGHT_YES;
        t.light = light;
        if (light) {
            t.pageBg = 0xFFF0F1F2;
            t.cardBg = 0xFFFFFFFF;
            t.titleC = 0xFF1A1A1A;
            t.summaryC = 0xFF6B6B6B;
        } else {
            t.pageBg = 0xFF000000;
            t.cardBg = 0xFF1A1A1A;
            t.titleC = 0xFFE9E9E9;
            t.summaryC = 0xFF9E9E9E;
        }
        return t;
    }

    private interface Vv { void visit(View v); }

    private static void walk(View v, Vv vv) {
        if (v == null) return;
        vv.visit(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), vv);
        }
    }

    private static final String PREF = "wtsync_page";
    private static final String K_CLIP = "clip_enabled";

    private static volatile boolean OPEN = false;
    private static volatile View overlay;
    private static volatile View hostContent;   // 推入动画时被左移的宿主 content
    /** 挂进宿主导航容器时的页面本体 / 被覆盖的宿主页（nav_host_fragment_container） */
    private static volatile View navPage;
    /** 当前页面业务内容根（改名/解绑后就地重建） */
    private static volatile View pageBody;
    private static volatile View navPrev;

    /**
     * 页面栈：同一 Activity 里可以叠着开二级页（同步页 → 模块状态）。
     * 每压入一页就记下「被压住的那一页 + 它自己的上一页」，关闭时原样回退，
     * 否则 navPage/navPrev 被新页覆盖后，旧页会失去引用、返回键再也关不掉。
     */
    private static final class Frame {
        View page;
        View prev;
    }
    private static final java.util.ArrayDeque<Frame> STACK = new java.util.ArrayDeque<>();
    /** 进入页面自动同步的冷却时间戳（防止 refreshContent 触发的重建反复拉起同步） */
    private static volatile long LAST_AUTO_SYNC = 0;

    public static boolean isOpen() { return OPEN; }

    /** 宿主返回键：二级页打开时先关页面 */
    public static boolean close() {
        if (!OPEN) return false;
        // 挂进宿主导航容器路径：用宿主原生 slide_out_right / slide_in_left 动画对向退出
        if (navPage != null) {
            final View page = navPage;
            final View prev = navPrev;
            // 逐层回退：栈里还有上一页就恢复它，否则整组页面关闭
            Frame back = STACK.isEmpty() ? null : STACK.pop();
            navPage = back == null ? null : back.page;
            navPrev = back == null ? null : back.prev;
            OPEN = navPage != null;
            try {
                Activity act = (Activity) page.getContext();
                int outR = idOf(act, "slide_out_right", "anim");
                int inL = idOf(act, "slide_in_left", "anim");
                if (prev != null) {
                    prev.setVisibility(View.VISIBLE);
                    prev.setTranslationX(0);
                    prev.setAlpha(1f);
                    prev.requestLayout();
                    prev.invalidate();
                    if (inL != 0) prev.startAnimation(AnimationUtils.loadAnimation(act, inL));
                }
                if (outR != 0) {
                    Animation out = AnimationUtils.loadAnimation(act, outR);
                    out.setAnimationListener(new Animation.AnimationListener() {
                        @Override public void onAnimationStart(Animation a) {}
                        @Override public void onAnimationRepeat(Animation a) {}
                        @Override public void onAnimationEnd(Animation a) {
                            try {
                                if (page.getParent() instanceof ViewGroup)
                                    ((ViewGroup) page.getParent()).removeView(page);
                            } catch (Throwable ignore) {}
                        }
                    });
                    page.startAnimation(out);
                } else {
                    ((ViewGroup) page.getParent()).removeView(page);
                }
                MainHookLogger.log("event=navc_close_ok");
            } catch (Throwable t) {
                MainHookLogger.log("event=navc_close_fail " + t);
                try {
                    if (page.getParent() instanceof ViewGroup)
                        ((ViewGroup) page.getParent()).removeView(page);
                } catch (Throwable ignore) {}
            }
            return true;
        }
        final View rv = overlay;
        OPEN = false;
        overlay = null;
        if (rv == null) return true;
        final int sw = rv.getContext().getResources().getDisplayMetrics().widthPixels;
        // 退出过渡：二级页向右滑出 100%（与原生 slide_out_right 一致），宿主页从 -100% 滑回
        rv.animate().setDuration(300)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .translationX(sw)
                .withEndAction(() -> {
                    try {
                        if (rv.getParent() instanceof ViewGroup)
                            ((ViewGroup) rv.getParent()).removeView(rv);
                    } catch (Throwable ignore) {}
                }).start();
        if (hostContent != null) {
            hostContent.setVisibility(View.VISIBLE);
            hostContent.animate().setDuration(300)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .translationX(0).start();
            hostContent = null;
        }
        return true;
    }

    public static void show(final Activity act) {
        if (act == null) return;
        if (OPEN) {
            // 关键：只在上一次打开的那一页**仍挂在当前 Activity 上**时才认为是重复打开。
            // 用户直接从设置页划走/返回桌面导致 Activity 被销毁时，OPEN 会残留 true，
            // 之后每次点击都会被这里静默吞掉（表现就是「点了没反应」）。
            boolean alive = false;
            try {
                View p = navPage;
                alive = p != null && p.isAttachedToWindow() && p.getContext() == act;
            } catch (Throwable ignore) {}
            if (alive) {
                MainHookLogger.log("event=page_skip_open");
                return;
            }
            MainHookLogger.log("event=page_stale_reset");
            OPEN = false;
            navPage = null;
            navPrev = null;
            pageBody = null;
            STACK.clear();
        }
        MainHookLogger.log("event=page_enter act=" + act.getClass().getName());
        // 首选：挂进宿主原生导航容器 nav_host_fragment_container，
        //      inflate 原生骨架 → 复用真实 AppBarBlurOverlayView 毛玻璃 + 原生四向滑动动画
        if (mountPage(act, buildContent(act, act.getLayoutInflater()), "跨设备同步", null)) return;
        // 兜底：decor 浮层（保留此前对齐原生的动画/圆角/圆形返回钮）
        showFallback(act);
    }

    // ============ 挂入宿主原生导航容器 nav_host_fragment_container ============

    /**
     * 为什么不是「真的 androidx Fragment」？
     * 宿主的 androidx 被 R8 重名化了（实测 FragmentManager 的类名就是 androidx.fragment.app.E，
     * getFragments 也不存在，且模块的 ClassLoader 里连 androidx.core.widget.NestedScrollView 都
     * 加载不到）。因此：
     *  - 任何「extends androidx.fragment.app.Fragment」的模块类，编译期名字与宿主的 Fragment
     *    不是同一个 Class，FragmentTransaction 会类型不匹配；
     *  - 反过来，凡是被布局 XML 引用过的类（CoordinatorLayout / AppBarBlurOverlayView /
     *    NestedScrollView…）R8 必须保留原名，所以可以在运行期用宿主 ClassLoader 反射拿到。
     *
     * 于是采取等效做法：
     *  1. inflate 宿主原生骨架 coui_preference_list_with_appbar_fragment（含原生
     *     AppBarBlurOverlayView 真实毛玻璃 + COUIAppBarLayout{COUIToolbar}）；
     *  2. 用宿主 ClassLoader 反射 new 出真正的 androidx NestedScrollView，替换掉骨架里
     *     的 @android:id/content，继承其 LayoutParams（含 appbar_scrolling_view_behavior），
     *     使其成为 CoordinatorLayout 的直接子节点 —— 这样嵌套滚动才会分发给
     *     AppBarBlurOverlayBehavior，毛玻璃才真正联动；
     *  3. 把它加进宿主真正的 nav_host_fragment_container 所在的同一个父布局槽位，
     *     因此矩形位置与真实导航目的地完全一致（而非 decor 浮层）；
     *  4. 进出场用宿主自己的 slide_in_right / slide_out_left / slide_in_left / slide_out_right
     *     动画资源（就是 NavController 用的那四个，300ms + 默认加减速），两页同时位移。
     */
    /**
     * 通用挂载：把任意自绘页面挂进宿主的导航容器，获得与原生二级页一致的
     * AppBar 毛玻璃、slide_in_right / slide_out_left 进出场动画、以及侧滑/返回键的退场。
     *
     * @param content 页面内容（会被放进宿主的 NestedScrollView，随 appbar 联动滚动）
     * @param title   AppBar 标题
     * @param overlay 叠在骨架最上层的浮层（可为 null），如右侧索引条
     */
    static boolean mountPage(Activity act, View content, CharSequence title, View overlay) {
        int skeletonId = idOf(act, "coui_preference_list_with_appbar_fragment", "layout");
        if (skeletonId == 0) { MainHookLogger.log("event=navc_no_skeleton"); return false; }

        View navHost = findNavHostView(act);
        if (navHost == null) { MainHookLogger.log("event=navc_no_container"); return false; }
        ViewGroup parent = (ViewGroup) navHost.getParent();
        if (parent == null) { MainHookLogger.log("event=navc_no_parent"); return false; }

        View root;
        try {
            root = act.getLayoutInflater().inflate(skeletonId, parent, false);
        } catch (Throwable t) {
            MainHookLogger.log("event=navc_inflate_fail " + t);
            return false;
        }
        if (root == null || !(root instanceof ViewGroup)) { MainHookLogger.log("event=navc_root_bad"); return false; }

        // --- 原生滚动容器（真正的 Host NestedScrollView，保证毛玻璃联动）---
        View scroller = newHostNestedScrollView(act);
        if (scroller == null) { MainHookLogger.log("event=navc_no_scroller"); return false; }
        pageBody = content;
        ((ViewGroup) scroller).addView(content, new FrameLayout.LayoutParams(MATCH, MATCH));

        ViewGroup cl = (ViewGroup) root;
        FrameLayout contentSlot = findContentSlot(cl, act);
        boolean replaced = false;
        if (contentSlot != null) {
            int idx = cl.indexOfChild(contentSlot);
            ViewGroup.LayoutParams lp = contentSlot.getLayoutParams();   // 内含 appbar_scrolling_view_behavior
            scroller.setId(android.R.id.content);
            try {
                cl.removeView(contentSlot);
                cl.addView(scroller, idx >= 0 ? idx : 0, lp);
                replaced = true;
            } catch (Throwable t) {
                MainHookLogger.log("event=navc_replace_fail " + t);
            }
        }
        if (!replaced) {
            MainHookLogger.log("event=navc_replace_skipped");
            return false;
        }

        setupHostToolbar(root, act, title);
        if (overlay != null) {
            try {
                ((ViewGroup) root).addView(overlay, new ViewGroup.LayoutParams(MATCH, MATCH));
            } catch (Throwable t) { MainHookLogger.log("event=navc_overlay_fail " + t); }
        }

        // --- 进出场动画：宿主原生资源 ---
        int idx = parent.indexOfChild(navHost);
        // 注意：不能直接复用 navHost 的 LayoutParams 对象 —— ConstraintLayout.LayoutParams
        // 内部携带 ConstraintWidget 状态，两个 View 共享同一对象会把宿主容器的布局态搞坏
        // （实测宿主 fragment_container 被压成 0x0，退出后主页全空白）。必须拷贝一份。
        ViewGroup.LayoutParams navLp = navHost.getLayoutParams();
        ViewGroup.LayoutParams useLp;
        try {
            @SuppressWarnings("unchecked")
            java.lang.reflect.Constructor<ViewGroup.LayoutParams> ctor =
                    (java.lang.reflect.Constructor<ViewGroup.LayoutParams>)
                            navLp.getClass().getConstructor(navLp.getClass());
            useLp = ctor.newInstance(navLp);
        } catch (Throwable t) {
            useLp = new ViewGroup.LayoutParams(MATCH, MATCH);
        }
        // 允许在已有二级页上再叠一页（同步页 → 模块状态）：先记下当前可见页，
        // 新页进场时把它左移并隐藏，关闭时再从栈里取回来恢复显示。
        final View oldTop = (navPage != null && navPage.isAttachedToWindow()) ? navPage : navHost;
        Frame frame = new Frame();
        frame.page = navPage;
        frame.prev = navPrev;
        STACK.push(frame);
        // 叠页时的插入位置：必须排在「当前可见页」之后，否则新页会被旧页盖住
        int insertAt = idx + 1;
        if (oldTop != navHost) {
            int oldIdx = parent.indexOfChild(oldTop);
            if (oldIdx >= 0) insertAt = oldIdx + 1;
        }
        parent.addView(root, insertAt, useLp);

        int inR = idOf(act, "slide_in_right", "anim");
        int outL = idOf(act, "slide_out_left", "anim");
        if (inR != 0 && outL != 0) {
            Animation in = AnimationUtils.loadAnimation(act, inR);
            root.startAnimation(in);
            Animation out = AnimationUtils.loadAnimation(act, outL);
            final View oldPage = oldTop;
            out.setAnimationListener(new Animation.AnimationListener() {
                @Override public void onAnimationStart(Animation a) {}
                @Override public void onAnimationRepeat(Animation a) {}
                @Override public void onAnimationEnd(Animation a) {
                    if (navPage != null) oldPage.setVisibility(View.GONE);  // 仍在当前页时才隐藏宿主
                }
            });
            oldPage.startAnimation(out);
        } else {
            navHost.setVisibility(View.GONE);
        }

        navPage = root;
        navPrev = oldTop;
        OPEN = true;
        MainHookLogger.log("event=navc_mount_ok parent=" + parent.getClass().getName()
                + " scroller=" + scroller.getClass().getName());
        return true;
    }

    /**
     * 定位 NavHostFragment 的容器视图。
     * 首选按常见 id 名查；兜底用 NavController 的标记 tag ——
     * NavHostFragment.onCreateView 会 Navigation.setViewNavController(view, controller)，
     * 即把 NavController 挂到 @id/nav_controller_view_tag 这个 view tag 上，扫描即可定位。
     */
    private static View findNavHostView(Activity act) {
        String[] names = {"nav_host_fragment_container", "nav_host_fragment", "fragment_container", "nav_fragment"};
        for (String n : names) {
            int id = idOf(act, n, "id");
            if (id == 0) continue;
            View v = act.findViewById(id);
            if (v != null) {
                MainHookLogger.log("event=navc_found_by_id name=" + n);
                return v;
            }
        }
        int tagId = idOf(act, "nav_controller_view_tag", "id");
        if (tagId != 0) {
            final View[] hit = new View[1];
            walk(act.getWindow().getDecorView(), v -> {
                if (hit[0] == null) {
                    Object tag = v.getTag(tagId);
                    if (tag != null && v instanceof ViewGroup) hit[0] = v;
                }
            });
            if (hit[0] != null) {
                MainHookLogger.log("event=navc_found_by_tag cls=" + hit[0].getClass().getName());
                return hit[0];
            }
        }
        return null;
    }

    /**
     * 找出宿主骨架里承载内容的那个 FrameLayout（mI.xml 中直接挂在 CoordinatorLayout 下、
     * 带 app:layout_behavior 的 @android:id/content 槽位）。
     * 不能只依赖 id：实测该 id 在本宿主上并非 android.R.id.content，故按「类名严格等于
     * android.widget.FrameLayout 的直接子节点」来定位，更稳。
     */
    private static FrameLayout findContentSlot(ViewGroup cl, Activity act) {
        FrameLayout fallback = null;
        for (int i = 0; i < cl.getChildCount(); i++) {
            View ch = cl.getChildAt(i);
            if (ch instanceof FrameLayout) {
                if ("android.widget.FrameLayout".equals(ch.getClass().getName())) {
                    MainHookLogger.log("event=navc_slot_hit i=" + i);
                    return (FrameLayout) ch;
                }
                if (fallback == null) fallback = (FrameLayout) ch;
            }
        }
        int cid = idOf(act, "content", "id");
        if (cid != 0) {
            View v = cl.findViewById(cid);
            if (v instanceof FrameLayout) return (FrameLayout) v;
        }
        MainHookLogger.log("event=navc_slot_none children=" + cl.getChildCount());
        return fallback;
    }

    /**
     * 用宿主 ClassLoader 实例化真正的 androidx NestedScrollView。
     * 失败（宿主混淆掉该类）时退回框架 ScrollView —— 页面仍可用，但毛玻璃不由滚动驱动。
     */
    private static View newHostNestedScrollView(Activity act) {
        try {
            Class<?> cls = Class.forName("androidx.core.widget.NestedScrollView",
                    true, act.getClassLoader());
            // 注意：宿主经 R8 裁剪后，单参 (Context) 构造器很可能已不存在，
            // 依次尝试 (Context) / (Context, AttributeSet) / (Context, AttributeSet, int)。
            View v = null;
            try {
                v = (View) cls.getConstructor(Context.class).newInstance(act);
            } catch (Throwable ignore1) {
                try {
                    v = (View) cls.getConstructor(Context.class, android.util.AttributeSet.class)
                            .newInstance(act, null);
                } catch (Throwable ignore2) {
                    v = (View) cls.getConstructor(Context.class, android.util.AttributeSet.class, int.class)
                            .newInstance(act, null, 0);
                }
            }
            if (v == null) throw new IllegalStateException("null scroller");
            try {
                cls.getMethod("setFillViewport", boolean.class).invoke(v, true);
            } catch (Throwable ignore) {}
            MainHookLogger.log("event=host_nsv_ok cls=" + cls.getName());
            return v;
        } catch (Throwable t) {
            MainHookLogger.log("event=host_nsv_fail " + t);
            ScrollView sv = new ScrollView(act);
            sv.setFillViewport(true);
            return sv;
        }
    }

    /** 退出：our page slide_out_right，宿主页 slide_in_left，与走进的方向对称（全程位移） */

    /** 构造二级页业务内容（三张卡片）。滚动容器由挂载层提供，以便 CoordinatorLayout 联动原生毛玻璃。 */
    static View buildContent(final Activity act, final LayoutInflater inf) {
        final float d = act.getResources().getDisplayMetrics().density;
        // 与宿主原生开关行完全一致：行布局 coui_preference（行根 COUICustomListSelectedLinearLayout，
        // 32dp/32dp padding + 空 widget_frame），开关小部件 coui_preference_widget_switch
        // 塞进 widget_frame（该小部件 focusable=false/clickable=false —— 这是宿主
        // COUISwitchPreference 的标准做法，只有开关不可点，整行触摸才走
        // COUISwitchTouchEventDelegate 代理并驱动行按压动画）。
        final int L_JUMP = idOf(act, "coui_preference_withlittlewiget_assignment_in_right", "layout");
        final int L_SW = idOf(act, "coui_preference", "layout");
        final int L_SW_WIDGET = idOf(act, "coui_preference_widget_switch", "layout");
        final int L_PLAIN = idOf(act, "coui_preference_assignment_in_right", "layout");
        final int ARROW = idOf(act, "assignment_icon", "id");
        if (L_JUMP == 0 || L_SW == 0 || L_PLAIN == 0) {
            MainHookLogger.log("event=page_layout_missing j=" + L_JUMP + " s=" + L_SW + " p=" + L_PLAIN);
        }
        final Prefs sp = new Prefs(act);
        Theme th = sampleTheme(act);
        // 行布局自带的 textColor 本身就是主题感知的（实测深色下 title=e6ffffff / summary=8affffff），
        // 所以不覆盖行内文字 —— 覆盖色一旦判错，整片文字就会看不清。
        // 只有自绘控件（设备图标 / 箭头 / 分组标题 / 对话框）才需要显式给色。
        thC = 0;
        thS = 0;
        thTitle = th.titleC;
        thSum = th.summaryC;
        MainHookLogger.log("event=theme_v2 light=" + th.light
                + " card=" + Integer.toHexString(th.cardBg)
                + " title=" + Integer.toHexString(th.titleC)
                + " summary=" + Integer.toHexString(th.summaryC));

        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        // 水平方向零 padding：行必须全宽 —— 卡片视觉（左右内缩 16dp、圆角、底色）由
        // 每行行根 COUICardListSelectedItemLayout 自绘，与原生列表完全一致。
        // （旧实现 body 16dp + 行内 32dp 叠加成 48dp，正是「左右留空偏大」的根因。）
        body.setPadding(0, dp(d, 8), 0, dp(d, 24));

        // ================= 卡片一：同步开关 =================
        // 两个开关都是**服务端的组功能开关**（GroupInfo.funcSwitch，位：1剪贴板 2常用语 4个人词库 8语音速记），
        // 状态由服务端保存、同组设备共享；本地只跟随显示，不再假装开关后又弹回。
        // 「同步个人词库」已移除：协议里只有 FuncSwitchUserDict 这个能力位，
        // 没有任何词库数据字段/命令（filter=8/15 拉回的 Group 只有 groupId/devices/clipboard/hotwords/funcSwitch），
        // 而且小布本地也没有「个人词库」这个概念，留着只会是个假开关。
        // 移除时**不改动**服务端那一位，避免顺带改掉微信输入法各端原本的行为。
        LinearLayout card1 = card(act, th.cardBg, d);
        final long mask = WtClient.funcSwitchMask();
        View rClip = makeSwitchRow(act, inf, L_SW, L_SW_WIDGET, "跨设备复制粘贴", "复制文字、图片后，关联设备可粘贴");
        bindSwitch(rClip, (mask & WxClient.FUNC_CLIPBOARD) != 0 || (mask == 0 && sp.clip()), on -> {
            sp.setClip(on);
            WtClient.setClipboardEnabled(on);
            setSwitchQuiet(rClip, on);
            WtClient.setFuncSwitch(WxClient.FUNC_CLIPBOARD, on, (ok, msg) -> new android.os.Handler(
                    android.os.Looper.getMainLooper()).post(() -> toast(act,
                    ok ? (on ? "已开启跨设备复制粘贴" : "已关闭跨设备复制粘贴") : msg)));
        });
        View rWord = makeSwitchRow(act, inf, L_SW, L_SW_WIDGET, "同步常用语", "关联设备可同步常用语的内容");
        bindSwitch(rWord, (mask & WxClient.FUNC_HOT_WORDS) != 0, on -> {
            setSwitchQuiet(rWord, on);
            WtClient.setFuncSwitch(WxClient.FUNC_HOT_WORDS, on, (ok, msg) -> new android.os.Handler(
                    android.os.Looper.getMainLooper()).post(() -> {
                toast(act, ok ? (on ? "已开启同步常用语" : "已关闭同步常用语") : msg);
                if (!ok) { setSwitchQuiet(rWord, !on); return; }
                // 打开即立刻跑一次双向同步，用户不必再找别的入口
                if (on) syncNow(act, "正在同步常用语…");
            }));
        });
        // 个人词库：本地链路（微信输入法 → 小布），不需要设备组，所以开关状态存本地
        View rDict = makeSwitchRow(act, inf, L_SW, L_SW_WIDGET, "同步个人词库",
                "把微信输入法的个人词库并入小布，每 5 分钟自动一次");
        bindSwitch(rDict, WtClient.userDictEnabled(), on -> {
            setSwitchQuiet(rDict, on);
            WtClient.setUserDictEnabled(on);
            if (on) syncUserDictNow(act, "正在同步个人词库…");
            else toast(act, "已关闭同步个人词库");
        });
        card1.addView(rClip); card1.addView(rWord); card1.addView(rDict);
        card1.addView(makeSyncRow(act, inf, L_JUMP, ARROW));
        card1.addView(makeDictSyncRow(act, inf, L_JUMP, ARROW));
        finishCard(card1, d, th.cardBg);
        body.addView(card1, lp(d, 14));

        // 组里已有关联设备时，进入页面也顺手补一次同步（带冷却，避免和「打开即同步」互相触发死循环）
        if ((mask & WxClient.FUNC_HOT_WORDS) != 0 && !WtClient.devices().isEmpty()) {
            final long now = System.currentTimeMillis();
            if (now - LAST_AUTO_SYNC > 30000L) {
                LAST_AUTO_SYNC = now;
                act.getWindow().getDecorView().postDelayed(() -> {
                    try { WtClient.syncHotWordsNow(null); } catch (Throwable ignore) {}
                }, 1200);
            }
        }

        // ================= 卡片二：我的设备 =================
        TextView devHead = new TextView(act);
        devHead.setText("我的设备");
        devHead.setTextSize(13);
        devHead.setTextColor(th.summaryC);
        // 浅色主题下压暗一点当分组标题；深色主题下基色本身已偏灰，再乘 alpha 会糊掉
        devHead.setAlpha(th.light ? 0.75f : 1f);
        devHead.setPadding(dp(d, 16), 0, 0, 0);
        body.addView(devHead, lp(d, 24));

        LinearLayout card2 = card(act, th.cardBg, d);
        java.util.List<WtClient.Dev> dl = WtClient.devices();
        // 本机名称：与向服务器声明的一致（系统设备名/机型），而不是沿用服务器返回的旧名
        String selfName = WxClient.computeDeviceName();
        WtClient.Dev selfDev = null;
        for (WtClient.Dev dv : dl) { if (dv.self) { selfName = dv.name; selfDev = dv; break; } }
        if (selfName == null || selfName.isEmpty()) {
            selfName = WxClient.computeDeviceName();
        }
        // 本机行：图标 + 名称 + 「本机」；点击同样是改名（匹配码在下面单独一行）
        final String selfFinal = selfName;
        final long selfUin = selfDev != null ? selfDev.uin : 0;
        View selfRow = deviceRow(act, inf, L_PLAIN, selfFinal, "本机", true);
        if (selfUin != 0) {
            selfRow.setOnClickListener(v -> showRenameDialog(act, selfUin, selfFinal));
        }
        card2.addView(selfRow);
        for (WtClient.Dev dv : dl) {
            if (dv.self) continue;
            final WtClient.Dev d2 = dv;
            View r = deviceRow(act, inf, L_PLAIN, dv.name, "已关联", false);
            View del = trailingDeleteButton(act, th);
            del.setOnClickListener(v -> confirmUnbind(act, d2));
            attachTrailing(r, del);
            r.setOnClickListener(v -> showRenameDialog(act, d2.uin, d2.name));
            card2.addView(r);
        }
        View rCode = row(act, inf, L_JUMP, "查看匹配码", "生成六位关联码，供其他设备加入", ARROW);
        rCode.setOnClickListener(v -> showCodeDialog(act));
        card2.addView(rCode);
        finishCard(card2, d, th.cardBg);
        body.addView(card2, lp(d, 14));

        // ================= 卡片三：关联设备 =================
        LinearLayout card3 = card(act, th.cardBg, d);
        View rBind = row(act, inf, L_JUMP, "关联设备", "输入其他设备显示的关联码，或在本机生成", ARROW);
        rBind.setOnClickListener(v -> showJoinDialog(act));
        card3.addView(rBind);
        finishCard(card3, d, th.cardBg);
        body.addView(card3, lp(d, 14));

        // 滚动容器由挂载层提供（真正的宿主 NestedScrollView），这里只产业务内容
        return body;
    }

    /** 配置原生骨架里的 COUIToolbar：标题 + 返回钮（向左 chevron），点击走原生 slide_out_right */
    static void setupHostToolbar(View root, Activity act, CharSequence title) {
        int color = titleColorOf(act);
        View tb = findViewByClass(root.findViewById(idOf(act, "appbar", "id")), "Toolbar");
        if (tb == null) tb = findViewByClass(root, "Toolbar");
        if (tb == null) { MainHookLogger.log("event=toolbar_notfound"); return; }
        try { tb.getClass().getMethod("setTitle", CharSequence.class).invoke(tb, title); } catch (Throwable ignore) {}
        try { tb.getClass().getMethod("setTitleTextColor", int.class).invoke(tb, color); } catch (Throwable ignore) {}
        // 优先用宿主自带的返回图标资源（coui_back_arrow），保证图标样式与按钮尺寸和原生完全一致；
        // 取不到才退回自绘箭头。
        Drawable navIcon = hostNavIcon(act);
        if (navIcon == null) navIcon = makeChevronDrawable(act, color);
        try { tb.getClass().getMethod("setNavigationIcon", Drawable.class).invoke(tb, navIcon); } catch (Throwable ignore) {}
        // COUIToolbar 会自带 navigation 点击处理（或覆写 setNavigationOnClickListener），
        // 直接找到导航图标子 View 挂监听，比走 Toolbar API 可靠。
        View nav = findNavButtonView(tb);
        if (nav != null) {
            nav.setOnClickListener(v -> close());
            MainHookLogger.log("event=toolbar_nav_ok cls=" + nav.getClass().getName());
        } else {
            try { tb.getClass().getMethod("setNavigationOnClickListener", View.OnClickListener.class)
                    .invoke(tb, (View.OnClickListener) v -> close()); } catch (Throwable ignore) {}
            MainHookLogger.log("event=toolbar_nav_delegated");
        }
        // 状态栏避让：骨架是 edge-to-edge 的，原生 fragment 在代码里做 inset，我们手动补
        int sbh = statusBarHeight(act);
        View appBar = root.findViewById(idOf(act, "appbar", "id"));
        if (appBar == null) appBar = findViewByClass(root, "AppBarLayout");
        if (appBar != null && sbh > 0) {
            appBar.setPadding(appBar.getPaddingLeft(), appBar.getPaddingTop() + sbh,
                    appBar.getPaddingRight(), appBar.getPaddingBottom());
            MainHookLogger.log("event=appbar_inset sbh=" + sbh);
        }
    }

    /** 取宿主自带的返回图标（与原生二级页同一份资源，尺寸/样式天然一致） */
    private static Drawable hostNavIcon(Activity act) {        String[] names = {"coui_back_arrow", "coui_back_arrow_normal", "abc_ic_ab_back_material"};
        for (String n : names) {
            int id = idOf(act, n, "drawable");
            if (id == 0) continue;
            try {
                Drawable d = act.getResources().getDrawable(id, act.getTheme());
                if (d != null) {
                    MainHookLogger.log("event=navicon " + n + " " + d.getIntrinsicWidth() + "x" + d.getIntrinsicHeight());
                    return d;
                }
            } catch (Throwable t) {
                MainHookLogger.log("event=navicon_fail " + n + " " + t);
            }
        }
        return null;
    }

    /** 在 Toolbar 里找导航图标所在的子 View（ImageButton / ImageView 且可点击） */
    private static View findNavButtonView(View tb) {
        final View[] hit = new View[1];
        walk(tb, v -> {
            if (hit[0] != null || v == tb) return;
            String cn = v.getClass().getName();
            if ((cn.contains("ImageButton") || cn.contains("ImageView")) && v.isShown()) hit[0] = v;
        });
        return hit[0];
    }

    static int titleColorOf(Activity act) { return sampleTheme(act).titleC; }

    /**
     * 供其它自绘页复用的「原生返回图标」：优先宿主自己的 coui_back_arrow，
     * 取不到就用同一套 ChevronView 画一个细箭头，并按当前主题染色。
     */
    static Drawable backIcon(Activity act, int color) {
        Drawable d = hostNavIcon(act);
        if (d != null) {
            try {
                d = d.mutate();
                d.setTint(color);
                return d;
            } catch (Throwable ignore) {}
        }
        return makeChevronDrawable(act, color);
    }

    /**
     * 未染色的原始导航图标 —— 交给 Toolbar#setNavigationIcon 用，
     * 由 Toolbar 按 ?attr/colorControlNormal 自己着色（同步页就是这么做的）。
     * 提前 setTint 反而会被 Toolbar 的 tint 覆盖成灰色，箭头在深色下几乎看不见。
     */
    static Drawable rawNavIcon(Activity act) {
        Drawable d = hostNavIcon(act);
        return d != null ? d : makeChevronDrawable(act, 0xFFFFFFFF);
    }

    private static Drawable makeChevronDrawable(Activity act, int color) {
        float density = act.getResources().getDisplayMetrics().density;
        int sz = (int) (48 * density + 0.5f);
        ChevronView cv = new ChevronView(act, color, 0);   // 0 = 原生返回样式的细箭头
        cv.layout(0, 0, sz, sz);
        Bitmap b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        cv.draw(c);
        return new BitmapDrawable(act.getResources(), b);
    }

    private static View findViewByClass(View root, String contains) {
        if (root == null) return null;
        final View[] hit = new View[1];
        walk(root, v -> { if (hit[0] == null && v.getClass().getName().contains(contains)) hit[0] = v; });
        return hit[0];
    }

    private static void showFallback(final Activity act) {
        if (act == null || OPEN) return;
        MainHookLogger.log("event=page_enter act=" + act.getClass().getName());
        final float d = act.getResources().getDisplayMetrics().density;
        // 直接用宿主 Activity 的 LayoutInflater：自带正确的 COUI 主题与资源，
        // 才能 inflate 出 COUICustomListSelectedLinearLayout 等原生控件
        final Context host = act;
        final LayoutInflater inf = act.getLayoutInflater();
        final int L_JUMP = idOf(act, "coui_preference_withlittlewiget_assignment_in_right", "layout");
        final int L_SW = idOf(act, "coui_preference", "layout");
        final int L_SW_WIDGET = idOf(act, "coui_preference_widget_switch", "layout");
        final int L_PLAIN = idOf(act, "coui_preference_assignment_in_right", "layout");
        final int ARROW = idOf(act, "assignment_icon", "id");
        if (L_JUMP == 0 || L_SW == 0 || L_PLAIN == 0) {
            MainHookLogger.log("event=page_layout_missing j=" + L_JUMP + " s=" + L_SW + " p=" + L_PLAIN);
            showJoinDialog(act);
            return;
        }
        MainHookLogger.log("event=page_show jump=" + L_JUMP + " sw=" + L_SW + " arrow=" + ARROW);

        final Prefs sp = new Prefs(act);

        // ---------- 根 ----------
        final FrameLayout root = new FrameLayout(act);
        Theme th = sampleTheme(act);
        thC = 0;
        thS = 0;
        thTitle = th.titleC;
        thSum = th.summaryC;
        MainHookLogger.log("event=page_theme pageBg=" + Integer.toHexString(th.pageBg) + " cardBg=" + Integer.toHexString(th.cardBg) + " title=" + Integer.toHexString(th.titleC) + " summary=" + Integer.toHexString(th.summaryC));
        root.setBackgroundColor(th.pageBg);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setOnKeyListener((v, keyCode, ev) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                    && ev.getAction() == android.view.KeyEvent.ACTION_UP) {
                close();
                return true;
            }
            return false;
        });

        LinearLayout page = new LinearLayout(act);
        page.setOrientation(LinearLayout.VERTICAL);
        root.addView(page, new FrameLayout.LayoutParams(MATCH, MATCH));

        // ---------- 顶部栏（仿宿主 toolbar：返回 + 标题） ----------
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int sbH = statusBarHeight(act);
        bar.setPadding(dp(d, 4), sbH + dp(d, 12), dp(d, 12), dp(d, 4));
        // 圆形返回钮（仿 COUIToolbar navigationIcon 的圆形 ripple 触摸反馈）
        View back = roundBackButton(act, th.titleC, d);
        back.setOnClickListener(v -> close());
        bar.addView(back);
        TextView title = new TextView(act);
        title.setText("跨设备同步");
        title.setTextSize(20);
        title.setTextColor(th.titleC);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, WRAP, 1f);
        tl.leftMargin = dp(d, 4);
        bar.addView(title, tl);
        page.addView(bar);

        // ---------- 业务内容（与导航挂载共用 buildContent） ----------
        View sv = buildContent(act, inf);
        page.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        // 兜底路径无原生 AppBarBlurOverlayView：滚动毛玻璃近似（顶栏底色随滚动渐显）
        final int blurTh = dp(d, 56);
        sv.getViewTreeObserver().addOnScrollChangedListener(() -> {
            int sy = sv.getScrollY();
            int a = Math.min(255, (int) (sy * 255f / blurTh));
            bar.setBackgroundColor((th.pageBg & 0x00FFFFFF) | (a << 24));
        });

        // ---------- 挂到宿主窗口 ----------
        ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
        decor.addView(root, new ViewGroup.LayoutParams(MATCH, MATCH));
        overlay = root;
        OPEN = true;
        root.requestFocus();
        // 进入过渡：导航式推入——宿主页全程左移 -100% 移出屏幕，二级页从右滑入
        // （与原生 slide_in_right / slide_out_left 四向滑动一致：两页同时运动、各走全程、无投影）
        final int sw = act.getResources().getDisplayMetrics().widthPixels;
        hostContent = act.findViewById(android.R.id.content);
        if (hostContent != null) {
            hostContent.animate().setDuration(300)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .translationX(-sw).start();
        }
        root.setTranslationX(sw);
        root.animate().setDuration(300)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .translationX(0)
                .withEndAction(() -> {
                    if (hostContent != null) hostContent.setVisibility(View.GONE);
                }).start();
    }

    // ================= 我的设备 =================

    /**
     * 设备行：原生行布局 + 左侧设备图标（填充宿主布局自带的 img_layout 图标槽）
     * + 右侧 widget_frame 里的操作按钮（本机=查看匹配码 / 其它设备=解除关联）。
     * 图标槽与 widget_frame 都是宿主行布局自带的，所以视觉与原生设置行完全一致。
     */
    private static View deviceRow(Activity act, LayoutInflater inf, int layout,
                                 String name, String summary, boolean self) {
        View v = row(act, inf, layout, name, summary, 0);
        // 设备行不需要右箭头（本机用绿色按钮、其它设备用删除按钮作为 trailing）
        try {
            int arrowId = idOf(act, "assignment_icon", "id");
            if (arrowId != 0) {
                View a = v.findViewById(arrowId);
                if (a != null) a.setVisibility(View.GONE);
            }
        } catch (Throwable ignore) {}
        try {
            int iconId = idOf(act, "img_layout", "id");
            View slot = iconId != 0 ? v.findViewById(iconId) : null;
            if (slot instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) slot;
                g.removeAllViews();
                g.setVisibility(View.VISIBLE);
                int size = dp(act.getResources().getDisplayMetrics().density, 40);
                boolean pc = isDesktop(name);
                WtIconView icon = new WtIconView(act, thSum,
                        pc ? WtIconView.COMPUTER : WtIconView.PHONE,
                        36f, pc, pc ? 1.5f : 0f);
                if (pc) icon.addFillPath(WtIconView.COMPUTER_STAND);
                g.addView(icon, new FrameLayout.LayoutParams(size, size));
            }
        } catch (Throwable t) { MainHookLogger.log("event=devicon_fail " + t); }
        return v;
    }

    /** 名称像电脑就画显示器，否则画手机 */
    private static boolean isDesktop(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.contains("win") || n.contains("pc") || n.contains("mac")
                || n.contains("电脑") || n.contains("笔记本") || n.contains("book");
    }

    /** 把控件塞进行布局右侧的 widget_frame（原生行为：Preference widget 槽） */
    static void attachTrailing(View rowView, View trailing) {
        try {
            View frame = rowView.findViewById(android.R.id.widget_frame);
            if (frame instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) frame;
                g.removeAllViews();
                g.setVisibility(View.VISIBLE);
                g.addView(trailing, new ViewGroup.LayoutParams(WRAP, WRAP));
            }
        } catch (Throwable t) { MainHookLogger.log("event=trailing_fail " + t); }
    }


    /** 原生风格删除按钮（浅红圆底 + 红色垃圾桶） */
    private static View trailingDeleteButton(Context c, Theme th) {
        float d = c.getResources().getDisplayMetrics().density;
        FrameLayout box = new FrameLayout(c);
        int size = dp(d, 30);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(th.light ? 0x1AF44336 : 0x26F44336);
        bg.setShape(GradientDrawable.OVAL);
        box.setBackground(bg);
        box.addView(new WtIconView(c, 0xFFFF5252, WtIconView.DELETE, 54f, false, 0f),
                new FrameLayout.LayoutParams(size, size));
        box.setLayoutParams(new ViewGroup.LayoutParams(size, size));
        return box;
    }

    /**
     * 修改设备名称：宿主原生 COUIAlertDialogBuilder + COUIInputView（与「关联设备」同款）。
     * 结果通过 cmd79 同步到微信输入法服务器，其它设备拉取组信息即可看到新名字。
     */
    private static void showRenameDialog(final Activity act, final long targetUin, final String current) {
        if (targetUin == 0) { toast(act, "设备信息不完整"); return; }
        final float d = act.getResources().getDisplayMetrics().density;
        try {
            ClassLoader cl = act.getClassLoader();
            Class<?> inputCls = Class.forName(
                    "com.coui.appcompat.edittext.COUIInputView", true, cl);
            final Object input = inputCls.getConstructor(Context.class).newInstance(act);
            try { inputCls.getMethod("setTitle", CharSequence.class).invoke(input, "设备名称"); } catch (Throwable ignore) {}
            try { inputCls.getMethod("setMaxCount", int.class).invoke(input, 32); } catch (Throwable ignore) {}
            final android.widget.EditText et =
                    (android.widget.EditText) inputCls.getMethod("getEditText").invoke(input);
            et.setSingleLine(true);
            et.setText(current == null ? "" : current);
            try { et.setSelection(et.getText().length()); } catch (Throwable ignore) {}
            try {
                et.setFilters(new android.text.InputFilter[]{
                        new android.text.InputFilter.LengthFilter(32)});
            } catch (Throwable ignore) {}

            LinearLayout wrap = new LinearLayout(act);
            wrap.setOrientation(LinearLayout.VERTICAL);
            int hp = dp(d, 24);
            wrap.setPadding(hp, dp(d, 12), hp, 0);
            wrap.addView((View) input, new LinearLayout.LayoutParams(MATCH, dp(d, 96)));

            Class<?> bCls = Class.forName(
                    "com.coui.appcompat.dialog.COUIAlertDialogBuilder", true, cl);
            Object b = bCls.getConstructor(Context.class).newInstance(act);
            bCls.getMethod("setTitle", CharSequence.class).invoke(b, "修改设备名称");
            bCls.getMethod("setView", View.class).invoke(b, wrap);
            final android.content.DialogInterface.OnClickListener pos = (di, w) -> {
                String nm = et.getText().toString().trim();
                if (nm.isEmpty()) { toast(act, "名称不能为空"); return; }
                if (nm.equals(current)) return;
                toast(act, "正在修改…");
                WtClient.renameDevice(targetUin, nm, (ok, msg) -> new android.os.Handler(
                        android.os.Looper.getMainLooper()).post(() -> {
                    toast(act, msg);
                    if (ok) refreshContent(act);
                }));
            };
            bCls.getMethod("setPositiveButton", CharSequence.class,
                    android.content.DialogInterface.OnClickListener.class).invoke(b, "保存", pos);
            bCls.getMethod("setNegativeButton", CharSequence.class,
                    android.content.DialogInterface.OnClickListener.class).invoke(b, "取消", null);
            bCls.getMethod("show").invoke(b);
            MainHookLogger.log("event=rename_dialog_ok uin=" + targetUin);
            et.post(() -> {
                try {
                    et.requestFocus();
                    et.setSelection(et.getText().length());
                } catch (Throwable ignore) {}
            });
        } catch (Throwable t) {
            MainHookLogger.log("event=rename_dialog_fail " + t);
            toast(act, "无法打开修改对话框");
        }
    }

    /** 解除关联确认：原生 COUIAlertDialogBuilder（微信输入法同款文案） */
    private static void confirmUnbind(final Activity act, final WtClient.Dev dev) {
        try {
            Class<?> bCls = Class.forName(
                    "com.coui.appcompat.dialog.COUIAlertDialogBuilder", true, act.getClassLoader());
            Object b = bCls.getConstructor(Context.class).newInstance(act);
            bCls.getMethod("setTitle", CharSequence.class).invoke(b, "解除关联");
            bCls.getMethod("setMessage", CharSequence.class).invoke(b,
                    "解除设备后，「" + dev.name + "」将无法与这台设备同步复制粘贴。");
            final android.content.DialogInterface.OnClickListener pos = (di, w) -> {
                toast(act, "正在解除…");
                WtClient.unbindDevice(dev.uin, (ok, msg) -> new android.os.Handler(
                        android.os.Looper.getMainLooper()).post(() -> {
                    toast(act, msg);
                    if (ok) refreshContent(act);
                }));
            };
            bCls.getMethod("setPositiveButton", CharSequence.class,
                    android.content.DialogInterface.OnClickListener.class).invoke(b, "解除", pos);
            bCls.getMethod("setNegativeButton", CharSequence.class,
                    android.content.DialogInterface.OnClickListener.class).invoke(b, "取消", null);
            bCls.getMethod("show").invoke(b);
            MainHookLogger.log("event=unbind_dialog_ok uin=" + dev.uin);
        } catch (Throwable t) {
            MainHookLogger.log("event=unbind_dialog_fail " + t);
            toast(act, "无法打开对话框");
        }
    }

    /** 改名/解绑后原地重建页面内容（保持滚动容器与毛玻璃不变） */
    private static void refreshContent(Activity act) {
        try {
            View body = pageBody;
            if (body == null || !(body.getParent() instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) body.getParent();
            int idx = parent.indexOfChild(body);
            View fresh = buildContent(act, act.getLayoutInflater());
            parent.removeViewAt(idx);
            parent.addView(fresh, idx, new FrameLayout.LayoutParams(MATCH, MATCH));
            pageBody = fresh;
            MainHookLogger.log("event=page_refresh");
        } catch (Throwable t) { MainHookLogger.log("event=page_refresh_err " + t); }
    }



    /**
     * 设备图标：直接用微信输入法原生 vector 图标的 pathData（从 wxime.apk 的
     * res/r/a9/icon_transfer_android_mini.xml / icon_transfer_computer_mini.xml 提取），
     * 只做颜色替换以适配明暗主题，形状与官方完全一致。
     */
    static final class WtIconView extends View {
        static final String PHONE =
                "M25,0.5C26.657,0.5 28,1.843 28,3.5V32.5C28,34.157 26.657,35.5 25,35.5H11"
              + "L10.846,35.496C9.261,35.416 8,34.105 8,32.5V3.5C8,1.843 9.343,0.5 11,0.5H25Z"
              + "M11,2C10.172,2 9.5,2.672 9.5,3.5V32.5C9.5,33.328 10.172,34 11,34H25"
              + "C25.828,34 26.5,33.328 26.5,32.5V3.5C26.5,2.672 25.828,2 25,2H11Z"
              + "M21,31C21.276,31 21.5,31.224 21.5,31.5C21.5,31.776 21.276,32 21,32H15"
              + "C14.724,32 14.5,31.776 14.5,31.5C14.5,31.224 14.724,31 15,31H21Z";
        /** 显示器外框（描边 1.5） */
        static final String COMPUTER =
                "M5.845,7.188L30.155,7.188A1.45,1.45 0 0 1,31.605 8.637L31.605,24.038"
              + "A1.45,1.45 0 0 1,30.155 25.487L5.845,25.487A1.45,1.45 0 0 1,4.395 24.038"
              + "L4.395,8.637A1.45,1.45 0 0 1,5.845 7.188z";
        /** 显示器底座（填充） */
        static final String COMPUTER_STAND =
                "M15.685,24.801C16.044,24.801 16.335,25.092 16.335,25.451C16.335,27.275 15.48,28.99 14.033,30.088"
              + "H17.999H21.966C20.518,28.99 19.664,27.275 19.664,25.451C19.664,25.092 19.955,24.801 20.314,24.801"
              + "C20.673,24.801 20.964,25.092 20.964,25.451C20.964,26.916 21.673,28.29 22.868,29.138L23.376,29.499"
              + "C24.203,30.086 23.788,31.388 22.774,31.388H13.224C12.21,31.388 11.795,30.086 12.622,29.499L13.13,29.138"
              + "C14.325,28.29 15.035,26.916 15.035,25.451C15.035,25.092 15.326,24.801 15.685,24.801Z";
        static final String DELETE =
                "M37.5,14.4L48,14.4L48,18.6L43.8,18.6L43.8,45.9C43.8,47.06 42.86,48 41.7,48L12.3,48"
              + "C11.14,48 10.2,47.06 10.2,45.9L10.2,18.6L6,18.6L6,14.4L16.5,14.4L16.5,8.1"
              + "C16.5,6.94 17.44,6 18.6,6L35.4,6C36.56,6 37.5,6.94 37.5,8.1L37.5,14.4Z"
              + "M39.6,18.6L14.4,18.6L14.4,43.8L39.6,43.8L39.6,18.6Z"
              + "M20.7,24.9L24.9,24.9L24.9,37.5L20.7,37.5L20.7,24.9Z"
              + "M29.1,24.9L33.3,24.9L33.3,37.5L29.1,37.5L29.1,24.9Z"
              + "M20.7,10.2L20.7,14.4L33.3,14.4L33.3,10.2L20.7,10.2Z";

        private final android.graphics.Path path = new android.graphics.Path();
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float viewport;
        private android.graphics.Path extra;
        private android.graphics.Paint extraPaint;

        /** 追加一条纯填充路径（例如显示器底座） */
        void addFillPath(String d) {
            extra = new android.graphics.Path();
            SvgPath.parse(d, extra);
            extraPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            extraPaint.setStyle(android.graphics.Paint.Style.FILL);
            extraPaint.setColor(paint.getColor());
        }

        WtIconView(Context c, int color, String pathData, float viewport, boolean stroke, float strokeWidth) {
            super(c);
            this.viewport = viewport;
            if (stroke) {
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                paint.setStrokeWidth(strokeWidth);
            } else {
                paint.setStyle(android.graphics.Paint.Style.FILL);
            }
            paint.setColor(color);
            SvgPath.parse(pathData, path);
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float s = Math.min(w, h) / viewport * 0.86f;
            c.save();
            c.translate((w - viewport * s) / 2f, (h - viewport * s) / 2f);
            c.scale(s, s);
            c.drawPath(path, paint);
            if (extra != null) c.drawPath(extra, extraPaint);
            c.restore();
        }
    }

    /** 极简 SVG path 解析（支持 M/L/H/V/C/A/Z，微信输入法图标只用到这些） */
    static final class SvgPath {
        static void parse(String d, android.graphics.Path out) {
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("([MmLlHhVvCcAaZz])|(-?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?)")
                        .matcher(d);
                java.util.List<String> tok = new java.util.ArrayList<>();
                while (m.find()) tok.add(m.group());
                int i = 0;
                float cx = 0, cy = 0, sx = 0, sy = 0;
                char cmd = 'M';
                while (i < tok.size()) {
                    String t = tok.get(i);
                    if (t.length() == 1 && Character.isLetter(t.charAt(0))) {
                        cmd = t.charAt(0);
                        i++;
                        if (cmd == 'Z' || cmd == 'z') { out.close(); cx = sx; cy = sy; continue; }
                    }
                    switch (cmd) {
                        case 'M': cx = f(tok, i++); cy = f(tok, i++); out.moveTo(cx, cy); sx = cx; sy = cy; cmd = 'L'; break;
                        case 'm': cx += f(tok, i++); cy += f(tok, i++); out.moveTo(cx, cy); sx = cx; sy = cy; cmd = 'l'; break;
                        case 'L': cx = f(tok, i++); cy = f(tok, i++); out.lineTo(cx, cy); break;
                        case 'l': cx += f(tok, i++); cy += f(tok, i++); out.lineTo(cx, cy); break;
                        case 'H': cx = f(tok, i++); out.lineTo(cx, cy); break;
                        case 'h': cx += f(tok, i++); out.lineTo(cx, cy); break;
                        case 'V': cy = f(tok, i++); out.lineTo(cx, cy); break;
                        case 'v': cy += f(tok, i++); out.lineTo(cx, cy); break;
                        case 'C': {
                            float x1 = f(tok, i++), y1 = f(tok, i++), x2 = f(tok, i++), y2 = f(tok, i++),
                                    x = f(tok, i++), y = f(tok, i++);
                            out.cubicTo(x1, y1, x2, y2, x, y); cx = x; cy = y; break;
                        }
                        case 'c': {
                            float x1 = cx + f(tok, i++), y1 = cy + f(tok, i++), x2 = cx + f(tok, i++),
                                    y2 = cy + f(tok, i++), x = cx + f(tok, i++), y = cy + f(tok, i++);
                            out.cubicTo(x1, y1, x2, y2, x, y); cx = x; cy = y; break;
                        }
                        case 'A': case 'a': {
                            float rx = f(tok, i++), ry = f(tok, i++), rot = f(tok, i++);
                            boolean large = f(tok, i++) != 0, sweep = f(tok, i++) != 0;
                            float x = cmd == 'A' ? f(tok, i++) : cx + f(tok, i++);
                            float y = cmd == 'A' ? f(tok, i++) : cy + f(tok, i++);
                            arcTo(out, cx, cy, rx, ry, rot, large, sweep, x, y);
                            cx = x; cy = y; break;
                        }
                        default: i++; break;
                    }
                }
            } catch (Throwable t) { MainHookLogger.log("event=svgpath_fail " + t); }
        }

        private static float f(java.util.List<String> t, int i) {
            try { return Float.parseFloat(t.get(i)); } catch (Throwable e) { return 0f; }
        }

        /** 端点参数化的圆弧 → 若干三次贝塞尔（每段 ≤90°） */
        private static void arcTo(android.graphics.Path p, float x0, float y0,
                                  float rx, float ry, float rotDeg, boolean large, boolean sweep,
                                  float x, float y) {
            if (rx == 0 || ry == 0) { p.lineTo(x, y); return; }
            double phi = Math.toRadians(rotDeg);
            double dx2 = (x0 - x) / 2.0, dy2 = (y0 - y) / 2.0;
            double x1 = Math.cos(phi) * dx2 + Math.sin(phi) * dy2;
            double y1 = -Math.sin(phi) * dx2 + Math.cos(phi) * dy2;
            double rxs = rx * rx, rys = ry * ry;
            double lambda = (x1 * x1) / rxs + (y1 * y1) / rys;
            if (lambda > 1) { double s = Math.sqrt(lambda); rx *= s; ry *= s; rxs = rx * rx; rys = ry * ry; }
            double sign = (large == sweep) ? -1 : 1;
            double num = rxs * rys - rxs * y1 * y1 - rys * x1 * x1;
            double den = rxs * y1 * y1 + rys * x1 * x1;
            double co = sign * Math.sqrt(Math.max(0, num / den));
            double cxp = co * rx * y1 / ry, cyp = -co * ry * x1 / rx;
            double cx = Math.cos(phi) * cxp - Math.sin(phi) * cyp + (x0 + x) / 2.0;
            double cy = Math.sin(phi) * cxp + Math.cos(phi) * cyp + (y0 + y) / 2.0;
            double theta1 = Math.atan2((y1 - cyp) / ry, (x1 - cxp) / rx);
            double theta2 = Math.atan2((-y1 - cyp) / ry, (-x1 - cxp) / rx);
            double dTheta = theta2 - theta1;
            if (!sweep && dTheta > 0) dTheta -= 2 * Math.PI;
            else if (sweep && dTheta < 0) dTheta += 2 * Math.PI;
            int seg = Math.max(1, (int) Math.ceil(Math.abs(dTheta) / (Math.PI / 2)));
            double delta = dTheta / seg;
            double t1 = 4.0 / 3.0 * Math.tan(delta / 4);
            for (int k = 0; k < seg; k++) {
                double a1 = theta1 + k * delta, a2 = a1 + delta;
                double cos1 = Math.cos(a1), sin1 = Math.sin(a1), cos2 = Math.cos(a2), sin2 = Math.sin(a2);
                double p1x = cx + rx * cos1 * Math.cos(phi) - ry * sin1 * Math.sin(phi);
                double p1y = cy + rx * cos1 * Math.sin(phi) + ry * sin1 * Math.cos(phi);
                double p2x = cx + rx * cos2 * Math.cos(phi) - ry * sin2 * Math.sin(phi);
                double p2y = cy + rx * cos2 * Math.sin(phi) + ry * sin2 * Math.cos(phi);
                double d1x = -rx * sin1 * Math.cos(phi) - ry * cos1 * Math.sin(phi);
                double d1y = -rx * sin1 * Math.sin(phi) + ry * cos1 * Math.cos(phi);
                double d2x = -rx * sin2 * Math.cos(phi) - ry * cos2 * Math.sin(phi);
                double d2y = -rx * sin2 * Math.sin(phi) + ry * cos2 * Math.cos(phi);
                p.cubicTo((float) (p1x + t1 * d1x), (float) (p1y + t1 * d1y),
                        (float) (p2x - t1 * d2x), (float) (p2y - t1 * d2y),
                        (float) p2x, (float) p2y);
            }
        }
    }

    // ================= 原生行 =================

    /** 行内文字覆盖色：0 = 保留布局自带（主题感知），非 0 才强制覆盖 */
    private static int thC, thS;
    /** 自绘控件（图标 / 箭头 / 分组标题 / 对话框）用的主题色 */
    private static int thTitle = 0xFFE9E9E9, thSum = 0xFF9E9E9E;

    static View row(Context host, LayoutInflater inf, int layout,
                    String title, String summary, int arrowId) {
        View v;
        try {
            v = inf.inflate(layout, null);
        } catch (Throwable t) {
            MainHookLogger.log("event=row_inflate_fail " + layout + " " + t);
            return fallbackRow(host, title, summary);
        }
        try {
            TextView t = v.findViewById(android.R.id.title);
            TextView s = v.findViewById(android.R.id.summary);
            if (t == null || s == null) {
                // coui_preference_assignment_in_right 等布局并不使用 android.R.id.* 约定，
                // 按字号兜底定位，否则这些行会保留宿主原色，与其它行不一致。
                TextView[] pair = guessTitleSummary(v);
                if (t == null) t = pair[0];
                if (s == null) s = pair[1];
            }
            if (t != null) {
                t.setText(title);
                // 只在明确需要覆盖时才 setTextColor：主动 setTextColor(int) 会把宿主的
                // ColorStateList 压成固定色，按压态的高亮就没了。
                if (thC != 0) t.setTextColor(thC);
            }
            if (s != null) {
                if (summary == null || summary.isEmpty()) s.setVisibility(View.GONE);
                else s.setText(summary);
                if (thS != 0) s.setTextColor(thS);
            }
        } catch (Throwable ignore) {}
        if (arrowId != 0) {
            try {
                View a = v.findViewById(arrowId);
                if (a != null && a.getParent() instanceof ViewGroup) {
                    // COUIRoundImageView 无 src 时是实心方块，整颗换成自绘 chevron
                    ViewGroup par = (ViewGroup) a.getParent();
                    int idx = par.indexOfChild(a);
                    ViewGroup.LayoutParams lp = a.getLayoutParams();
                    par.removeViewAt(idx);
                    par.addView(new ChevronView(host, thSum, 1), idx, lp);
                }
            } catch (Throwable ignore) {}
        }
        // 行根保持宿主框架原生状态：
        //  - 不清背景：COUICardListSelectedItemLayout 构造时已把卡片底色（couiColorCardBackground，
        //    圆角路径，左右内缩 coui_preference_card_margin_horizontal=16dp）装进自己的
        //    COUIStateEffectDrawable（setBackground 被重写为 setViewBackground）。
        //  - 不去掉 1dp 竖线：开关行原生就带分隔线（coui_switch_with_divider_preference 布局内声明）。
        //  - 不归一化 padding：布局 XML 声明值（32dp/16dp、32dp/32dp）即原生真值。
        // 按压/悬停遮罩与卡片同路径（getLayoutPath），由 finishCard 里的
        // setPositionInGroup(HEAD/MIDDLE/TAIL/FULL) 驱动圆角与头尾补偿，全程零自绘。
        return v;
    }

    /** 行布局没有 android.R.id.title/summary 时，按字号兜底：最大字号=标题，次之=摘要 */
    private static TextView[] guessTitleSummary(View root) {
        final java.util.List<TextView> list = new java.util.ArrayList<>();
        walk(root, v -> {
            if (!(v instanceof TextView)) return;
            if (v.getClass().getName().contains("Switch")) return;
            if (v.getVisibility() == View.GONE) return;
            android.view.ViewParent p = v.getParent();
            while (p instanceof View) {
                if (((View) p).getId() == android.R.id.widget_frame) return;
                p = ((View) p).getParent();
            }
            list.add((TextView) v);
        });
        list.sort((x, y) -> Float.compare(y.getTextSize(), x.getTextSize()));
        TextView t = list.isEmpty() ? null : list.get(0);
        TextView s = null;
        for (int i = 1; i < list.size(); i++) {
            if (t != null && list.get(i).getTextSize() < t.getTextSize() - 0.5f) {
                s = list.get(i);
                break;
            }
        }
        return new TextView[]{t, s};
    }

    /** 开关行：coui_preference 行 + 把 coui_preference_widget_switch 塞进 widget_frame（与宿主 COUISwitchPreference 一致） */
    private static View makeSwitchRow(Context host, LayoutInflater inf, int rowLayout,
                                      int widgetLayout, String title, String summary) {
        View v = row(host, inf, rowLayout, title, summary);
        try {
            View frame = v.findViewById(android.R.id.widget_frame);
            if (frame instanceof ViewGroup && widgetLayout != 0) {
                inf.inflate(widgetLayout, (ViewGroup) frame, true);
            } else {
                MainHookLogger.log("event=sw_widget_fail frame=" + (frame == null ? "null" : frame.getClass().getSimpleName()) + " wl=" + widgetLayout);
            }
        } catch (Throwable t) {
            MainHookLogger.log("event=sw_widget_err " + t);
        }
        return v;
    }

    private static View row(Context host, LayoutInflater inf, int layout,
                            String title, String summary) {
        return row(host, inf, layout, title, summary, 0);
    }

    /** 「立即同步个人词库」行：点一下就把微信输入法词库并进小布 */
    private static View makeDictSyncRow(final Activity act, LayoutInflater inf, int layout, int arrow) {
        View v = row(act, inf, layout, "立即同步个人词库",
                "从微信输入法读取词条，补进小布的个人词库", arrow);
        v.setOnClickListener(x -> syncUserDictNow(act, null));
        return v;
    }

    /** 跑一次个人词库同步（微信输入法 → 小布） */
    private static void syncUserDictNow(final Activity act, final String hint) {
        if (hint != null) toast(act, hint);
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        WtClient.syncUserDictNow((ok, msg) -> h.post(() -> {
            if (act.isFinishing()) return;
            toast(act, msg);
        }));
    }

    /** 「立即同步常用语」行：点一下就跑一次双向同步 */
    private static View makeSyncRow(final Activity act, LayoutInflater inf, int layout, int arrow) {
        View v = row(act, inf, layout, "立即同步常用语", "拉取关联设备新增的常用语，并把本机新增的推送到组", arrow);
        v.setOnClickListener(x -> syncNow(act, null));
        return v;
    }

    /** 跑一次常用语双向同步，结果用 toast 反馈 */
    private static void syncNow(final Activity act, final String hintToast) {
        if (hintToast != null) toast(act, hintToast);
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        WtClient.syncHotWordsNow((ok, msg) -> h.post(() -> {
            if (act.isFinishing()) return;
            toast(act, msg);
        }));
    }

    /** 原生布局 inflate 失败时的兜底行 */
    private static View fallbackRow(Context c, String title, String summary) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c.getResources().getDisplayMetrics().density, 18);
        v.setPadding(p, p, p, p);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(17);
        v.addView(t);
        TextView s = new TextView(c);
        s.setText(summary);
        s.setTextSize(13);
        s.setAlpha(0.6f);
        v.addView(s);
        return v;
    }

    private static View findSwitch(View row) {
        try {
            View v = row.findViewById(android.R.id.checkbox);
            if (v != null) return v;
        } catch (Throwable ignore) {}
        // 宿主可能重映射 id，退化为按类名递归找 Switch
        final View[] hit = new View[1];
        walk(row, v -> {
            if (hit[0] != null) return;
            String n = v.getClass().getName();
            if (n.contains("Switch") || n.contains("CheckBox") || n.contains("Toggle")) hit[0] = v;
        });
        return hit[0];
    }

    private static void setSwitch(View row, boolean on) {
        View s = findSwitch(row);
        MainHookLogger.log("event=sw_set on=" + on + " view=" + (s == null ? "null" : s.getClass().getSimpleName()));
        if (s instanceof CompoundButton) {
            ((CompoundButton) s).setChecked(on);
        } else if (s != null) {
            try {
                s.getClass().getMethod("setChecked", boolean.class).invoke(s, on);
            } catch (Throwable ignore) {}
        }
    }

    /** 程序性 setSwitch 时抑制回调，避免失败回滚触发新一轮请求 */
    private static boolean SUPPRESS_SW;

    private static void setSwitchQuiet(View row, boolean on) {
        try {
            SUPPRESS_SW = true;
            setSwitch(row, on);
        } finally {
            SUPPRESS_SW = false;
        }
    }

    private static void bindSwitch(View row, boolean init, ToggleCB cb) {
        setSwitchQuiet(row, init);
        View s = findSwitch(row);
        if (s instanceof CompoundButton) {
            ((CompoundButton) s).setOnCheckedChangeListener((b, on) -> {
                if (SUPPRESS_SW) return;
                cb.run(on);
            });
        } else if (s != null) {
            try {
                for (java.lang.reflect.Method mm : s.getClass().getMethods()) {
                    if (mm.getName().equals("setOnCheckedChangeListener") && mm.getParameterCount() == 1) {
                        Class<?> lis = mm.getParameterTypes()[0];
                        mm.invoke(s, java.lang.reflect.Proxy.newProxyInstance(
                                s.getClass().getClassLoader(), new Class[]{lis}, (pr, m, args) -> {
                                    if (args != null && args.length > 1 && args[1] instanceof Boolean) {
                                        cb.run((Boolean) args[1]);
                                    }
                                    return null;
                                }));
                        break;
                    }
                }
            } catch (Throwable ignore) {}
        }
        // 点整行也能切换：直接 toggle()，OnCheckedChangeListener 会回调 cb（与原生 preference 行一致）
        row.setOnClickListener(v -> toggleSwitch(row));
    }

    interface ToggleCB { void run(boolean on); }

    // ================= 卡片容器 =================

    /**
     * 分组容器：仅作布局分组（原生卡片由每行行根 COUICardListSelectedItemLayout 自绘：
     * 底色 couiColorCardBackground + 圆角路径 + 左右内缩 16dp，组内首/中/尾行由
     * setPositionInGroup 决定上下圆角）。容器本身透明、无裁剪、无分隔线 —— 与原生卡片列表一致。
     */
    static LinearLayout card(Context c, int cardBg, float d) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    /** 行根是否可点：无监听器的行补一个空监听，只为拿到 clickable=true（按压反馈的前提，与原生 preference 行一致） */
    private static void ensureRowClickable(View row) {
        if (row.isClickable()) return;
        View sw = findSwitch(row);
        if (sw instanceof CompoundButton) {
            row.setOnClickListener(v -> ((CompoundButton) sw).toggle());
        } else {
            row.setOnClickListener(v -> { /* 无动作行：仅按压反馈 */ });
        }
    }

    /**
     * 完全复刻宿主 COUICardListHelper.setItemCardBackground + COUISwitchWithDividerPreference.onBindViewHolder：
     *  - setPositionInGroup(1=HEAD/2=MIDDLE/3=TAIL/4=FULL)：行根据此设置上下圆角、头尾 2dp 补偿 padding；
     *  - 开关行：给行根挂宿主自己的 COUISwitchTouchEventDelegate（把开关区的触摸代理给 COUISwitch
     *    并驱动行按压动画，与原生同一类），并令 main_layout（内层 ListSelectedItemLayout）点击也切换。
     */
    static void finishCard(LinearLayout card, float d, int cardBg) {
        int n = card.getChildCount();
        for (int i = 0; i < n; i++) {
            final View child = card.getChildAt(i);
            int pos = n == 1 ? 4 : (i == 0 ? 1 : (i == n - 1 ? 3 : 2));
            setRowCardPosition(child, pos);
            ensureRowClickable(child);
            View sw = findSwitch(child);
            if (sw != null) bindSwitchRowNative(child, sw);
        }
        // 行间分隔线放在位置计算之后插入（不影响 HEAD/MIDDLE/TAIL 判定）
        addDividers(card, d, n);
    }

    /**
     * 原生行间分隔线（实测原生主页 y=1232/1468/1704…）：
     *  颜色 (224,224,224) = #E0E0E0，高度 1px，
     *  水平跨度 x=128..1311 = 屏幕 32dp 内缩（卡片自绘边缘在 16dp，分隔线再内缩 16dp）。
     * 分隔线是分组容器的兄弟节点，不参与行的按压遮罩，与原生卡片列表一致。
     */
    private static void addDividers(LinearLayout card, float d, int rowCount) {
        if (rowCount < 2) return;
        Context c = card.getContext();
        int inset = dp(d, 32);
        for (int i = rowCount - 1; i >= 1; i--) {
            View line = new View(c);
            line.setBackgroundColor(dividerColor(c));
            line.setClickable(false);
            line.setFocusable(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, 1);
            lp.setMargins(inset, 0, inset, 0);
            card.addView(line, i, lp);
        }
    }

    /** 分隔线颜色：深色卡片白 12%，浅色卡片 #E0E0E0（原生实测 (224,224,224)） */
    private static int dividerColor(Context c) {
        boolean night = false;
        try {
            night = (c.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignore) {}
        return night ? 0x1FFFFFFF : 0xFFE0E0E0;
    }

    /** 反射调行根 setPositionInGroup（COUICardListSelectedItemLayout 声明，沿类层级找） */
    private static void setRowCardPosition(View row, int pos) {
        try {
            java.lang.reflect.Method m = null;
            for (Class<?> k = row.getClass(); k != null && m == null; k = k.getSuperclass()) {
                try { m = k.getDeclaredMethod("setPositionInGroup", int.class); } catch (Throwable ignore) {}
            }
            if (m != null) {
                m.setAccessible(true);
                m.invoke(row, pos);
                MainHookLogger.log("event=row_pos pos=" + pos + " cls=" + row.getClass().getSimpleName());
            } else {
                MainHookLogger.log("event=row_pos_nomethod cls=" + row.getClass().getName());
            }
        } catch (Throwable t) {
            MainHookLogger.log("event=row_pos_fail " + t);
        }
    }

    /** 开关行原生绑定：COUISwitchTouchEventDelegate 代理开关触摸 + main_layout 点击切换 */
    private static void bindSwitchRowNative(final View row, final View sw) {
        try {
            // 1) 宿主触摸代理：行根 setOnTouchListener，DOWN 落在开关区时转发给开关并驱动行按压动画
            ClassLoader cl = row.getContext().getClassLoader();
            Class<?> delegateCls = Class.forName(
                    "com.coui.appcompat.preference.COUISwitchTouchEventDelegate", true, cl);
            Class<?> switchCls = Class.forName(
                    "com.coui.appcompat.couiswitch.COUISwitch", true, cl);
            Class<?> listenerCls = null;
            for (Class<?> inner : delegateCls.getDeclaredClasses()) {
                if (inner.getName().endsWith("OnItemViewShareEventListener")) { listenerCls = inner; break; }
            }
            java.lang.reflect.Constructor<?> ctor =
                    delegateCls.getDeclaredConstructor(switchCls, View.class, listenerCls);
            ctor.setAccessible(true);
            ctor.newInstance(switchCls.cast(sw), row, null);
            MainHookLogger.log("event=sw_delegate_ok");
        } catch (Throwable t) {
            MainHookLogger.log("event=sw_delegate_fail " + t);
        }
        // 2) main_layout（内层 ListSelectedItemLayout，id=main_layout）点击也切换 —— 同 COUISwitchWithDividerPreference
        try {
            int mlId = idOf(row.getContext(), "main_layout", "id");
            View main = mlId != 0 ? row.findViewById(mlId) : findViewByClass(row, "ListSelectedItemLayout");
            if (main != null && main != row) {
                main.setOnClickListener(v -> toggleSwitch(row));
                MainHookLogger.log("event=sw_mainlayout_click cls=" + main.getClass().getSimpleName());
            }
        } catch (Throwable t) {
            MainHookLogger.log("event=sw_mainlayout_fail " + t);
        }
        // 3) 头尾补偿 padding 转移到 main_layout（原生 setMainLayoutToSetExtraPadding 语义）
        try {
            java.lang.reflect.Method m = null;
            for (Class<?> k = row.getClass(); k != null && m == null; k = k.getSuperclass()) {
                try { m = k.getDeclaredMethod("setMainLayoutToSetExtraPadding", View.class); } catch (Throwable ignore) {}
            }
            if (m != null) {
                int mlId = idOf(row.getContext(), "main_layout", "id");
                View main = mlId != 0 ? row.findViewById(mlId) : null;
                if (main != null) { m.setAccessible(true); m.invoke(row, main); }
            }
        } catch (Throwable ignore) {}
    }

    /** 切换行内开关（root/main_layout/switch_layout 点击共用） */
    private static void toggleSwitch(View row) {
        View sw = findSwitch(row);
        if (sw instanceof CompoundButton) {
            ((CompoundButton) sw).toggle();
        } else if (sw != null) {
            try {
                boolean cur = (Boolean) sw.getClass().getMethod("isChecked").invoke(sw);
                sw.getClass().getMethod("setChecked", boolean.class).invoke(sw, !cur);
            } catch (Throwable ignore) {}
        }
    }

    /** 读取宿主 dimen（按名字，找不到用默认 dp） */
    private static int hostDimenPx(Context c, String name, int defDp) {
        int id = idOf(c, name, "dimen");
        if (id == 0) {
            return (int) (defDp * c.getResources().getDisplayMetrics().density + 0.5f);
        }
        try {
            return c.getResources().getDimensionPixelSize(id);
        } catch (Throwable t) {
            return (int) (defDp * c.getResources().getDisplayMetrics().density + 0.5f);
        }
    }



    static LinearLayout.LayoutParams lp(float d, int topDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(MATCH, WRAP);
        l.topMargin = dp(d, topDp);
        return l;
    }

    // ================= 关联对话框 =================

    /** 匹配码数字颜色：取自微信输入法 APK 资源（#23C891），与其官方匹配码一致 */
    private static final int CODE_GREEN = 0xFF23C891;

    /**
     * 查看匹配码弹窗：COUI 风格底部弹窗（圆角取自宿主 coui_dialog_background_corner_radius），
     * 6 位匹配码分格显示，正在申请时轮询刷新。
     */
    private static void showCodeDialog(final Activity act) {
        if (WtClient.active == null) { toast(act, "同步引擎未运行"); return; }
        final float d = act.getResources().getDisplayMetrics().density;
        Theme th = sampleTheme(act);
        int radius = hostDimenPx(act, "coui_dialog_background_corner_radius", 24);

        // 容器
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(th.cardBg);
        root.setPadding(dp(d, 24), dp(d, 24), dp(d, 24), dp(d, 28));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(th.cardBg);
        bg.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        root.setBackground(bg);

        TextView title = new TextView(act);
        title.setText("本机匹配码");
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(th.titleC);
        root.addView(title);

        TextView sub = new TextView(act);
        sub.setText("匹配码 5 分钟内有效");
        sub.setTextSize(13);
        sub.setTextColor(th.summaryC);
        sub.setPadding(0, dp(d, 4), 0, dp(d, 20));
        root.addView(sub);

        // 6 位匹配码格子
        LinearLayout boxes = new LinearLayout(act);
        boxes.setOrientation(LinearLayout.HORIZONTAL);
        boxes.setGravity(android.view.Gravity.CENTER);
        int bw = dp(d, 46), bh = dp(d, 60);
        final TextView[] digits = new TextView[6];
        for (int i = 0; i < 6; i++) {
            FrameLayout box = new FrameLayout(act);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(bw, bh);
            if (i > 0) blp.leftMargin = dp(d, 8);
            GradientDrawable bbg = new GradientDrawable();
            bbg.setColor((th.light ? 0xFFF2F3F5 : 0xFF2A2A2A));
            bbg.setCornerRadius(dp(d, 12));
            box.setBackground(bbg);
            TextView tv = new TextView(act);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setTextSize(26);
            tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
            tv.setTextColor(CODE_GREEN);
            tv.setText("–");
            box.addView(tv, new FrameLayout.LayoutParams(MATCH, MATCH));
            boxes.addView(box, blp);
            digits[i] = tv;
        }
        root.addView(boxes);

        TextView tip = new TextView(act);
        tip.setText("在其它设备上打开「跨设备同步」，输入此匹配码即可关联。");
        tip.setTextSize(13);
        tip.setTextColor(th.summaryC);
        tip.setPadding(0, dp(d, 20), 0, 0);
        root.addView(tip);

        final android.app.Dialog dlg = new android.app.Dialog(act);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dlg.setContentView(root);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(0x00000000));
            dlg.getWindow().setGravity(android.view.Gravity.BOTTOM);
            dlg.getWindow().setLayout(MATCH, WRAP);
            dlg.getWindow().setDimAmount(0.4f);
        }
        dlg.setCanceledOnTouchOutside(true);
        dlg.show();

        // 填码：有码直接显示，无码先申请再轮询刷新
        String cur = WtClient.active.issueCode();
        if (cur == null) {
            WtClient.active.startIssue();
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            final int[] tries = {0};
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                if (!dlg.isShowing() || tries[0]++ > 25) return;
                String c2 = WtClient.active.issueCode();
                if (c2 != null) {
                    fillDigits(digits, c2);
                } else {
                    h.postDelayed(poll[0], 800);
                }
            };
            h.postDelayed(poll[0], 800);
        } else {
            fillDigits(digits, cur);
        }
    }

    private static void fillDigits(TextView[] digits, String code) {
        String s = code == null ? "" : code.trim();
        for (int i = 0; i < digits.length; i++) {
            digits[i].setText(i < s.length() ? String.valueOf(s.charAt(i)) : "–");
        }
    }

    /**
     * 「关联设备」弹窗：完全用宿主原生组件
     *   com.coui.appcompat.dialog.COUIAlertDialogBuilder（宿主所有设置弹窗都用它，
     *   自带 COUI 圆角卡片、标题排版、按钮栏、毛玻璃/遮罩与进出动画）
     * + com.coui.appcompat.edittext.COUIInputView（COUI 原生输入框：标题 + 高亮下划线 + 清除按钮）
     * 这样弹窗外观与宿主其它对话框完全一致，而不是 Material 默认 AlertDialog。
     * 宿主类不可用时回退到旧实现，保证功能不丢。
     */
    private static void showJoinDialog(final Activity act) {
        final float d = act.getResources().getDisplayMetrics().density;
        try {
            ClassLoader cl = act.getClassLoader();

            // ---- 原生输入框 ----
            Class<?> inputCls = Class.forName(
                    "com.coui.appcompat.edittext.COUIInputView", true, cl);
            final Object input = inputCls.getConstructor(Context.class).newInstance(act);
            try { inputCls.getMethod("setTitle", CharSequence.class).invoke(input, "关联码"); } catch (Throwable ignore) {}
            try { inputCls.getMethod("setHint", CharSequence.class).invoke(input, "6 位数字"); } catch (Throwable ignore) {}
            try { inputCls.getMethod("setMaxCount", int.class).invoke(input, 6); } catch (Throwable ignore) {}
            final android.widget.EditText et =
                    (android.widget.EditText) inputCls.getMethod("getEditText").invoke(input);
            et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            et.setSingleLine(true);
            try {
                et.setFilters(new android.text.InputFilter[]{
                        new android.text.InputFilter.LengthFilter(6)});
            } catch (Throwable ignore) {}

            LinearLayout wrap = new LinearLayout(act);
            wrap.setOrientation(LinearLayout.VERTICAL);
            int hp = dp(d, 24);
            wrap.setPadding(hp, dp(d, 12), hp, 0);
            wrap.addView((View) input, new LinearLayout.LayoutParams(MATCH, dp(d, 96)));

            // ---- 原生对话框 ----
            Class<?> bCls = Class.forName(
                    "com.coui.appcompat.dialog.COUIAlertDialogBuilder", true, cl);
            Object b = bCls.getConstructor(Context.class).newInstance(act);
            bCls.getMethod("setTitle", CharSequence.class).invoke(b, "关联设备");
            bCls.getMethod("setView", View.class).invoke(b, wrap);
            final android.content.DialogInterface.OnClickListener pos = (di, w) -> {
                String code = et.getText().toString().trim();
                if (code.length() != 6) {
                    toast(act, "请输入 6 位关联码");
                    return;
                }
                if (WtClient.active != null) {
                    // 结果（成功/匹配码错误/超时）回到主线程提示，避免「点完没下文」
                    WtClient.joinCb = (ok, msg) -> new android.os.Handler(
                            android.os.Looper.getMainLooper()).post(() -> toast(act, msg));
                    WtClient.active.startJoin(code);
                    toast(act, "已提交，等待对方确认");
                }
            };
            bCls.getMethod("setPositiveButton", CharSequence.class,
                    android.content.DialogInterface.OnClickListener.class).invoke(b, "关联", pos);
            bCls.getMethod("setNegativeButton", CharSequence.class,
                    android.content.DialogInterface.OnClickListener.class).invoke(b, "取消", null);
            Object dlg = bCls.getMethod("show").invoke(b);
            MainHookLogger.log("event=join_dialog_native ok=" + (dlg != null));
            // 弹窗一出现就聚焦输入框（COUI 输入框需要焦点才展开下划线高亮）
            try {
                final android.app.Dialog d0 = (android.app.Dialog) dlg;
                et.post(() -> {
                    try {
                        et.requestFocus();
                        android.view.inputmethod.InputMethodManager imm =
                                (android.view.inputmethod.InputMethodManager)
                                        act.getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showSoftInput(et, 0);
                    } catch (Throwable ignore) {}
                });
                d0.setCanceledOnTouchOutside(true);
            } catch (Throwable ignore) {}
            return;
        } catch (Throwable t) {
            MainHookLogger.log("event=join_dialog_native_fail " + t);
        }
        // ---- 回退：老实现 ----
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(d, 16);
        box.setPadding(p, dp(d, 4), p, 0);
        final android.widget.EditText et = new android.widget.EditText(act);
        et.setSingleLine(true);
        et.setHint("输入另一台设备显示的 6 位关联码");
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(MATCH, dp(d, 48));
        elp.topMargin = dp(d, 8);
        box.addView(et, elp);
        new android.app.AlertDialog.Builder(act)
                .setTitle("关联设备")
                .setMessage("在平板或电脑的微信输入法里生成关联码后输入此处")
                .setView(box)
                .setPositiveButton("关联", (di, w) -> {
                    String code = et.getText().toString().trim();
                    if (code.length() != 6) { toast(act, "请输入 6 位关联码"); return; }
                    if (WtClient.active != null) WtClient.active.startJoin(code);
                    toast(act, "已提交，等待对方确认");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================= 工具 =================

    static int idOf(Context c, String name, String type) {
        try {
            return c.getResources().getIdentifier(name, type, "com.oplus.keyboard");
        } catch (Throwable t) { return 0; }
    }

    static int dp(float d, float v) { return (int) (v * d + 0.5f); }

    private static int statusBarHeight(Context c) {
        try {
            int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
            return id != 0 ? c.getResources().getDimensionPixelSize(id) : 0;
        } catch (Throwable t) { return 0; }
    }

    /** 自绘箭头 chevron：dir=1 向右（行内），dir=-1 向左（返回钮） */
    static final class ChevronView extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int dir;
        ChevronView(Context c, int color, int dir) {
            super(c);
            this.dir = dir;
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            p.setColor(color);
            p.setAlpha(150);
        }
        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            // dir == 0：原生返回样式的细箭头「←」；dir = ±1：右/左 chevron
            if (dir == 0) {
                p.setStrokeWidth(Math.max(2f, Math.min(w, h) * 0.055f));
                float cy = h * 0.5f;
                float x1 = w * 0.28f, x2 = w * 0.74f, dy = w * 0.17f;
                android.graphics.Path pa = new android.graphics.Path();
                pa.moveTo(x2, cy);
                pa.lineTo(x1, cy);
                pa.moveTo(x1 + dy, cy - dy);
                pa.lineTo(x1, cy);
                pa.lineTo(x1 + dy, cy + dy);
                c.drawPath(pa, p);
                return;
            }
            float s = Math.min(w, h) * 0.30f;
            p.setStrokeWidth(Math.max(2f, Math.min(w, h) * 0.11f));
            float cx = w * 0.5f, cy = h * 0.5f;
            float dx = dir * s;
            android.graphics.Path pa = new android.graphics.Path();
            pa.moveTo(cx - dx * 0.55f, cy - s);
            pa.lineTo(cx + dx * 0.45f, cy);
            pa.lineTo(cx - dx * 0.55f, cy + s);
            c.drawPath(pa, p);
        }
    }

    /** 圆形返回钮：透明圆底 + 向左 chevron + 圆形 ripple 触摸反馈（仿 COUIToolbar navigationIcon） */
    private static View roundBackButton(Activity act, int color, float d) {
        int sz = dp(d, 40);
        FrameLayout btn = new FrameLayout(act);
        btn.setClickable(true);
        btn.setFocusable(true);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        RippleDrawable rd = new RippleDrawable(
                ColorStateList.valueOf((color & 0x00FFFFFF) | 0x40000000), null, mask);
        btn.setBackground(rd);
        ChevronView ch = new ChevronView(act, color, -1);
        int inset = dp(d, 10);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(sz - inset * 2, sz - inset * 2);
        clp.gravity = android.view.Gravity.CENTER;
        btn.addView(ch, clp);
        btn.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        return btn;
    }


    static void toast(Context c, String s) {
        android.widget.Toast.makeText(c, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    static final class Prefs {
        private final android.content.SharedPreferences sp;
        Prefs(Context c) { sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE); }
        boolean clip() { return sp.getBoolean(K_CLIP, true); }
        void setClip(boolean v) { sp.edit().putBoolean(K_CLIP, v).apply(); }
    }

    private SyncPage() {}
}
