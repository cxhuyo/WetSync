package com.cxhuyo.wetsync;

import android.app.Application;

/**
 * 只做一件事：尽早注册 libxposed service 监听。
 *
 * 管理器把 binder 递过来是异步的，放在 Activity.onCreate 里注册会晚一拍：
 * 首次打开模块主页时经常先看到「检测中」，要等下一次回调才变成已激活。
 */
public class WetSyncApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        FrameworkStatus.init(this);
    }
}
