package com.cxhuyo.wetsync;

import java.lang.reflect.Method;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * 设置页原生菜单注入：挂 settings.base.b.onViewCreated（所有设置页共用基类），
 * 若页面是 androidx PreferenceFragmentCompat，则 addPreference 一条原生菜单。
 */
public final class XiaobuSettingsHook {

    private static volatile ClassLoader HOST_CL;
    /**
     * 已注入过的页面，key = 页面类型 + PreferenceScreen 的身份哈希。
     * 不能用 screen 对象本身：宿主 PreferenceScreen 的 equals 会让不同页面的实例互相相等，
     * 结果「词库设置」页被判定成「首页」而静默跳过（实测 menu_skip_dup）。
     */
    private static final java.util.Set<String> INJECTED_SCREENS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** 兼容旧调用（剪贴板页日志） */
    public static void install(io.github.libxposed.api.XposedInterface xp, ClassLoader cl) {
        MainHookLogger.log("event=settings_hook_ok");
        try {
            hookPreferenceBind(xp, cl);
        } catch (Throwable t) {
            MainHookLogger.log("event=bind_hook_fail " + t);
        }
    }

    /** 注入行的 key，用于在绑定回调里识别「我们自己那一行」 */
    static final String ENTRY_KEY = "cxhuyo_wtsync_entry";
    /** 「词库设置」二级页里那一行的 key（与首页那行区分，避免点击回调串页） */
    static final String ENTRY_KEY_DICT = "cxhuyo_wtsync_entry_dict";

    /**
     * 行绑定兜底点击：
     * 宿主列表 itemView 的 OnClickListener 由 androidx 在 onBindViewHolder 里设置，
     * 但 COUIPreference 重写绑定流程后，某些时机（首帧重建 / 复用 / 精确点击代理）
     * 会让 performClick 走不到我们挂的 OnPreferenceClickListener —— 表现就是
     * 「刚进设置页时前几次点击没反应」。
     * 这里 hook 宿主的 onBindViewHolder，每次绑定后把 itemView 的点击直接指向开页函数，
     * 不依赖框架分发；命中同一行时 show() 自带去重（page_skip_open）。
     */
    private static void hookPreferenceBind(io.github.libxposed.api.XposedInterface xp, ClassLoader cl) throws Exception {
        Class<?> prefCls = cl.loadClass("com.coui.appcompat.preference.COUIPreference");
        for (java.lang.reflect.Method m : prefCls.getDeclaredMethods()) {
            if (!m.getName().equals("onBindViewHolder") || m.getParameterCount() != 1) continue;
            xp.hook(m).intercept(chain -> {
                Object r = chain.proceed();
                try {
                    Object pref = chain.getThisObject();
                    if (ENTRY_KEY.equals(keyOf(pref))) {
                        android.view.View item = itemViewOf(chain.getArg(0));
                        if (item != null) {
                            item.setOnClickListener(v -> openSyncPage());
                            MainHookLogger.log("event=bind_click_attached");
                        }
                    }
                } catch (Throwable t) {
                    MainHookLogger.log("event=bind_click_err " + t);
                }
                return r;
            });
            MainHookLogger.log("event=bind_hook_ok " + m);
            break;
        }
    }

    private static String keyOf(Object pref) {
        try {
            java.lang.reflect.Method g = pref.getClass().getMethod("getKey");
            Object k = g.invoke(pref);
            return k == null ? null : String.valueOf(k);
        } catch (Throwable t) { return null; }
    }

    private static android.view.View itemViewOf(Object holder) {
        if (holder == null) return null;
        try {
            java.lang.reflect.Field f = holder.getClass().getField("itemView");
            Object v = f.get(holder);
            if (v instanceof android.view.View) return (android.view.View) v;
        } catch (Throwable ignore) {}
        try {
            java.lang.reflect.Method m = holder.getClass().getMethod("itemView");
            Object v = m.invoke(holder);
            if (v instanceof android.view.View) return (android.view.View) v;
        } catch (Throwable ignore) {}
        return null;
    }

