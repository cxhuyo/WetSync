package com.cxhuyo.wtsync;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 一站式配置页：账号凭证 + 匹配码关联设备 + 连接开关。
 * 配置通过广播下发给小布输入法进程内的 WtClient（模块在宿主进程中注册接收器落盘）。
 */
public class SettingsActivity extends Activity {

    private EditText etUin, etGroup, etToken, etBindCode;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("微信输入法同步 · 小布版");
        title.setTextSize(20);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        tvStatus = new TextView(this);
        tvStatus.setText("状态: (等待宿主进程上报)");
        tvStatus.setTextColor(Color.DKGRAY);
        root.addView(tvStatus);

        root.addView(label("UIN（账号数字 id）"));
        etUin = input(); root.addView(etUin);
        root.addView(label("Group ID（设备组）"));
        etGroup = input(); root.addView(etGroup);
        root.addView(label("User Token（登录凭证）"));
        etToken = input(); root.addView(etToken);

        Button save = new Button(this);
        save.setText("保存并下发到小布输入法");
        save.setOnClickListener(v -> {
            Intent i = new Intent("com.cxhuyo.wtsync.CONFIG");
            i.setPackage("com.oplus.keyboard");
            i.putExtra("uin", etUin.getText().toString().trim());
            i.putExtra("group", etGroup.getText().toString().trim());
            i.putExtra("token", etToken.getText().toString().trim());
            sendBroadcast(i);
            Toast.makeText(this, "已下发（小布进程需存活）", Toast.LENGTH_SHORT).show();
        });
        root.addView(save);

        root.addView(label("匹配码关联设备"));
        etBindCode = input();
        etBindCode.setHint("输入 PC 端显示的匹配码");
        root.addView(etBindCode);
        Button bind = new Button(this);
        bind.setText("发起关联（匹配码）");
        bind.setOnClickListener(v -> {
            String code = etBindCode.getText().toString().trim();
            if (code.isEmpty()) { Toast.makeText(this, "先输入匹配码", Toast.LENGTH_SHORT).show(); return; }
            Intent i = new Intent("com.cxhuyo.wtsync.BIND");
            i.setPackage("com.oplus.keyboard");
            i.putExtra("code", code);
            sendBroadcast(i);
            Toast.makeText(this, "绑定请求已提交", Toast.LENGTH_SHORT).show();
        });
        root.addView(bind);

        TextView note = new TextView(this);
        note.setText("说明:\n· 保存后凭证写入小布私有目录, 不依赖微信输入法\n"
                + "· 匹配码绑定协议对齐官方: 提交后由服务器下发 SyncBindSuccess 推送完成入组\n"
                + "· 独立登录(无 token 引导)为后续版本");
        note.setTextSize(12);
        note.setTextColor(Color.GRAY);
        root.addView(note);

        setContentView(root);
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                tvStatus.setText("状态: " + i.getStringExtra("msg"));
            }
        }, new android.content.IntentFilter("com.cxhuyo.wtsync.STATUS_BACK"),
           android.content.Context.RECEIVER_EXPORTED);
        requestStatus();
    }

    private void requestStatus() {
        Intent i = new Intent("com.cxhuyo.wtsync.STATUS");
        i.setPackage("com.oplus.keyboard");
        sendBroadcast(i);
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, (int) (getResources().getDisplayMetrics().density * 12), 0, 4);
        t.setTextColor(Color.BLACK);
        return t;
    }

    private EditText input() {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextSize(14);
        return e;
    }
}
