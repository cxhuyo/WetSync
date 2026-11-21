package com.cxhuyo.wtsync;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 小布输入法「个人词库」页面。
 *
 * 数据来源是宿主自己的 Kernel.userDictSyncExport（明文 JSON），不碰引擎的私有存储：
 *   {"language_dict":{"cn_words":[{"word":"词(ci)","type":1,"timestamp":N,"commits":M,
 *                                  "commits_by_spelling":{"ci":M}}],
 *                     "en_words":[{"word":"APK(APK)","type":1,"timestamp":N,"commits":M}]}}
 *
 * 编辑能力来自实测结论：userDictSyncImport 对**已存在的词条会覆盖** commits
 * （实测把 1 改成 9999 后导入，再导出读到 9999）。宿主没有单条删除 API，
 * 因此「删除」做成软删除：commits 归零，该词不再被优先推荐，但仍留在库里
 * （下次从微信同步时若对方还有这个词，会被重新写回）。
 *
 * 每次保存都是「导出 → 改一个词 → 全量导入 → 重启词库引擎」，约 2~4 秒。
 */
public final class UserDictPage {

    private static boolean OPEN = false;
    private static Activity act;
    private static LinearLayout listBox;
    private static TextView statView;
    private static EditText searchBox;
    private static LinearLayout indexBar;
    private static TextView indexBubble;
    private static LinearLayout lastBlock;

    private static final List<Entry> all = new ArrayList<>();
    /** 首字母 → 该字母在 lastBlock 里第一次出现的 child 下标（索引条跳转用） */
    private static final java.util.Map<String, Integer> letterRows = new java.util.HashMap<>();
    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ#";
    private static String activeLetter = "";
    private static String query = "";
    /** 排序方式：0 = 按字母，1 = 按使用次数 */
    private static final int SORT_LETTER = 0;
    private static final int SORT_COUNT = 1;
    private static int sortBy = SORT_COUNT;
    private static TextView sortBtn;
    /** 当前渲染窗口的起始下标（索引条跳转时改它） */
    private static int renderStart = 0;
    /** 当前已渲染多少行（用于「加载更多」） */
    private static int renderedCount = 0;
    /** 一窗渲染多少行（全量上万条，必须分窗） */
    private static final int WINDOW = 200;
    private static volatile boolean busy = false;

    /** 一条词库记录 */
    static final class Entry {
        String word = "";       // 词本身（不含括号）
        String pinyin = "";     // 括号里的内容
        String rawWord = "";    // 原始 "词(拼音)"
        int commits = 1;
        boolean en = false;
    }