    /**
     * 在 PreferenceScreen 构建完成的当口注入菜单，随原生列表首帧出现（无 2.5s 延迟）。
     * 宿主用 COUI 自研 preference 框架，androidx.preference.PreferenceFragmentCompat 不存在，
     * 因此沿 SettingsHomeFragment 的类层级向上，把所有声明了 onCreatePreferences(Bundle,String)
     * 的方法都 hook 一遍；同时兜底 hook onCreate/onViewCreated/onResume。
     */
    private static void hookOnCreatePreferences(io.github.libxposed.api.XposedInterface xp, ClassLoader cl) {
        java.util.List<String> cands = new java.util.ArrayList<>();
        // 已知的设置页 Fragment 及其可能的父类名。
        // 注意必须把「词库设置」页自己也列进来：它在自己的类里重写了 onCreatePreferences，
        // 只 hook 父类拦不到子类重写（子类不调 super 就不会触发）。
        String[] roots = {
                "com.oplus.keyboard.settings.SettingsHomeFragment",
                "com.oplus.keyboard.settings.DictionarySettingsFragment",
                "com.coui.appcompat.preference.COUIPreferenceFragment",
                "com.coui.appcompat.preference.COUIPreferenceListFragment",
        };
        for (String r : roots) {
            try {
                Class<?> c = cl.loadClass(r);
                for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                    for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                        if (m.getName().equals("onCreatePreferences") && m.getParameterCount() == 2
                                && !cands.contains(k.getName())) {
                            cands.add(k.getName());
                        }
                    }
                }
            } catch (Throwable ignore) {}
        }
        if (cands.isEmpty()) {
            // 退而求其次：hook 已知 Fragment 自身的 onCreate / onViewCreated
            try {
                Class<?> f = cl.loadClass("com.oplus.keyboard.settings.SettingsHomeFragment");
                for (String nm : new String[]{"onCreate", "onViewCreated", "onResume"}) {
                    for (java.lang.reflect.Method m : f.getDeclaredMethods()) {
                        if (m.getName().equals(nm)) {
                            xp.hook(m).intercept(chain -> {
                                Object r = chain.proceed();
                                injectNow(chain.getThisObject());
                                return r;
                            });
                            MainHookLogger.log("event=fallback_hook " + nm);
                        }
                    }
                }
            } catch (Throwable t) {
                MainHookLogger.log("event=fallback_hook_fail " + t);
            }
            return;
        }
        for (String cn : cands) {
            try {
                Class<?> k = cl.loadClass(cn);
                for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                    if (!m.getName().equals("onCreatePreferences") || m.getParameterCount() != 2) continue;
                    xp.hook(m).intercept(chain -> {
                        Object r = chain.proceed();
                        injectNow(chain.getThisObject());
                        return r;
                    });
                    MainHookLogger.log("event=ocp_hook_ok " + cn);
                }
            } catch (Throwable t) {
                MainHookLogger.log("event=ocp_hook_one_fail " + cn + " " + t);
            }
        }
    }

    /**
     * 立刻尝试注入。
     * 不在 Fragment 维度去重：宿主复用同一个 Fragment 实例时会重建 PreferenceScreen
     * （返回上一页再进来就会重建），若按 Fragment 记忆「已注入」就会永久漏掉新屏幕。
     * 真正的去重由 INJECTED_SCREENS（页面类型 + screen 身份）负责。
     */
    private static void injectNow(Object frag) {
        try {
            if (frag == null) return;
            tryInject(frag, frag.getClass().getName());
        } catch (Throwable t) {
            MainHookLogger.log("event=inject_now_err " + t);
        }
    }

    public static void installMainOverlay(io.github.libxposed.api.XposedInterface xp, ClassLoader cl) {
        HOST_CL = cl;
        try {
            Class<?> act = cl.loadClass("com.oplus.keyboard.settings.SettingsActivity");
            try {
                Method onCreate = act.getDeclaredMethod("onCreate", android.os.Bundle.class);
                xp.hook(onCreate).intercept(chain -> {
                    Object r = chain.proceed();
                    try {
                        android.app.Activity a = (android.app.Activity) chain.getThisObject();
                        MainHook.ensureEngine(a);
                    } catch (Throwable t) { MainHookLogger.log("event=oncreate_engine_err " + t); }
                    return r;
                });
                MainHookLogger.log("event=oncreate_hook_ok");
            } catch (Throwable t) { MainHookLogger.log("event=oncreate_hook_fail " + t); }
            // 关键：在 Fragment 构建 PreferenceScreen 的当口就注入，菜单随原生列表一起首帧出现。
            // 宿主用的是 COUI 自研 preference 框架（非 androidx.preference），
            // 因此沿 SettingsHomeFragment 的类层级向上找所有声明了 onCreatePreferences 的方法逐个 hook。
            try {
                hookOnCreatePreferences(xp, cl);
            } catch (Throwable t) { MainHookLogger.log("event=ocp_hook_fail " + t); }
            Method onResume = act.getDeclaredMethod("onResume");
                xp.hook(onResume).intercept(chain -> {
                    Object ret = chain.proceed();
                    final android.app.Activity a = (android.app.Activity) chain.getThisObject();
                    MainHook.lastActivity = a;
                    // 每次回到前台启动行点击看护循环，避免复用/重建后丢失
                    startRowClickWatch(a);
                    try { MainHook.ensureEngine(a); } catch (Throwable t) { MainHookLogger.log("event=engine_call_err " + t); }
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try { MainHook.ensureEngine(a); } catch (Throwable ignore) {}
                    try {
                        MainHook.ensureEngine(a);
                        int id = a.getResources().getIdentifier("fragment_container", "id", a.getPackageName());
                        if (id == 0) { MainHookLogger.log("event=no_container_id"); return; }
                        Object fm = a.getClass().getMethod("getSupportFragmentManager").invoke(a);
                        Method find = null;
                        for (Method mm : fm.getClass().getMethods()) {
                            if (mm.getParameterCount() == 1 && mm.getParameterTypes()[0] == int.class
                                    && mm.getReturnType().getName().contains("Fragment")) {
                                MainHookLogger.log("event=findcand " + mm.getName());
                                find = mm; break;
                            }
                        }
                        if (find == null) { MainHookLogger.log("event=no_findfrag"); return; }
                        Object navHost = find.invoke(fm, id);
                        if (navHost == null) { MainHookLogger.log("event=navhost_null"); return; }
                        Method cfmM = null;
                        for (Method mm : navHost.getClass().getMethods()) {
                            if (mm.getParameterCount() == 0
                                    && mm.getReturnType().getName().contains("FragmentManager")) { cfmM = mm; break; }
                        }
                        if (cfmM == null) { MainHookLogger.log("event=no_childfm"); return; }
                        Object cfm = cfmM.invoke(navHost);
                        MainHookLogger.log("event=childfm " + cfmM.getName());
                        Method getFrags = null;
                        for (Method mm : cfm.getClass().getMethods()) {
                            if (mm.getParameterCount() == 0
                                    && java.util.List.class.isAssignableFrom(mm.getReturnType())) { getFrags = mm; break; }
                        }
                        if (getFrags == null) { MainHookLogger.log("event=no_getfrags"); return; }
                        Object frs = getFrags.invoke(cfm);
                        for (Object f : (java.util.List<?>) frs) {
                            if (f == null) continue;
                            String cn = f.getClass().getName();
                            MainHookLogger.log("event=dest_frag " + cn);
                            tryInject(f, cn);
                        }
                    } catch (Throwable t) { MainHookLogger.log("event=nav_err " + t); }
                }, 2500);
                return ret;
            });
            // 宿主已无 onBackPressed（实测 NoSuchMethodException，API34 且未启用预测性返回），
            // 返回键走普通 KEYCODE_BACK → 在框架 Activity.dispatchKeyEvent 上拦截，
            // 框架类永不混淆，且用 this 判定限定到 SettingsActivity。
            try {
                Method dk = android.app.Activity.class.getDeclaredMethod(
                        "dispatchKeyEvent", android.view.KeyEvent.class);
                final String actName = act.getName();
                xp.hook(dk).intercept(chain -> {
                    try {
                        Object thiz = chain.getThisObject();
                        if ((SyncPage.isOpen() || UserDictPage.isOpen()) && thiz instanceof android.app.Activity
                                && actName.equals(thiz.getClass().getName())) {
                            android.view.KeyEvent ev = (android.view.KeyEvent) chain.getArgs().get(0);
                            if (ev != null && ev.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK
                                    && ev.getAction() == android.view.KeyEvent.ACTION_UP) {
                                // 哪个页开着就关哪个；返回 true 表示已消费，不再往下传
                                boolean closed = UserDictPage.isOpen() ? UserDictPage.close() : SyncPage.close();
                                if (closed) return Boolean.TRUE;
                            }
                        }
                    } catch (Throwable t) { MainHookLogger.log("event=backkey_err " + t); }
                    return chain.proceed();
                });
                MainHookLogger.log("event=backkey_hook_ok");
            } catch (Throwable t) { MainHookLogger.log("event=backkey_hook_fail " + t); }
            MainHookLogger.log("event=navhook_ok");
        } catch (Throwable t) { MainHookLogger.log("event=navhook_fail " + t); }
    }

    /** 从 SettingsActivity 找到 NavHostFragment 下的所有子 Fragment */
    public static java.util.List<Object> destFragments(android.app.Activity a) throws Exception {
        java.util.List<Object> out = new java.util.ArrayList<>();
        int id = a.getResources().getIdentifier("fragment_container", "id", a.getPackageName());
        if (id == 0) return out;
        Object fm = a.getClass().getMethod("getSupportFragmentManager").invoke(a);
        Method find = null;
        for (Method mm : fm.getClass().getMethods()) {
            if (mm.getParameterCount() == 1 && mm.getParameterTypes()[0] == int.class
                    && mm.getReturnType().getName().contains("Fragment")) { find = mm; break; }
        }
        if (find == null) return out;
        Object navHost = find.invoke(fm, id);
        if (navHost == null) return out;
        Method cfmM = null;
        for (Method mm : navHost.getClass().getMethods()) {
            if (mm.getParameterCount() == 0
                    && mm.getReturnType().getName().contains("FragmentManager")) { cfmM = mm; break; }
        }
        if (cfmM == null) return out;
        Object cfm = cfmM.invoke(navHost);
        Method getFrags = null;
        for (Method mm : cfm.getClass().getMethods()) {
            if (mm.getParameterCount() == 0
                    && java.util.List.class.isAssignableFrom(mm.getReturnType())) { getFrags = mm; break; }
        }
        if (getFrags == null) return out;
        Object frs = getFrags.invoke(cfm);
        if (frs != null) for (Object f : (java.util.List<?>) frs) if (f != null) out.add(f);
        return out;
    }

    /** 诊断入口：抓当前设置页所有 Fragment 的原生样式（带重试，等 NavHost 就绪） */
    static void inspectCurrent(android.app.Activity a) {
        new Thread(() -> {
            for (int attempt = 0; attempt < 8; attempt++) {
                try {
                    java.util.List<Object> fs = destFragments(a);
                    MainHookLogger.log("event=insp_frags n=" + fs.size() + " try=" + attempt);
                    if (!fs.isEmpty()) {
                        for (Object f : fs) inspectScreen(f);
                        return;
                    }
                } catch (Throwable t) { MainHookLogger.log("event=insp_err " + t); }
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
        }, "WtInspect").start();
    }

    /** 诊断：枚举屏幕内 Preference 类名 + 遍历视图树抓背景圆角/箭头，用于 1:1 复刻原生样式 */
    public static void inspectScreen(Object frag) {
        new Thread(() -> {
            try {
                Method gps = findMethod(frag.getClass(), "getPreferenceScreen", 0);
                Object screen = gps.invoke(frag);
                Method cnt = findMethod(screen.getClass(), "getPreferenceCount", 0);
                Method getAt = findMethod(screen.getClass(), "getPreference", 1);
                int n = ((Number) cnt.invoke(screen)).intValue();
                MainHookLogger.log("event=insp_prefs n=" + n);
                for (int i = 0; i < n; i++) {
                    Object it = getAt.invoke(screen, i);
                    if (it == null) continue;
                    String t = titleOf(it);
                    boolean isGroup = false;
                    for (Class<?> k = it.getClass(); k != null; k = k.getSuperclass()) {
                        if ("androidx.preference.PreferenceGroup".equals(k.getName())) { isGroup = true; break; }
                    }
                    MainHookLogger.log("event=insp_pref cls=" + it.getClass().getName()
                            + " simple=" + it.getClass().getSimpleName() + " title=" + t
                            + " group=" + isGroup + " layout=" + layoutResOf(it));
                    if (isGroup) {
                        Method cc = findMethod(it.getClass(), "getPreferenceCount", 0);
                        Method cg = findMethod(it.getClass(), "getPreference", 1);
                        if (cc != null && cg != null) {
                            int m = ((Number) cc.invoke(it)).intValue();
                            for (int j = 0; j < m && j < 16; j++) {
                                Object c = cg.invoke(it, j);
                                if (c == null) continue;
                                MainHookLogger.log("event=insp_child cls=" + c.getClass().getName()
                                        + " simple=" + c.getClass().getSimpleName()
                                        + " title=" + titleOf(c) + " layout=" + layoutResOf(c));
                            }
                        } else {
                            MainHookLogger.log("event=insp_child_nomethod");
                        }
                    }
                }
                // 视图树
                android.view.View root = null;
                try { root = (android.view.View) frag.getClass().getMethod("getView").invoke(frag); }
                catch (Throwable ignore) {}
                if (root == null) { MainHookLogger.log("event=insp_noroot"); return; }
                final int[] seen = {0};
                walk(root, v -> {
                    if (seen[0]++ > 260) return;
                    android.graphics.drawable.Drawable bg = v.getBackground();
                    if (bg == null) return;
                    StringBuilder sb = new StringBuilder();
                    sb.append(v.getClass().getSimpleName()).append(" id=")
                      .append(safeIdName(v)).append(" bg=").append(bg.getClass().getSimpleName());
                    if (bg instanceof android.graphics.drawable.GradientDrawable) {
                        android.graphics.drawable.GradientDrawable g =
                                (android.graphics.drawable.GradientDrawable) bg;
                        float[] r = new float[8];
                        try { r = g.getCornerRadii(); } catch (Throwable ignore) {}
                        float rr = g.getCornerRadius();
                        sb.append(" radius=").append(rr).append(" radii=")
                          .append(java.util.Arrays.toString(r));
                        try {
                            int c = g.getColor() != null ? g.getColor().getDefaultColor() : 0;
                            sb.append(" color=").append(String.format("%08X", c));
                        } catch (Throwable ignore) {}
                        try {
                            android.content.res.ColorStateList cs = g.getColor();
                            if (cs != null) sb.append(" csl=").append(String.format("%08X", cs.getDefaultColor()));
                        } catch (Throwable ignore) {}
                    }
                    if (bg instanceof android.graphics.drawable.RippleDrawable) {
                        sb.append(" ripple");
                        try {
                            android.graphics.drawable.Drawable d =
                                    ((android.graphics.drawable.RippleDrawable) bg).getDrawable(0);
                            if (d instanceof android.graphics.drawable.GradientDrawable) {
                                android.graphics.drawable.GradientDrawable g =
                                        (android.graphics.drawable.GradientDrawable) d;
                                sb.append(" inner=").append(g.getClass().getSimpleName())
                                  .append(" radius=").append(g.getCornerRadius())
                                  .append(" color=").append(String.format("%08X",
                                          g.getColor() == null ? 0 : g.getColor().getDefaultColor()));
                            } else if (d != null) {
                                sb.append(" inner=").append(d.getClass().getSimpleName());
                            }
                        } catch (Throwable ignore) {}
                    }
                    MainHookLogger.log("event=insp_view " + sb);
                });
                // 找箭头 ImageView
                walk(root, v -> {
                    if (!(v instanceof android.widget.ImageView)) return;
                    android.graphics.drawable.Drawable d =
                            ((android.widget.ImageView) v).getDrawable();
                    if (d == null) return;
                    MainHookLogger.log("event=insp_arrow cls=" + v.getClass().getName()
                            + " drawable=" + d.getClass().getName()
                            + " intrinsic=" + d.getIntrinsicWidth() + "x" + d.getIntrinsicHeight()
                            + " parent=" + (v.getParent() == null ? "null"
                                    : v.getParent().getClass().getSimpleName()));
                });
                // 文本外观
                walk(root, v -> {
                    if (!(v instanceof android.widget.TextView)) return;
                    android.widget.TextView tv = (android.widget.TextView) v;
                    CharSequence t = tv.getText();
                    if (t == null || t.length() == 0) return;
                    MainHookLogger.log("event=insp_text [" + t + "] size=" + tv.getTextSize()
                            + " color=" + String.format("%08X", tv.getCurrentTextColor())
                            + " cls=" + tv.getClass().getSimpleName()
                            + " padding=" + tv.getPaddingLeft() + "," + tv.getPaddingTop()
                            + "," + tv.getPaddingRight() + "," + tv.getPaddingBottom());
                });
            } catch (Throwable t) {
                MainHookLogger.log("event=insp_err " + t);
            }
        }, "WtInspect").start();
    }

    private interface ViewVisitor { void visit(android.view.View v); }

    /** 0 参 getter（getTitle 等） */
    private static Method findGetter(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private static String titleOf(Object pref) {
        try {
            Method m = findGetter(pref.getClass(), "getTitle");
            if (m == null) return null;
            Object o = m.invoke(pref);
            return o == null ? null : o.toString();
        } catch (Throwable t) { return null; }
    }

    private static void walk(android.view.View v, ViewVisitor vis) {
        if (v == null) return;
        vis.visit(v);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), vis);
        }
    }

    private static String safeIdName(android.view.View v) {
        try {
            if (v.getId() == android.view.View.NO_ID) return "-";
            return v.getResources().getResourceName(v.getId());
        } catch (Throwable t) { return "?" + v.getId(); }
    }

    /** Preference 的 layoutResId（androidx Preference#getLayoutResource） */
    private static String layoutResOf(Object pref) {
        try {
            Method m = findMethod(pref.getClass(), "getLayoutResource", 0);
            if (m == null) return "?";
            Object r = m.invoke(pref);
            int id = r instanceof Integer ? (Integer) r : 0;
            if (id == 0) return "0";
            try { return pref.getClass().getMethod("getContext").invoke(pref)
                    .getClass().getName() + ":" + id; } catch (Throwable ignore) {}
            return "#" + id;
        } catch (Throwable t) { return "err"; }
    }

    /** 零名字依赖：构造原生 Preference 并插入第一个分组 */
    private static void tryInject(Object frag, String cn) throws Exception {
        // 注入两处：
        //  · 「输入法设置」首页  → 跨设备同步（匹配码、剪贴板、常用语）
        //  · 「词库设置」二级页  → 个人词库（查看 / 编辑）
        // 其余设置子页（输入设置、按键效果…）不注入，否则每页都会多出一行。
        boolean home = cn != null && cn.contains("SettingsHomeFragment");
        boolean dict = cn != null && cn.contains("DictionarySettingsFragment");
        if (!home && !dict) {
            MainHookLogger.log("event=inject_skip_cls " + cn);
            return;
        }
        final boolean isDict = dict;
        final String rowTitle = isDict ? "个人词库" : "跨设备同步";
        final String rowSummary = isDict ? "查看和编辑已学的词条" : "匹配码关联设备、剪贴板同步";
        final String rowKey = isDict ? ENTRY_KEY_DICT : ENTRY_KEY;
        MainHookLogger.log("event=inject_cls cn=" + cn + " isDict=" + isDict);
        Method gps = findMethod(frag.getClass(), "getPreferenceScreen", 0);
        if (gps == null) { MainHookLogger.log("event=no_prefscreen " + cn); return; }
        Object screen = gps.invoke(frag);
        if (screen == null) { MainHookLogger.log("event=screen_null " + cn); return; }
        // 按「页面类型 + screen 身份」去重：同一页只注入一次，不同页各自独立
        String sid = (isDict ? "dict:" : "home:") + System.identityHashCode(screen);
        if (INJECTED_SCREENS.contains(sid)) {
            MainHookLogger.log("event=menu_skip_dup " + sid);
            return;
        }
        INJECTED_SCREENS.add(sid);
        Method add = findMethod(screen.getClass(), "addPreference", 1);
        if (add == null) { MainHookLogger.log("event=no_addpref " + cn); return; }
        Class<?> prefCls = add.getParameterTypes()[0];
        // 尝试克隆一个真实行的类（带箭头/原生样式）
        try {
            Object cat0 = null;
            Method cnt0 = findMethod(screen.getClass(), "getPreferenceCount", 0);
            Method get0 = findMethod(screen.getClass(), "getPreference", 1);
            Class<?> grp0 = add.getDeclaringClass();
            if (cnt0 != null && get0 != null) {
                int n0 = ((Number) cnt0.invoke(screen)).intValue();
                for (int i0 = 0; i0 < n0 && cat0 == null; i0++) {
                    Object c0 = get0.invoke(screen, i0);
                    if (c0 != null && grp0.isInstance(c0)
                            && !"COUIPreferenceCategory".equals(c0.getClass().getSimpleName())) { cat0 = c0; }
                }
            }
            if (cat0 != null) {
                Method c1 = findMethod(cat0.getClass(), "getPreferenceCount", 0);
                Method g1 = findMethod(cat0.getClass(), "getPreference", 1);
                int m0 = ((Number) c1.invoke(cat0)).intValue();
                for (int j0 = 0; j0 < m0; j0++) {
                    Object it = g1.invoke(cat0, j0);
                    if (it != null && !grp0.isInstance(it) && it.getClass() != prefCls) {
                        MainHookLogger.log("event=row_template " + it.getClass().getSimpleName());
                        prefCls = it.getClass();
                        break;
                    }
                }
            }
        } catch (Throwable ignore) {}
        Class<?> groupCls = add.getDeclaringClass();
        Context ctx = (Context) screen.getClass().getMethod("getContext").invoke(screen);
        // 优先使用宿主原生的「跳转型」行类（COUIJumpPreference）：自带右箭头 + 原生卡片圆角/水波纹
        Class<?> rowCls = null;
        try {
            Class<?> jump = (HOST_CL != null ? HOST_CL
                    : XiaobuSettingsHook.class.getClassLoader()).loadClass(
                    "com.coui.appcompat.preference.COUIJumpPreference");
            try {
                jump.getConstructor(Context.class);
                rowCls = jump;
                MainHookLogger.log("event=row_cls COUIJumpPreference");
            } catch (Throwable t) {
                MainHookLogger.log("event=row_cls_no_ctx_ctor " + t);
                try {
                    jump.getConstructor(Context.class, android.util.AttributeSet.class);
                    rowCls = jump;
                    MainHookLogger.log("event=row_cls COUIJumpPreference(attr)");
                } catch (Throwable t2) {
                    MainHookLogger.log("event=row_cls_no_attr_ctor " + t2);
                }
            }
        } catch (Throwable t) {
            MainHookLogger.log("event=row_cls_load_fail " + t);
        }
        if (rowCls == null) rowCls = prefCls;
        Object p = rowCls.getConstructor(Context.class).newInstance(ctx);
        // 若回退到 androidx Preference，则强制套用宿主原生「跳转行」布局（含右箭头容器 + 原生圆角）
        if (rowCls == prefCls) {
            try {
                int jumpLayout = ctx.getResources().getIdentifier(
                        "coui_preference_withlittlewiget_assignment_in_right", "layout",
                        "com.oplus.keyboard");
                if (jumpLayout != 0) {
                    Method slr = findMethod(rowCls, "setLayoutResource", 1);
                    if (slr != null) {
                        slr.invoke(p, jumpLayout);
                        MainHookLogger.log("event=layout_set " + jumpLayout);
                    }
                }
            } catch (Throwable t) { MainHookLogger.log("event=layout_set_fail " + t); }
        }
        // 打上唯一 key：绑定回调里据此识别「我们这一行」并补挂 View 级点击
        try {
            Method sk = findMethodTyped(rowCls, "setKey", String.class);
            if (sk != null) sk.invoke(p, rowKey);
        } catch (Throwable t) { MainHookLogger.log("event=setkey_fail " + t); }
        Method st = findMethodTyped(rowCls, "setTitle", CharSequence.class);
        Method ss = findMethodTyped(rowCls, "setSummary", CharSequence.class);
        if (st != null) st.invoke(p, rowTitle);
        if (ss != null) ss.invoke(p, rowSummary);
        // 部分 COUI 行类用 setBrief/setDescription 之类承载副标题，逐个尝试常见名
        if (ss == null) {
            for (String nm : new String[]{"setSummary", "setBrief", "setDescription", "setSubtitle",
                    "setSummaryOn", "setContentDescription", "setSecondaryText"}) {
                Method m = findMethodTyped(rowCls, nm, CharSequence.class);
                if (m != null) {
                    try {
                        m.invoke(p, rowSummary);
                        MainHookLogger.log("event=summary_via " + nm);
                        break;
                    } catch (Throwable ignore) {}
                }
            }
        }
        Method sl = findMethod(prefCls, "setOnPreferenceClickListener", 1);
        if (sl != null) {
            Class<?> lis = sl.getParameterTypes()[0];
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(prefCls.getClassLoader(),
                    new Class[]{lis}, (pr, mm, args) -> {
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .post(() -> {
                                    try {
                                        android.app.Activity a = MainHook.lastActivity;
                                        MainHookLogger.log("event=row_click isDict=" + isDict);
                                        if (a == null) {
                                            showBindDialog(ctx);
                                        } else if (isDict) {
                                            UserDictPage.show(a);
                                        } else {
                                            SyncPage.show(a);
                                        }
                                    } catch (Throwable t) {
                                        MainHookLogger.log("event=page_err " + MainHook.stackOf(t));
                                        showBindDialog(ctx);
                                    }
                                });
                        return Boolean.TRUE;
                    });
            sl.invoke(p, proxy);
        }
        Object target = screen;
        Method cnt = findMethod(screen.getClass(), "getPreferenceCount", 0);
        Method getAt = findMethod(screen.getClass(), "getPreference", 1);
        if (cnt != null && getAt != null) {
            int n = ((Number) cnt.invoke(screen)).intValue();
            for (int i = 0; i < n; i++) {
                Object it = getAt.invoke(screen, i);
                if (it != null && groupCls.isInstance(it)) { target = it; break; }
            }
        }
        Method addT = findMethod(target.getClass(), "addPreference", 1);
        (addT != null ? addT : add).invoke(target, p);
        MainHookLogger.log("event=menu_added group=" + target.getClass().getSimpleName());
        // 首选：直接给 Preference 挂 androidx 的 OnPreferenceClickListener。
        // 宿主列表点击本来就是 itemView.setOnClickListener → Preference.performClick()
        // → mOnClickListener.onPreferenceClick()，这条链路由框架维护，
        // RecyclerView 复用/滚动/Fragment 重建都不会丢；之前用 View tag + 轮询补挂
        // 才会出现「点击经常没反应」（复用后新 holder 还没补上，或 tag 落在别的行上）。
        attachPrefClick(p, isDict);
        // 仅在 Preference 监听挂不上时才启用 View 级兜底（否则会和框架点击重复/相互覆盖）
        if (!PREF_CLICK_BOUND) {
            try {
                final android.app.Activity actRef = MainHook.lastActivity;
                if (actRef != null) {
                    android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                    h.post(() -> ensureRowClick(actRef));
                    h.postDelayed(() -> ensureRowClick(actRef), 800);
                    h.postDelayed(() -> ensureRowClick(actRef), 2500);
                }
            } catch (Throwable t) { MainHookLogger.log("event=rowclick_setup_fail " + t); }
        }
        if (PENDING_INSPECT) {
            PENDING_INSPECT = false;
            MainHookLogger.log("event=insp_auto");
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> inspectScreen(frag), 1500);
        }
    }

    /** 框架层点击是否已接管（接管后不再做 View 级补挂） */
    private static volatile boolean PREF_CLICK_BOUND = false;

    /**
     * 给注入的 Preference 挂 androidx 原生点击监听：
     *   宿主 RecyclerView 的 itemView 点击 → Preference.performClick()
     *   → isEnabled() && isSelectable() → onClick() → mOnClickListener.d(this)
     * 该接口在宿主里被 R8 改名为 androidx.preference.d，方法名 d(Preference) 返回 boolean，
     * 所以这里通过 setOnPreferenceClickListener 的形参类型动态取接口，不写死类名。
     */
    private static boolean attachPrefClick(Object pref, final boolean isDict) {
        try {
            java.lang.reflect.Method setter = null;
            for (java.lang.reflect.Method m : pref.getClass().getMethods()) {
                if (m.getName().equals("setOnPreferenceClickListener") && m.getParameterCount() == 1) {
                    setter = m; break;
                }
            }
            if (setter == null) { MainHookLogger.log("event=pref_click_nosetter"); return false; }
            // performClick() 前置条件是 isEnabled() && isSelectable()
            try { pref.getClass().getMethod("setSelectable", boolean.class).invoke(pref, true); } catch (Throwable ignore) {}
            try { pref.getClass().getMethod("setEnabled", boolean.class).invoke(pref, true); } catch (Throwable ignore) {}
            Class<?> iface = setter.getParameterTypes()[0];
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    iface.getClassLoader(), new Class<?>[]{iface},
                    (p, m, args) -> {
                        if (m.getDeclaringClass() == Object.class) {
                            String n = m.getName();
                            if ("equals".equals(n)) return p == args[0];
                            if ("hashCode".equals(n)) return System.identityHashCode(p);
                            if ("toString".equals(n)) return "WtPrefClickListener";
                            return null;
                        }
                        MainHookLogger.log("event=pref_click isDict=" + isDict);
                        if (isDict) {
                            android.app.Activity a = MainHook.lastActivity;
                            if (a != null) UserDictPage.show(a);
                        } else {
                            openSyncPage();
                        }
                        return m.getReturnType() == boolean.class ? Boolean.TRUE : null;
                    });
            setter.invoke(pref, proxy);
            PREF_CLICK_BOUND = true;
            MainHookLogger.log("event=pref_click_ok iface=" + iface.getName());
            return true;
        } catch (Throwable t) {
            MainHookLogger.log("event=pref_click_fail " + t);
            return false;
        }
    }

    /** 点击「跨设备同步」行 → 打开二级页（框架回调，主线程） */
    private static void openSyncPage() {
        try {
            android.app.Activity a = MainHook.lastActivity;
            if (a == null || a.isFinishing()
                    || (android.os.Build.VERSION.SDK_INT >= 17 && a.isDestroyed())) {
                MainHookLogger.log("event=open_noact");
                return;
            }
            SyncPage.show(a);
        } catch (Throwable t) {
            MainHookLogger.log("event=open_err " + t);
        }
    }

    /** 设置页是否对用户可见（在后台时不做任何视图遍历，避免与输入法主线程抢时间片） */
    private static boolean isVisibleToUser(android.app.Activity a) {
        try {
            if (a == null) return false;
            if (android.os.Build.VERSION.SDK_INT >= 17 && a.isDestroyed()) return false;
            if (a.isFinishing()) return false;
            android.view.View decor = a.getWindow() != null ? a.getWindow().getDecorView() : null;
            if (decor == null || !decor.isShown()) return false;
            return a.hasWindowFocus();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 周期性重绑「跨设备同步」行点击。仅靠一次绑定不够：
     * RecyclerView 复用/重建、屏幕旋转、Fragment 重建都会让监听丢失（表现为"经常点了没反应"），
     * 因此只要 Activity 还活着且可见，就每 1.2s 补挂一次（命中已有 tag 会直接跳过，开销极小）。
     */
    /** 每个 Activity 只起一个看护循环（多次 onResume 不得叠加，否则循环越积越多拖垮主线程） */
    private static final int WATCH_TAG_KEY = 0x1f0a5e13;

    public static void startRowClickWatch(final android.app.Activity a) {
        if (a == null) return;
        try {
            android.view.View decor = a.getWindow() != null ? a.getWindow().getDecorView() : null;
            if (decor == null) return;
            if (decor.getTag(WATCH_TAG_KEY) != null) return;   // 该 Activity 已有看护循环
            decor.setTag(WATCH_TAG_KEY, Boolean.TRUE);
        } catch (Throwable t) {
            MainHookLogger.log("event=watch_tag_fail " + t);
        }
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable[] loop = new Runnable[1];
        loop[0] = () -> {
            try {
                boolean dead = (android.os.Build.VERSION.SDK_INT >= 17 && a.isDestroyed()) || a.isFinishing();
                if (dead) return;
                // 只有设置界面真正可见（前台）时才做视图遍历。
                // 之前退出设置页后循环仍在跑：输入法与设置页同进程同主线程，
                // 后台每 1.2s 遍历一次视图树会抢主线程，极端情况下表现成「输入法卡死/唤不起」。
                if (!isVisibleToUser(a)) {
                    h.postDelayed(loop[0], 2500);
                    return;
                }
                ensureRowClick(a);
                h.postDelayed(loop[0], 1200);
            } catch (Throwable ignore) {
                try { h.postDelayed(loop[0], 3000); } catch (Throwable ignore2) {}
            }
        };
        h.postDelayed(loop[0], 800);
    }

    /** 给「跨设备同步」行补 View 级点击（用唯一 key 打 tag 防重复，滚动复用后自动补挂） */
    private static final int ROW_TAG_KEY = 0x1f0a5e12;
    private static final int SCROLL_WATCH_TAG_KEY = 0x1f0a5e14;
    private static final String ROW_TITLE = "跨设备同步";
    private static final String ROW_TITLE_DICT = "个人词库";

    public static void ensureRowClick(android.app.Activity act) {
        try {
            if (PREF_CLICK_BOUND) return;      // 框架点击已接管，无需 View 级补挂
            if (act == null || SyncPage.isOpen() || UserDictPage.isOpen()) return;
            int rvId = act.getResources().getIdentifier("recycler_view", "id", "com.oplus.keyboard");
            android.view.View rv = rvId != 0 ? act.findViewById(rvId) : null;
            if (rv == null) {
                // 兜底：从 decor 里找 RecyclerView（类名匹配，避免依赖资源 id）
                final android.view.View[] hit = new android.view.View[1];
                walkDecor(act, v -> {
                    if (hit[0] == null && v.getClass().getName().contains("RecyclerView")) hit[0] = v;
                });
                rv = hit[0];
            }
            if (rv == null) return;
            final android.view.View rvRef = rv;
            bindRowClick(rvRef, act);
            // 滚动 / 复用后重新补挂（每个 RecyclerView 只挂一次，否则监听会越积越多拖垮主线程）
            try {
                if (rvRef.getTag(SCROLL_WATCH_TAG_KEY) == null) {
                    rvRef.setTag(SCROLL_WATCH_TAG_KEY, Boolean.TRUE);
                    rvRef.getViewTreeObserver().addOnScrollChangedListener(() -> bindRowClick(rvRef, act));
                }
            } catch (Throwable ignore) {}
        } catch (Throwable t) {
            MainHookLogger.log("event=rowclick_err " + t);
        }
    }

    private static void bindRowClick(android.view.View rv, android.app.Activity act) {
        try {
            if (!(rv instanceof android.view.ViewGroup)) return;
            android.view.ViewGroup g = (android.view.ViewGroup) rv;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View row = g.getChildAt(i);
                android.widget.TextView t = row.findViewById(android.R.id.title);
                if (t == null) continue;
                String title = String.valueOf(t.getText());
                boolean isDict = ROW_TITLE_DICT.equals(title);
                if (!ROW_TITLE.equals(title) && !isDict) continue;
                // RecyclerView 复用 ViewHolder：tag 必须记录「绑给了哪个目标」，
                // 否则首页那行绑过之后，词库页复用同一个 View 会因 tag 存在而跳过重绑，
                // 点「个人词库」仍然跳去同步页。
                Object tag = row.getTag(ROW_TAG_KEY);
                String want = isDict ? "dict" : "sync";
                if (want.equals(tag)) continue;            // 已绑到同一目标
                row.setTag(ROW_TAG_KEY, want);
                row.setClickable(true);
                row.setOnClickListener(v -> {
                    if (isDict) UserDictPage.show(act);
                    else SyncPage.show(act);
                });
                MainHookLogger.log("event=rowclick_bound " + title);
            }
        } catch (Throwable t) {
            MainHookLogger.log("event=rowclick_bind_err " + t);
        }
    }

    private static void walkDecor(android.app.Activity act, java.util.function.Consumer<android.view.View> cb) {
        android.view.View root = act.getWindow().getDecorView();
        java.util.ArrayDeque<android.view.View> st = new java.util.ArrayDeque<>();
        st.push(root);
        while (!st.isEmpty()) {
            android.view.View v = st.pop();
            cb.accept(v);
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) st.push(g.getChildAt(i));
            }
        }
    }

    private static volatile boolean PENDING_INSPECT = false;

    /** 请求在下一次菜单注入成功后自动抓取原生样式 */
    public static void requestInspect() { PENDING_INSPECT = true; }

    private static Method findMethodTyped(Class<?> c, String name, Class<?> param) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1
                        && param.isAssignableFrom(m.getParameterTypes()[0])) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return findMethod(c, name, 1);
    }

    private static Method findMethod(Class<?> c, String name, int params) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == params) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == params) { m.setAccessible(true); return m; }
        }
        return null;
    }

    /** 跨设备同步面板：状态 + 本机出码 + 输入对端码 */
    private static void showBindDialog(final Context ctx) {
        final WtClient c = WtClient.active;
        if (c == null) {
            Toast.makeText(ctx, "同步引擎未运行", Toast.LENGTH_LONG).show();
            return;
        }
        int dm = (int) (ctx.getResources().getDisplayMetrics().density * 16);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dm, dm / 2, dm, 0);

        final android.widget.TextView tvState = new android.widget.TextView(ctx);
        tvState.setTextSize(14);
        tvState.setPadding(0, 0, 0, dm / 2);
        box.addView(tvState);

        final EditText et = new EditText(ctx);
        et.setSingleLine(true);
        et.setHint("输入另一台设备显示的 6 位关联码");
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dm / 2, 0, 0);
        final android.widget.Button bIssue = new android.widget.Button(ctx);
        bIssue.setText("本机生成关联码");
        final android.widget.Button bJoin = new android.widget.Button(ctx);
        bJoin.setText("关联");
        row.addView(bIssue, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(bJoin, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(row);

        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(ctx)
                .setTitle("跨设备同步")
                .setMessage("在平板或电脑的微信输入法里生成关联码后输入此处；或点「本机生成关联码」，"
                        + "再到另一台设备上输入。")
                .setView(box)
                .setNegativeButton("关闭", null)
                .create();

        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable refresh = new Runnable() {
            @Override public void run() {
                tvState.setText("状态：" + c.status()
                        + (c.isPaired() ? "\n已入组，剪贴板自动同步中" : ""));
                String code = c.issueCode();
                if (code != null) {
                    et.setText(code);
                    et.setEnabled(false);
                }
                h.postDelayed(this, 1000);
            }
        };
        dlg.setOnShowListener(d -> h.post(refresh));
        dlg.setOnDismissListener(d -> h.removeCallbacks(refresh));

        bIssue.setOnClickListener(v -> {
            et.setEnabled(false);
            c.startIssue();
            Toast.makeText(ctx, "已申请关联码，等待对端输入", Toast.LENGTH_SHORT).show();
        });
        bJoin.setOnClickListener(v -> {
            String code = et.getText().toString().trim();
            if (code.length() != 6) {
                Toast.makeText(ctx, "请输入 6 位关联码", Toast.LENGTH_SHORT).show();
                return;
            }
            c.startJoin(code);
            Toast.makeText(ctx, "已提交，等待对方确认", Toast.LENGTH_SHORT).show();
        });
        dlg.show();
    }
}
