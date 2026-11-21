package com.cxhuyo.wetsync;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 独立的「关于」页面。
 *
 * 单独成 Activity 是为了让系统手势返回（含 Android 13+ 的预测性返回动画）
 * 走系统默认路径，而不是在主页里做视图切换。
 *
 * 内容与 XiaoBu-Swipe 的关于页一致（关于本模块 / 赞赏支持 / 版本尾注），
 * 按本模块的 COUI 配色重绘，深色浅色都跟着系统走。
 */
public final class AboutActivity extends Activity {

    private int cBg, cCard, cText, cSub, cDivider, cAccent;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        cBg = getColor(R.color.bg);
        cCard = getColor(R.color.card);
        cText = getColor(R.color.text);
        cSub = getColor(R.color.sub);
        cDivider = getColor(R.color.divider);
        cAccent = getColor(R.color.accent);
        setTitle("关于");
        configureWindow();
        setContentView(buildPage());
    }

    private void configureWindow() {
        Window w = getWindow();
        w.setStatusBarColor(cCard);
        w.setNavigationBarColor(cCard);
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int flags = 0;
        if (!night) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flags);
    }

    private View buildPage() {
        LinearLayout page = vertical();
        page.setBackgroundColor(cBg);

        page.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);

        LinearLayout content = vertical();
        content.setPadding(dp(12), dp(12), dp(12), dp(22));
        scroll.addView(content);

        content.addView(buildIntroCard());
        content.addView(buildDonateCard());

        TextView footer = text(versionLabel() + " · 感谢使用", 12, cSub);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(2), 0, dp(18));
        content.addView(footer);

        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        applySystemBarInsets(page);
        scroll.post(() -> scroll.scrollTo(0, 0));
        return page;
    }

    private View buildTopBar() {
        LinearLayout bar = horizontal();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(16), dp(6));
        bar.setBackgroundColor(cCard);
        bar.setElevation(dp(1));

        TextView back = text("‹", 30, cText);
        back.setGravity(Gravity.CENTER);
        back.setMinWidth(dp(44));
        back.setMinHeight(dp(44));
        back.setClickable(true);
        back.setFocusable(true);
        back.setContentDescription("返回");
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView title = text("关于", 19, cText);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = wrap();
        titleParams.leftMargin = dp(4);
        bar.addView(title, titleParams);
        return bar;
    }

    private View buildIntroCard() {
        LinearLayout card = createCard("关于本模块",
                "微信输入法 ⇄ 小布输入法 · LSPosed 模块");
        TextView declaration = text("模块完全免费，禁止倒卖。", 15, cText);
        declaration.setTypeface(Typeface.DEFAULT_BOLD);
        declaration.setPadding(dp(16), dp(2), dp(16), dp(6));
        card.addView(declaration);

        TextView note = text("如果你是通过付费渠道获取的，说明遇到了倒卖，请直接使用本免费版本。",
                12, cSub);
        note.setPadding(dp(16), 0, dp(16), dp(16));
        card.addView(note);
        return card;
    }

    private View buildDonateCard() {
        LinearLayout card = createCard("赞赏支持", null);
        ImageView code = new ImageView(this);
        code.setImageResource(R.drawable.donate_code);
        code.setScaleType(ImageView.ScaleType.FIT_CENTER);
        code.setContentDescription("赞赏码");
        code.setClipToOutline(true);
        code.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
            }
        });
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190));
        codeParams.setMargins(dp(16), dp(2), dp(16), dp(10));
        card.addView(code, codeParams);

        TextView thanks = text("您的支持将是维护模块的最大动力₍ᐢ..ᐢ₎♡谢谢！！", 14, cText);
        thanks.setTypeface(Typeface.DEFAULT_BOLD);
        thanks.setGravity(Gravity.CENTER);
        thanks.setPadding(dp(16), dp(2), dp(16), dp(6));
        card.addView(thanks);

        TextView saveTip = text("长按图片可保存到相册，再用支付宝扫一扫。", 12, cSub);
        saveTip.setGravity(Gravity.CENTER);
        saveTip.setPadding(dp(16), 0, dp(16), dp(16));
        card.addView(saveTip);
        return card;
    }

    private String versionLabel() {
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return "v" + name;
        } catch (PackageManager.NameNotFoundException ignored) {
            return "当前安装版本";
        }
    }

    private void applySystemBarInsets(View root) {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(0, top, 0, bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private LinearLayout createCard(String title, String subtitle) {
        LinearLayout card = vertical();
        card.setBackground(rounded(cCard, 16));
        card.setElevation(dp(1));
        card.setClipToOutline(true);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(12);
        card.setLayoutParams(cardParams);

        LinearLayout header = vertical();
        header.setPadding(dp(16), dp(14), dp(16), subtitle == null ? dp(10) : dp(11));
        TextView titleView = text(title, 18, cText);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(titleView);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subtitleView = text(subtitle, 12, cSub);
            LinearLayout.LayoutParams subtitleParams = wrap();
            subtitleParams.topMargin = dp(4);
            header.addView(subtitleView, subtitleParams);
        }
        card.addView(header);
        return card;
    }

    private GradientDrawable rounded(int fillColor, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fillColor);
        background.setCornerRadius(dp(radiusDp));
        return background;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