    public static boolean isOpen() {
        // 页面现挂在宿主 nav 容器里，是否还在由 SyncPage 的 navPage 决定
        try {
            return OPEN && SyncPage.isOpen();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean close() {
        OPEN = false;
        boolean ok = SyncPage.close();
        all.clear();
        return ok;
    }

    public static void show(final Activity a) {
        if (a == null) return;
        act = a;
        try {
            View content = buildContent(a);
            FrameLayout overlay = buildOverlay(a);
            if (!SyncPage.mountPage(a, content, "个人词库", overlay)) {
                MainHookLogger.log("event=udpage_mount_fail");
                return;
            }
            OPEN = true;
            reload();
            MainHookLogger.log("event=udpage_open");
        } catch (Throwable t) {
            MainHookLogger.log("event=udpage_open_fail " + t);
            OPEN = false;
        }
    }

    // ---------------- UI ----------------

    /**
     * 页面内容。与同步页同构：这里只产「业务内容」，滚动容器 / AppBar / 进出场动画
     * 全部由 SyncPage.mountPage 挂进宿主 nav 容器时提供，因此本方法里不再自建
     * ScrollView，也不做状态栏避让。
     */
    private static View buildContent(Activity a) {
        final Context ctx = a;
        // 每次重建页面都从「无搜索」开始：query 是静态字段，不清会沿用上次的过滤词，
        // 表现为「搜索框已空，列表却还是上次的搜索结果」。
        query = "";
        activeLetter = "";
        // 深色判断必须看系统夜间模式，不能用 ?attr/colorBackground：
        // 小布的 settings 主题本身是浅色（colorBackground = #FAFAFA），深色外观是系统
        // 「强制深色(force dark)」反色出来的。按主题属性判断会得到 dark=false，
        // 于是这里画白色卡片 + 深色文字，再被系统反成黑卡片 + 白字。
        final boolean dark = isNight(ctx);
        final int card = dark ? 0xFF2F2F35 : 0xFFFFFFFF;
        final int text = dark ? 0xFFEDEDED : 0xFF1A1A1A;
        final int sub = dark ? 0xFF9B9B9B : 0xFF8A8A8A;

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(ctx, 8), 0, dp(ctx, 48));
        // 本页自己按夜间模式配色，关掉系统的强制深色，否则浅色主题的白色卡片会被反成纯黑
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try { col.setForceDarkAllowed(false); } catch (Throwable ignore) {}
        }

        // 统计 + 新增（"新增"从顶栏挪到这里：COUIToolbar 右侧只能放 menu，动态造菜单不划算）
        LinearLayout statRow = new LinearLayout(ctx);
        statRow.setOrientation(LinearLayout.HORIZONTAL);
        statRow.setGravity(Gravity.CENTER_VERTICAL);
        statRow.setPadding(dp(ctx, 20), dp(ctx, 2), dp(ctx, 16), dp(ctx, 10));

        statView = new TextView(ctx);
        statView.setTextSize(13);
        statView.setTextColor(sub);
        statView.setText("正在读取词库…");
        statRow.addView(statView, new LinearLayout.LayoutParams(0, -2, 1));

        TextView add = new TextView(ctx);
        add.setText("新增");
        add.setTextSize(14);
        add.setTextColor(0xFF4C8DFF);
        add.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 2), dp(ctx, 6));
        add.setOnClickListener(v -> showAddDialog());
        statRow.addView(add, new LinearLayout.LayoutParams(-2, -2));

        // 排序切换：次数 ↔ 字母
        sortBtn = new TextView(ctx);
        sortBtn.setTextSize(14);
        sortBtn.setTextColor(0xFF4C8DFF);
        sortBtn.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
        sortBtn.setText(sortBy == SORT_COUNT ? "按次数" : "按字母");
        sortBtn.setOnClickListener(v -> {
            sortBy = (sortBy == SORT_COUNT) ? SORT_LETTER : SORT_COUNT;
            sortBtn.setText(sortBy == SORT_COUNT ? "按次数" : "按字母");
            renderStart = 0;
            renderList();
        });
        statRow.addView(sortBtn, new LinearLayout.LayoutParams(-2, -2));

        col.addView(statRow, new LinearLayout.LayoutParams(-1, -2));

        searchBox = new EditText(ctx);
        searchBox.setHint("搜索词条或拼音");
        searchBox.setTextSize(15);
        searchBox.setTextColor(text);
        searchBox.setHintTextColor(sub);
        searchBox.setSingleLine(true);
        searchBox.setBackground(roundBg(card, 12, ctx));
        searchBox.setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.leftMargin = dp(ctx, 16);
        slp.rightMargin = dp(ctx, 16);
        col.addView(searchBox, slp);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                renderList();
            }
        });

        // 列表本体（滚动交给挂载层的 NestedScrollView，与 appbar 联动）
        listBox = new LinearLayout(ctx);
        listBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-1, -2);
        vlp.topMargin = dp(ctx, 12);
        col.addView(listBox, vlp);

        return col;
    }

    /** 浮层：右侧索引条 + 中央放大字母提示（叠在骨架最上层，不随内容滚动） */
    private static FrameLayout buildOverlay(Activity a) {
        final Context ctx = a;
        final boolean dark = isNight(ctx);
        FrameLayout ov = new FrameLayout(ctx);
        ov.setClipChildren(false);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try { ov.setForceDarkAllowed(false); } catch (Throwable ignore) {}
        }

        indexBar = buildIndexBar(ctx, dark ? 0xFF9B9B9B : 0xFF8A8A8A);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(ctx, 28), -1, Gravity.END);
        ilp.topMargin = dp(ctx, 214);        // 让开 AppBar + 搜索框
        ilp.bottomMargin = dp(ctx, 96);      // 让开底部手势区
        ov.addView(indexBar, ilp);

        indexBubble = new TextView(ctx);
        indexBubble.setTextSize(34);
        indexBubble.setTextColor(dark ? 0xFFEDEDED : 0xFF1A1A1A);
        indexBubble.setGravity(Gravity.CENTER);
        indexBubble.setBackground(roundBg(dark ? 0xF22C2C2E : 0xF2FFFFFF, 18, ctx));
        indexBubble.setPadding(dp(ctx, 28), dp(ctx, 16), dp(ctx, 28), dp(ctx, 16));
        indexBubble.setVisibility(View.GONE);
        ov.addView(indexBubble, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return ov;
    }

    /** 右侧 A-Z # 索引条 */
    private static LinearLayout buildIndexBar(Context ctx, int sub) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER);
        for (int i = 0; i < LETTERS.length(); i++) {
            TextView t = new TextView(ctx);
            t.setText(String.valueOf(LETTERS.charAt(i)));
            t.setTextSize(9);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(sub);
            bar.addView(t, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        bar.setOnTouchListener((v, ev) -> handleIndexTouch(ev));
        return bar;
    }

    private static boolean handleIndexTouch(android.view.MotionEvent ev) {
        if (indexBar == null) return false;
        int h = indexBar.getHeight();
        if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
            MainHookLogger.log("event=udpage_idx_down y=" + ev.getY() + " h=" + h
                    + " rows=" + letterRows.size());
        }
        if (h <= 0) return false;
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
            case android.view.MotionEvent.ACTION_MOVE: {
                float cell = h / (float) LETTERS.length();
                int idx = (int) (ev.getY() / cell);
                if (idx < 0) idx = 0;
                if (idx >= LETTERS.length()) idx = LETTERS.length() - 1;
                selectLetter(String.valueOf(LETTERS.charAt(idx)));
                return true;
            }
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                // 松手后稍作停留，让用户看清当前字母（立刻消失会像「没反应」）
                if (indexBubble != null) {
                    indexBubble.postDelayed(() -> {
                        if (indexBubble != null) indexBubble.setVisibility(View.GONE);
                    }, 900);
                }
                return true;
            default:
                return false;
        }
    }

    /** 选中某个首字母：高亮、弹放大提示、把列表滚到该字母第一行 */
    private static void selectLetter(String letter) {
        if (indexBar == null || act == null) return;
        activeLetter = letter;
        final boolean dark = isNight(act);
        final int idle = dark ? 0xFF9B9B9B : 0xFF8A8A8A;
        final int on = 0xFF4C8DFF;
        for (int i = 0; i < indexBar.getChildCount(); i++) {
            View ch = indexBar.getChildAt(i);
            if (!(ch instanceof TextView)) continue;
            TextView t = (TextView) ch;
            boolean hit = letter.equals(String.valueOf(t.getText()));
            // 不能用 ?attr/textColorSecondary：浅色主题下它是深灰，被系统反色后又变成黑，
            // 深色底上直接看不见（主人反馈「其它字母都变黑了」就是这个）。
            t.setTextColor(hit ? on : idle);
            t.setTextSize(hit ? 12 : 9);
            t.setTypeface(null, hit ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        if (indexBubble != null) {
            indexBubble.setText(letter);
            indexBubble.setVisibility(View.VISIBLE);
        }
        // 跳到该字母：不能靠「已渲染的行」定位 —— 一屏只渲染 WINDOW 行，
        // 按字母排序时这些行往往只覆盖前两三个字母（实测 rows=3），
        // 点后面的字母就找不到行、什么都不会发生。
        // 正确做法是在完整数据里找该字母的第一条，从那里重新渲染一窗。
        List<Entry> src = currentSrc();
        int idx = -1;
        for (int i = 0; i < src.size(); i++) {
            if (letter.equals(letterOf(src.get(i)))) { idx = i; break; }
        }
        if (idx < 0) {
            MainHookLogger.log("event=udpage_no_letter " + letter);
            return;
        }
        renderStart = idx;
        renderList();
        scrollToTop();
        MainHookLogger.log("event=udpage_jump letter=" + letter + " idx=" + idx);
    }

    /** 把宿主 NestedScrollView 滚到顶部 */
    private static void scrollToTop() {
        View scroller = null;
        for (View p = listBox; p != null; p = (p.getParent() instanceof View) ? (View) p.getParent() : null) {
            String cn = p.getClass().getName();
            if (cn.contains("NestedScrollView") || cn.contains("ScrollView")) { scroller = p; break; }
        }
        if (scroller == null) return;
        try {
            scroller.getClass().getMethod("smoothScrollTo", int.class, int.class).invoke(scroller, 0, 0);
        } catch (Throwable ignore) {}
    }

    /**
     * 当前应展示的数据（过滤 + 排序）。
     * 单独抽出来是因为索引条跳转也要用它算「某字母在完整数据里的位置」。
     */
    private static List<Entry> currentSrc() {
        List<Entry> src = new ArrayList<>();
        if (query.isEmpty()) {
            src.addAll(all);
        } else {
            String q = query.toLowerCase();
            for (Entry e : all) {
                if (e.word.toLowerCase().contains(q) || e.pinyin.toLowerCase().contains(q)) src.add(e);
            }
        }
        if (sortBy == SORT_COUNT) {
            src.sort((x, y) -> x.commits != y.commits ? y.commits - x.commits
                    : x.word.compareTo(y.word));
        } else {
            src.sort((x, y) -> {
                int c = letterOf(x).compareTo(letterOf(y));
                return c != 0 ? c : x.pinyin.compareTo(y.pinyin);
            });
        }
        return src;
    }

    private static void renderList() {
        if (listBox == null || act == null) return;
        final Context ctx = act;
        final boolean dark = isNight(ctx);
        final int card = dark ? 0xFF2F2F35 : 0xFFFFFFFF;
        final int text = dark ? 0xFFEDEDED : 0xFF1A1A1A;
        final int sub = dark ? 0xFF9B9B9B : 0xFF8A8A8A;

        listBox.removeAllViews();

        List<Entry> src = currentSrc();
        // 词库上万条，全量渲染会卡死；只渲染一屏多的窗口。
        // renderStart 由索引条跳转控制，点某个字母就从该字母的第一条开始渲染。
        if (renderStart < 0) renderStart = 0;
        if (renderStart > src.size() - 1) renderStart = Math.max(0, src.size() - 1);
        final int limit = Math.min(src.size() - renderStart, WINDOW);
        if (limit == 0) {
            TextView empty = new TextView(ctx);
            empty.setText(all.isEmpty() ? "词库为空" : "没有匹配的词条");
            empty.setTextSize(14);
            empty.setTextColor(sub);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(ctx, 60), 0, 0);
            listBox.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        } else {
            LinearLayout block = new LinearLayout(ctx);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setBackground(roundBg(card, 16, ctx));
            letterRows.clear();
            for (int i = 0; i < limit; i++) {
                Entry e = src.get(renderStart + i);
                String L = letterOf(e);
                if (!letterRows.containsKey(L)) letterRows.put(L, block.getChildCount());
                block.addView(makeRow(ctx, e, text, sub, dark));
                if (i < limit - 1) block.addView(divider(ctx, dark));
            }
            lastBlock = block;
            renderedCount = limit;
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
            blp.leftMargin = dp(ctx, 16);
            blp.rightMargin = dp(ctx, sortBy == SORT_LETTER ? 44 : 16);   // 按字母时给索引条让位
            listBox.addView(block, blp);
            addFooter(ctx, block, src.size(), text, sub, dark);
        }

        // 索引条只在「按字母」时有意义（按次数排列时字母是散开的）
        if (indexBar != null) {
            indexBar.setVisibility(sortBy == SORT_LETTER ? View.VISIBLE : View.GONE);
        }

        int cn = 0, en = 0;
        for (Entry e : all) { if (e.en) en++; else cn++; }
        String s = "中文 " + cn + " · 英文 " + en;
        if (!query.isEmpty()) s = "匹配 " + src.size() + " · " + s;
        // 词库是分窗渲染的，明确告诉用户「看到的是第几段」，否则会以为数据丢了
        if (src.size() > renderedCount) {
            s += " · 已显示 " + renderedCount;
        }
        if (statView != null) statView.setText(s);
    }






    /** 列表底部的「加载更多」入口 */
    private static void addFooter(final Context ctx, final LinearLayout block, final int total,
                                  final int text, final int sub, final boolean dark) {
        if (renderStart + renderedCount >= total) return;
        final TextView more = new TextView(ctx);
        more.setText("加载更多（还有 " + (total - renderStart - renderedCount) + " 条）");
        more.setTextSize(14);
        more.setTextColor(0xFF4C8DFF);
        more.setGravity(Gravity.CENTER);
        more.setPadding(0, dp(ctx, 18), 0, dp(ctx, 18));
        more.setOnClickListener(v -> {
            block.removeView(more);
            appendWindow(ctx, block, total, text, sub, dark);
        });
        block.addView(more);
    }

    /** 追加下一窗（不重建已有行，滚动位置不会跳） */
    private static void appendWindow(Context ctx, LinearLayout block, int total,
                                     int text, int sub, boolean dark) {
        List<Entry> src = currentSrc();
        int from = renderStart + renderedCount;
        int to = Math.min(total, from + WINDOW);
        // 上一个「加载更多」把最后一行下面的分割线吞了，这里补一条
        if (block.getChildCount() > 0) block.addView(divider(ctx, dark));
        for (int i = from; i < to; i++) {
            Entry e = src.get(i);
            String L = letterOf(e);
            if (!letterRows.containsKey(L)) letterRows.put(L, block.getChildCount());
            block.addView(makeRow(ctx, e, text, sub, dark));
            if (i < to - 1) block.addView(divider(ctx, dark));
        }
        renderedCount += (to - from);
        addFooter(ctx, block, total, text, sub, dark);
        int cn = 0, en = 0;
        for (Entry e : all) { if (e.en) en++; else cn++; }
        String s = "中文 " + cn + " · 英文 " + en;
        if (!query.isEmpty()) s = "匹配 " + src.size() + " · " + s;
        if (src.size() > renderedCount) s += " · 已显示 " + renderedCount;
        if (statView != null) statView.setText(s);
    }

    private static View makeRow(Context ctx, final Entry e, int text, int sub, boolean dark) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12));

        LinearLayout left = new LinearLayout(ctx);
        left.setOrientation(LinearLayout.VERTICAL);

        TextView w = new TextView(ctx);
        w.setText(e.word);
        w.setTextSize(16);
        w.setTextColor(e.commits > 0 ? text : sub);
        left.addView(w);

        TextView p = new TextView(ctx);
        p.setText(e.pinyin);
        p.setTextSize(12);
        p.setTextColor(sub);
        left.addView(p);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));

        TextView c = new TextView(ctx);
        c.setText(e.commits > 0 ? String.valueOf(e.commits) : "已删除");
        c.setTextSize(13);
        c.setTextColor(e.commits > 0 ? sub : 0xFFD9534F);
        row.addView(c);

        // 按下反馈只负责「按压」，不能自带底色：
        // 之前用 new RippleDrawable(color, null, null)，在宿主 ROM 上内容为 null 时会被渲染成
        // 一整块不透明黑，把外层卡片的灰色底整片盖掉（截图采样：卡片区 RGB(0,0,0)，
        // 而页面背景是 RGB(6,6,6)——比背景还黑，就是这个原因）。
        android.util.TypedValue tvSel = new android.util.TypedValue();
        if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tvSel, true)
                && tvSel.resourceId != 0) {
            row.setBackgroundResource(tvSel.resourceId);
        }
        row.setClickable(true);
        row.setOnClickListener(v -> showEditDialog(e));
        return row;
    }

    // ---------------- 读取 ----------------

    private static void reload() {
        if (busy) return;
        busy = true;
        if (statView != null) statView.setText("正在读取词库…");
        new Thread(() -> {
            try {
                File dir = new File(XiaobuInject.ctx.getFilesDir(), "wtsync");
                dir.mkdirs();
                File f = new File(dir, "ud_view.json");
                if (!XiaobuPhraseStore.exportUserDict(f.getAbsolutePath())) {
                    ui(() -> { if (statView != null) statView.setText("读取失败：导出接口不可用"); });
                    return;
                }
                String json = new String(Files.readAllBytes(f.toPath()), "UTF-8");
                final List<Entry> parsed = parse(json);
                ui(() -> {
                    all.clear();
                    all.addAll(parsed);
                    renderList();
                });
            } catch (Throwable t) {
                MainHookLogger.log("event=udpage_load_err " + t);
                ui(() -> { if (statView != null) statView.setText("读取失败：" + t.getMessage()); });
            } finally {
                busy = false;
            }
        }, "WtUdLoad").start();
    }

    /** 从导出 JSON 里解析出 cn_words + en_words */
    static List<Entry> parse(String json) {
        List<Entry> out = new ArrayList<>();
        parseArray(json, "cn_words", false, out);
        parseArray(json, "en_words", true, out);
        // 次数高的排前面，同次数按词长
        out.sort((x, y) -> {
            if (x.commits != y.commits) return y.commits - x.commits;
            return x.word.compareTo(y.word);
        });
        return out;
    }

    private static void parseArray(String json, String key, boolean en, List<Entry> out) {
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return;
        int arr = json.indexOf('[', k);
        if (arr < 0) return;
        int p = arr + 1;
        while (p < json.length()) {
            int objStart = json.indexOf('{', p);
            if (objStart < 0) break;
            int objEnd = matchBrace(json, objStart);
            if (objEnd < 0) break;
            String obj = json.substring(objStart, objEnd + 1);
            Entry e = new Entry();
            e.en = en;
            e.rawWord = strField(obj, "word");
            e.commits = (int) numField(obj, "commits");
            if (!e.rawWord.isEmpty()) {
                int lp = e.rawWord.lastIndexOf('(');
                if (lp > 0 && e.rawWord.endsWith(")")) {
                    e.word = e.rawWord.substring(0, lp);
                    e.pinyin = e.rawWord.substring(lp + 1, e.rawWord.length() - 1);
                } else {
                    e.word = e.rawWord;
                    e.pinyin = en ? e.rawWord : "";
                }
                out.add(e);
            }
            p = objEnd + 1;
            int nextObj = json.indexOf('{', p);
            int close = json.indexOf(']', p);
            if (nextObj < 0 || (close >= 0 && close < nextObj)) break;
        }
    }

    private static String strField(String obj, String name) {
        int i = obj.indexOf("\"" + name + "\":\"");
        if (i < 0) return "";
        int s = i + name.length() + 4;
        StringBuilder sb = new StringBuilder();
        for (int p = s; p < obj.length(); p++) {
            char c = obj.charAt(p);
            if (c == '\\' && p + 1 < obj.length()) {
                char n = obj.charAt(++p);
                if (n == 'n') sb.append('\n');
                else if (n == 't') sb.append('\t');
                else sb.append(n);
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static long numField(String obj, String name) {
        int i = obj.indexOf("\"" + name + "\":");
        if (i < 0) return 0;
        int p = i + name.length() + 3;
        boolean neg = false;
        if (p < obj.length() && obj.charAt(p) == '-') { neg = true; p++; }
        long v = 0;
        while (p < obj.length() && Character.isDigit(obj.charAt(p))) {
            v = v * 10 + (obj.charAt(p) - '0');
            p++;
        }
        return neg ? -v : v;
    }

    private static int matchBrace(String s, int open) {
        int depth = 0;
        boolean inStr = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    // ---------------- 编辑 ----------------

    private static void showEditDialog(final Entry e) {
        final Activity a = act;
        if (a == null) return;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 20), dp(a, 8), dp(a, 20), 0);

        final EditText w = new EditText(a);
        w.setHint("词条");
        w.setText(e.word);
        w.setSingleLine(true);
        box.addView(tip(a, "词条"));
        box.addView(w);

        final EditText p = new EditText(a);
        p.setHint("拼音，空格分隔");
        p.setText(e.pinyin);
        p.setSingleLine(true);
        if (!e.en) {
            box.addView(tip(a, "拼音"));
            box.addView(p);
        }

        final EditText c = new EditText(a);
        c.setInputType(InputType.TYPE_CLASS_NUMBER);
        c.setText(String.valueOf(e.commits));
        c.setSingleLine(true);
        box.addView(tip(a, "使用次数（0 = 不再推荐该词）"));
        box.addView(c);

        final AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("编辑词条")
                .setView(box)
                .setPositiveButton("保存", null)
                .setNeutralButton("删除", null)
                .setNegativeButton("取消", null)
                .create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String nw = w.getText().toString().trim();
            if (nw.isEmpty()) {
                Toast.makeText(a, "词条不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            int nc;
            try { nc = Integer.parseInt(c.getText().toString().trim()); }
            catch (Exception ex) { nc = e.commits; }
            if (nc < 0) nc = 0;
            d.dismiss();
            applyEdit(e, nw, p.getText().toString().trim(), nc);
        });
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            d.dismiss();
            applyEdit(e, e.word, e.pinyin, 0);
        });
    }

    private static void showAddDialog() {
        final Activity a = act;
        if (a == null) return;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 20), dp(a, 8), dp(a, 20), 0);

        final EditText w = new EditText(a);
        w.setHint("词条，如：小布");
        w.setSingleLine(true);
        final EditText p = new EditText(a);
        p.setHint("拼音，空格分隔，如：xiao bu");
        p.setSingleLine(true);
        final EditText c = new EditText(a);
        c.setInputType(InputType.TYPE_CLASS_NUMBER);
        c.setText("50");
        c.setSingleLine(true);

        box.addView(tip(a, "词条"));
        box.addView(w);
        box.addView(tip(a, "拼音"));
        box.addView(p);
        box.addView(tip(a, "使用次数"));
        box.addView(c);

        final AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("新增词条")
                .setView(box)
                .setPositiveButton("添加", null)
                .setNegativeButton("取消", null)
                .create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String nw = w.getText().toString().trim();
            String np = p.getText().toString().trim();
            if (nw.isEmpty()) {
                Toast.makeText(a, "词条不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            int nc;
            try { nc = Integer.parseInt(c.getText().toString().trim()); }
            catch (Exception ex) { nc = 50; }
            if (nc <= 0) nc = 50;
            d.dismiss();
            applyAdd(nw, np, nc);
        });
    }

    /** 保存修改：导出 → 改这一条 → 全量导入 → 重启词库 */
    private static void applyEdit(final Entry e, final String newWord,
                                  final String newPinyin, final int newCommits) {
        if (busy) { toast("正在处理上一个操作，请稍候"); return; }
        busy = true;
        toast("保存中…");
        new Thread(() -> {
            try {
                File dir = new File(XiaobuInject.ctx.getFilesDir(), "wtsync");
                dir.mkdirs();
                File f = new File(dir, "ud_edit.json");
                if (!XiaobuPhraseStore.exportUserDict(f.getAbsolutePath())) {
                    fail("导出词库失败"); return;
                }
                String json = new String(Files.readAllBytes(f.toPath()), "UTF-8");
                String raw = e.en ? (newWord + "(" + newWord + ")") : (newWord + "(" + newPinyin + ")");
                String patched = patchEntry(json, e.rawWord, raw, newCommits);
                if (patched == null) { fail("没有在词库里定位到该词条"); return; }
                Files.write(f.toPath(), patched.getBytes("UTF-8"));
                if (!XiaobuPhraseStore.importUserDict(f.getAbsolutePath())) {
                    fail("写入词库失败"); return;
                }
                XiaobuPhraseStore.restartVocabulary();
                Thread.sleep(1200);
                MainHookLogger.log("event=udpage_edit ok word=" + newWord + " commits=" + newCommits);
                busy = false;
                ui(UserDictPage::reload);
            } catch (Throwable t) {
                MainHookLogger.log("event=udpage_edit_err " + t);
                fail("保存失败：" + t.getMessage());
            }
        }, "WtUdEdit").start();
    }

    /** 新增：导出 → 往对应数组末尾追加 → 全量导入 → 重启词库 */
    private static void applyAdd(final String word, final String pinyin, final int commits) {
        if (busy) { toast("正在处理上一个操作，请稍候"); return; }
        busy = true;
        toast("添加中…");
        new Thread(() -> {
            try {
                File dir = new File(XiaobuInject.ctx.getFilesDir(), "wtsync");
                dir.mkdirs();
                File f = new File(dir, "ud_add.json");
                if (!XiaobuPhraseStore.exportUserDict(f.getAbsolutePath())) {
                    fail("导出词库失败"); return;
                }
                String json = new String(Files.readAllBytes(f.toPath()), "UTF-8");
                boolean cjk = hasCjk(word);
                String raw = cjk ? (word + "(" + pinyin + ")") : (word + "(" + word + ")");
                long ts = System.currentTimeMillis() / 1000;
                StringBuilder sb = new StringBuilder("{\"word\":\"").append(esc(raw))
                        .append("\",\"type\":1,\"timestamp\":").append(ts).append(",\"commits\":").append(commits);
                if (cjk) {
                    sb.append(",\"commits_by_spelling\":{\"")
                      .append(esc(pinyin.replace(' ', '_'))).append("\":").append(commits).append("}");
                }
                sb.append("}");
                String merged = insertIntoArray(json, cjk ? "cn_words" : "en_words", sb.toString());
                if (merged == null) { fail("词库结构异常，无法追加"); return; }
                Files.write(f.toPath(), merged.getBytes("UTF-8"));
                if (!XiaobuPhraseStore.importUserDict(f.getAbsolutePath())) {
                    fail("写入词库失败"); return;
                }
                XiaobuPhraseStore.restartVocabulary();
                Thread.sleep(1200);
                MainHookLogger.log("event=udpage_add ok word=" + word + " commits=" + commits);
                busy = false;
                ui(UserDictPage::reload);
            } catch (Throwable t) {
                MainHookLogger.log("event=udpage_add_err " + t);
                fail("添加失败：" + t.getMessage());
            }
        }, "WtUdAdd").start();
    }

    /** 在 JSON 里把 oldRaw 的词条替换成 newRaw，并把 commits 改成 newCommits */
    static String patchEntry(String json, String oldRaw, String newRaw, int newCommits) {
        int i = json.indexOf("\"word\":\"" + esc(oldRaw) + "\"");
        if (i < 0) return null;
        int vs = i + 8;                       // 指向 word 值的起始引号之后
        int ve = vs;
        while (ve < json.length()) {
            char c = json.charAt(ve);
            if (c == '\\') { ve += 2; continue; }
            if (c == '"') break;
            ve++;
        }
        String out = json.substring(0, vs) + esc(newRaw) + json.substring(ve);

        // commits 在本对象内，位置在 word 之后
        int cs = out.indexOf("\"commits\":", vs + esc(newRaw).length());
        if (cs < 0) return null;
        int nvs = cs + 10;
        int nve = nvs;
        while (nve < out.length() && (out.charAt(nve) == '-' || Character.isDigit(out.charAt(nve)))) nve++;
        if (nve == nvs) return null;
        return out.substring(0, nvs) + newCommits + out.substring(nve);
    }

    /** 往 language_dict.<key> 数组末尾追加一个对象字面量 */
    static String insertIntoArray(String json, String key, String item) {
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int arr = json.indexOf('[', k);
        if (arr < 0) return null;
        int depth = 0;
        boolean inStr = false;
        int close = -1;
        for (int i = arr; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) { close = i; break; }
            }
        }
        if (close < 0) return null;
        boolean empty = json.substring(arr + 1, close).trim().isEmpty();
        return json.substring(0, close) + (empty ? "" : ",") + item + json.substring(close);
    }

    // ---------------- 小工具 ----------------

    private static void ui(Runnable r) {
        if (act == null) return;
        act.runOnUiThread(() -> {
            try { r.run(); } catch (Throwable ignore) {}
        });
    }

    private static void toast(final String s) {
        ui(() -> { if (act != null) Toast.makeText(act, s, Toast.LENGTH_SHORT).show(); });
    }

    private static void fail(final String s) {
        busy = false;
        MainHookLogger.log("event=udpage_fail " + s);
        toast(s);
        ui(() -> { if (statView != null) statView.setText(s); });
    }

    private static TextView tip(Context ctx, String s) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(12);
        t.setPadding(0, dp(ctx, 10), 0, dp(ctx, 2));
        int c = attrColor(ctx, android.R.attr.textColorSecondary, 0xFF888888);
        t.setTextColor(c);
        return t;
    }

    private static View divider(Context ctx, boolean dark) {
        View v = new View(ctx);
        // 与宿主设置页同一观感：1px 实线，左端留出内缩
        v.setBackgroundColor(dark ? 0x30FFFFFF : 0x1A000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(ctx, 1f)));
        lp.leftMargin = dp(ctx, 16);
        lp.rightMargin = dp(ctx, 16);
        v.setLayoutParams(lp);
        return v;
    }

    /** 词条归到哪个首字母分组（中文用拼音首字母，英文用词首字母） */
    private static String letterOf(Entry e) {
        String s = e.en ? e.word : e.pinyin;
        if (s == null || s.isEmpty()) return "#";
        char c = Character.toUpperCase(s.charAt(0));
        return (c >= 'A' && c <= 'Z') ? String.valueOf(c) : "#";
    }

    private static GradientDrawable roundBg(int color, int radiusDp, Context ctx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(ctx, radiusDp));
        return d;
    }

    static int attrColor(Context ctx, int attr, int def) {
        try {
            TypedValue tv = new TypedValue();
            if (ctx.getTheme().resolveAttribute(attr, tv, true)) {
                if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return tv.data;
                }
                if (tv.resourceId != 0) return ctx.getResources().getColor(tv.resourceId, ctx.getTheme());
            }
        } catch (Throwable ignore) {}
        return def;
    }

    private static float luminance(int c) {
        return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
    }

    /** 真正的深浅色判断：看系统夜间模式，而不是主题里的 colorBackground */
    static boolean isNight(Context ctx) {
        try {
            int m = ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return m == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            Character.UnicodeBlock b = Character.UnicodeBlock.of(s.charAt(i));
            if (b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) return true;
        }
        return false;
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private UserDictPage() {}
}
