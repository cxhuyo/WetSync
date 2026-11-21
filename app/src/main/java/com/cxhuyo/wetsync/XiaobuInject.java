package com.cxhuyo.wetsync;

import java.lang.reflect.Method;

/** 小布输入法侧注入：剪贴板记录 + 系统剪贴板（带防回环标记） */
public final class XiaobuInject {
    public static final String MARK = "cx.synced";
    private static Object clipManagerInstance; // com.oplus.keyboard.input.function.e (原 ClipboardManager)
    private static Method insertMethod;
    /** 上一次做过 URI 读授权的 content://，重试同一张图时不再全量授权 */
    private static volatile String lastGrantedUri = null;

    /** onPackageReady 时调用：定位 R8 改名后的 ClipboardManager(object e) 与写入方法 */
    public static void locate(ClassLoader cl) {
        try {
            Class<?> mgr = cl.loadClass("com.oplus.keyboard.input.function.e");
            for (java.lang.reflect.Field f : mgr.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && f.getType().getName().equals(mgr.getName())) {
                    f.setAccessible(true);
                    clipManagerInstance = f.get(null);
                }
            }
            if (clipManagerInstance == null) {
                Method inst = mgr.getDeclaredMethod("a"); // Kotlin object INSTANCE getter 常见名
                inst.setAccessible(true);
                clipManagerInstance = inst.invoke(null);
            }
            for (Method m : mgr.getDeclaredMethods()) {
                if (m.getName().equals("insertOrUpdateClipboardData")) {
                    insertMethod = m;
                    break;
                }
            }
            MainHookLogger.log("event=xiaobu_locate mgr=" + (clipManagerInstance != null)
                    + " insert=" + (insertMethod != null));
        } catch (Throwable t) {
            MainHookLogger.log("event=xiaobu_locate_fail " + t);
        }
    }

    /** 注入一条剪贴板记录：优先走宿主自己的 DAO（复用 Room 实例），失败回退系统剪贴板 */
    public static void injectClipboard(String text) {
        if (text == null || text.isEmpty()) return;
        boolean ok = false;
        try {
            if (clipManagerInstance != null && insertMethod != null) {
                Class<?> entityCls = Class.forName("com.oplus.keyboard.db.clipboard.a",
                        true, clipManagerInstance.getClass().getClassLoader());
                Object entity = entityCls.getDeclaredConstructor().newInstance();
                for (java.lang.reflect.Field f : entityCls.getDeclaredFields()) {
                    f.setAccessible(true);
                    if (f.getType() == String.class && f.get(entity) == null) { f.set(entity, text); break; }
                }
                insertMethod.invoke(clipManagerInstance, entity);
                ok = true;
            }
        } catch (Throwable t) {
            MainHookLogger.log("event=inject_db_fail " + t);
        }
        setSystemClipboard(text);
        MainHookLogger.log("event=clip_inject db=" + ok);
    }

    /**
     * 写系统剪贴板（带 cx.synced 标记防回环）。
     *
     * @return true = 已经写进去了（或剪贴板里本来就是这段内容）；false = 写不进去，
     *         调用方应挂进「待写队列」等输入法窗口出现再补写。
     *         注意：读回校验在后台可能被拒（getPrimaryClip 返回 null），此时会返回 false，
     *         属于**假失败** —— 所以补写队列必须做到幂等，重复写同一内容不出问题。
     */
    public static boolean setSystemClipboard(String text) {
        try {
            if (ctx == null || text == null) return false;
            // 幂等闸门：剪贴板里已经是同一段文字，就直接返回，不再 setPrimaryClip。
            // 每次写剪贴板都会刷新 ClipDescription.timestamp，而时间戳是本模块判断
            // 「用户又复制了一次」的唯一可靠信号 —— 把同样的内容反复写回去，等于自己
            // 伪造一次新复制：发送侧再上报一遍、接收侧再写一遍，形成自激循环。
            // 放在最底层，所有写入口（远端文本、COS 文本、键盘唤醒补写）一并受保护。
            String curNow = currentClipText();
            if (curNow != null && curNow.equals(text)) {
                MainHookLogger.log("event=clip_write_same len=" + text.length());
                return true;
            }
            android.content.ClipData cd = android.content.ClipData.newPlainText("wetypesync", text);
            android.content.ClipDescription desc = cd.getDescription();
            android.os.PersistableBundle ex = new android.os.PersistableBundle();
            ex.putBoolean(MARK, true);
            desc.setExtras(ex);
            android.content.ClipboardManager cm = clipboard();
            if (cm == null) return false;
            cm.setPrimaryClip(cd);
            // 文本写入同样可能被 WRITE_CLIPBOARD=foreground 静默吞掉，读回确认一次
            boolean applied = false;
            try {
                android.content.ClipData back = cm.getPrimaryClip();
                applied = back != null && back.getItemCount() > 0
                        && text.contentEquals(String.valueOf(back.getItemAt(0).getText()));
            } catch (Throwable ignore) {}
            if (!applied) {
                MainHookLogger.log("event=clip_write_text applied=false len=" + text.length());
            } else {
                // 写成功原来一行日志都没有 → 排查「剪贴板被谁改了」时分不清是模块还是外部进程。
                MainHookLogger.log("event=clip_write_text ok len=" + text.length());
            }
            return applied;
        } catch (Throwable t) {
            MainHookLogger.log("event=set_clip_fail " + t);
            return false;
        }
    }

    /**
     * 写图片到系统剪贴板（带 cx.synced 标记防回环）。
     *
     * 这里刻意不用 ClipData.newUri：它内部靠 ContentResolver.getType(uri) 推断 MIME，
     * 对 file:// 会返回 null，于是 ClipDescription 不带任何 MIME，
     * 小布那边 hasMimeType("image/*") 就判定失败、走文本分支。
     *
     * 改用两个公开 API 显式造：
     *   ClipDescription(label, String[]{mime})   ← 直接把 MIME 写死
     *   ClipData(ClipDescription, Item)
     * 绕开隐藏 API setMimeTypeCount/setMimeType（普通应用调不了）。
     *
     * URI 传 file:// 指向小布自己的私有目录：剪贴板里是同进程 URI，
     * 小布 a$a.a() 会 openInputStream 读它、复制进自己的图片目录、写库、刷新面板。
     */
    public static boolean setSystemClipboardUri(android.net.Uri uri, String mime, String name) {
        try {
            if (ctx == null || uri == null) return false;
            // 关键：先把这个 content:// 的读权限授予可能的接收方。
            // 微信实测 appops 显示 READ_MEDIA_VISUAL_USER_SELECTED: allow，
            // 即它处于 Android 14+ 的「部分照片访问」模式——此时 MediaProvider 只允许
            // 读用户手动选中的照片，我们新写进 MediaStore 的图不在授权集合里，
            // 微信 openInputStream 拿不到字节 → 粘贴不出图。
            // 显式的 URI grant 优先于媒体库的权限检查，可以绕过这个限制。
            //
            // 只对「新 URI」授权一次：写失败会按 2 秒节奏重试几十次，
            // 每次都全量 grantUriPermission（几百次 IPC）会把输入法拖死。
            String uriKey = uri.toString();
            if (!uriKey.equals(lastGrantedUri)) {
                lastGrantedUri = uriKey;
                grantReadToAll(uri);
            }
            // 幂等闸门（图片版）：剪贴板里已经是同一个 URI 就不再写。
            // 典型触发点：待写队列每 2 秒重试一次、键盘每次亮起补写一次、模块进程重启后
            // 重放同一条 —— 这些都不该刷新时间戳，否则发送侧会把这张图当成「又复制了一次」。
            // 授权放在闸门之前，保证「跳过写入」时读权限仍是新的（重启后 grant 会失效）。
            try {
                android.content.ClipboardManager cmPrev = clipboard();
                android.content.ClipData cdPrev =
                        (cmPrev != null && cmPrev.hasPrimaryClip()) ? cmPrev.getPrimaryClip() : null;
                if (cdPrev != null && cdPrev.getItemCount() > 0
                        && uri.equals(cdPrev.getItemAt(0).getUri())) {
                    MainHookLogger.log("event=clip_write_uri_same uri=" + uri);
                    return true;
                }
            } catch (Throwable ignore) {}
            String m = (mime == null || mime.isEmpty()) ? "image/jpeg" : mime;
            String label = name == null ? "wetypesync" : name;
            String resolved = resolverType(uri);
            android.content.ClipData cd;
            if (resolved != null && resolved.startsWith("image/")) {
                // 官方工厂：截图/相册/浏览器等一切能正常「复制图片」的 app 都用它造 clip，
                // 内部按 resolver.getType 填 MIME 并携带系统期望的隐含状态。
                // （旧注释里「newUri 对 file:// 推断出 null MIME」的问题已不存在——现在统一走
                //   MediaStore 的 content://，getType 一定返回 image/jpeg，先 verify 再用。）
                cd = android.content.ClipData.newUri(ctx.getContentResolver(), label, uri);
            } else {
                // 兜底：resolver 没给出 image MIME（异常情形）时显式指定，保证 hasMimeType("image/*") 判定不失效
                android.content.ClipDescription fb = new android.content.ClipDescription(
                        label, new String[]{m});
                android.os.PersistableBundle fex = new android.os.PersistableBundle();
                fex.putBoolean(MARK, true);
                fb.setExtras(fex);
                cd = new android.content.ClipData(fb, new android.content.ClipData.Item(uri));
            }
            // 防回环标记（newUri 路径同样要打，否则这张图会被误当成「本地新复制」推回 PC）
            try {
                android.os.PersistableBundle ex = new android.os.PersistableBundle();
                ex.putBoolean(MARK, true);
                cd.getDescription().setExtras(ex);
            } catch (Throwable ignore) {}
            android.content.ClipboardManager cm = clipboard();
            if (cm == null) return false;

            // ClipData.Item.getUri() 对 file:// 会走 StrictMode 的 VmPolicy 检查，
            // 抛 FileUriExposedException。这里在 setPrimaryClip 的瞬间临时放行，
            // 调用一返回就恢复原策略，缩小影响面。
            android.os.StrictMode.VmPolicy old = android.os.StrictMode.getVmPolicy();
            boolean applied;
            try {
                android.os.StrictMode.setVmPolicy(
                        new android.os.StrictMode.VmPolicy.Builder().build());
                cm.setPrimaryClip(cd);
            } finally {
                android.os.StrictMode.setVmPolicy(old);
            }
            // 写后立即读回校验：Android 10+ 只允许「默认 IME 处于活动输入状态」或前台 app
            // 写剪贴板，不满足时 setPrimaryClip 会静默失败（不抛异常）。必须读回比对，
            // 否则日志里永远是 clip=true 但剪贴板根本没变。
            try {
                android.content.ClipData back = cm.getPrimaryClip();
                applied = back != null && back.getItemCount() > 0
                        && uri.equals(back.getItemAt(0).getUri());
            } catch (Throwable te) {
                applied = false;
                MainHookLogger.log("event=clip_verify_err " + te);
            }
            MainHookLogger.log("event=clip_write uri=" + uri + " applied=" + applied
                    + " mime=" + m + " resolved=" + resolved);
            // 写失败最常见的原因就是 WRITE_CLIPBOARD 被限制成 foreground（键盘收着）。
            // 这时不硬重试到底，交给 WtClient 的待写队列：等输入法窗口出现再补写。
            return applied;
        } catch (Throwable t) {
            MainHookLogger.log("event=set_clip_uri_fail " + t);
            return false;
        }
    }

    /** 读 MediaStore URI 的真实 MIME（仅诊断用，失败返回 null） */
    private static String resolverType(android.net.Uri uri) {
        try { return ctx.getContentResolver().getType(uri); } catch (Throwable t) { return null; }
    }

    /**
     * 把 content:// URI 的读权限授予所有已安装的 app。
     *
     * 为什么是「所有」：粘贴目标由用户决定，事先无从得知包名，只能全量授权。
     * 高优先级的一批（微信/QQ/短信/浏览器等）先授，剩下的遍历兜底；
     * 每个都 try-catch——某个包名失败（provider 不支持 grant / 未安装）不影响其余。
     * 调用方应在后台线程调用，避免几百次 IPC 卡住主线程。
     */
    private static final String[] PRIORITY_TARGETS = {
            "com.tencent.mm",                 // 微信
            "com.tencent.mobileqq",           // QQ
            "com.tencent.tim",
            "com.tencent.wework",             // 企业微信
            "com.android.mms",                // 短信
            "com.android.chrome",
            "com.android.browser",
            "com.sina.weibo",
            "com.alibaba.android.rimet",      // 钉钉
            "com.ss.android.lark",            // 飞书
            "com.oplus.note",
            "com.coloros.note",
    };

    private static void grantReadToAll(final android.net.Uri uri) {
        if (ctx == null || uri == null) return;
        final int flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;
        final android.content.Context app = ctx;
        // 高优先级目标同步授权：微信等最常粘贴的 app，保证 setPrimaryClip 前已就绪
        for (String pkg : PRIORITY_TARGETS) {
            try { app.grantUriPermission(pkg, uri, flags); } catch (Throwable ignore) {}
        }
        // 其余已安装 app 走后台线程兜底（几百次 IPC 不能卡住调用方）
        new Thread(() -> {
            try {
                java.util.List<android.content.pm.ApplicationInfo> apps =
                        app.getPackageManager().getInstalledApplications(0);
                if (apps != null) {
                    for (android.content.pm.ApplicationInfo ai : apps) {
                        try { app.grantUriPermission(ai.packageName, uri, flags); }
                        catch (Throwable ignore) {}
                    }
                }
            } catch (Throwable t) {
                MainHookLogger.log("event=grant_err " + t);
            }
        }, "WtGrant").start();
    }

    /** 缓存 ClipboardManager 实例（2s 轮询 + 复制推送都会走到，省掉反复 getSystemService） */
    private static android.content.ClipboardManager sCm;
    private static android.content.ClipboardManager clipboard() {
        if (sCm == null && ctx != null) {
            try {
                sCm = (android.content.ClipboardManager)
                        ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            } catch (Throwable t) { return null; }
        }
        return sCm;
    }

    /**
     * 当前剪贴板条目的「来源指纹」：label/self|ext。
     * 用来分辨「时间戳变了但内容没变」到底是本模块写的，还是别的进程（跨屏互联、
     * 宿主面板重复制……）改的 —— 这也是判定重复上报来源的唯一现场证据。
     */
    public static String currentClipDesc() {
        try {
            android.content.ClipboardManager cm = clipboard();
            if (cm == null || !cm.hasPrimaryClip()) return "none";
            android.content.ClipData cd = cm.getPrimaryClip();
            if (cd == null) return "none";
            android.content.ClipDescription d = cd.getDescription();
            if (d == null) return "nodesc";
            boolean mark = false;
            try {
                android.os.PersistableBundle ex = d.getExtras();
                mark = ex != null && ex.getBoolean(MARK, false);
            } catch (Throwable ignore) {}
            return d.getLabel() + "/" + (mark ? "self" : "ext");
        } catch (Throwable t) { return "err"; }
    }

    public static boolean isSynced(android.content.ClipDescription d) {
        android.os.PersistableBundle ex = d != null ? d.getExtras() : null;
        return ex != null && ex.getBoolean(MARK, false);
    }
    static android.content.Context ctx;
    public static void bindContext(android.content.Context c) { ctx = c.getApplicationContext(); }

    /** 读取当前系统剪贴板纯文本 */
    /**
     * 当前剪贴板「被赋值」的时间戳（ClipDescription.getTimestamp，毫秒）。
     *
     * 这是判断「用户又复制了一次」的唯一可靠信号：同一段文字被重新复制时内容不变，
     * 但时间戳一定会变；没有它就只能拿字符串比较，于是「IME 没跑时复制的内容」
     * 在启动后会被当成基线、再次复制同内容也发不出去（2026-11-08 实测反馈）。
     *
     * @return 0 表示读不到（无剪贴板 / 无权限 / 无时间戳）
     */
    public static long currentClipTs() {
        try {
            if (ctx == null) return 0;
            android.content.ClipboardManager cm = clipboard();
            if (cm == null || !cm.hasPrimaryClip()) return 0;
            android.content.ClipData cd = cm.getPrimaryClip();
            if (cd == null) return 0;
            android.content.ClipDescription d = cd.getDescription();
            return d == null ? 0 : d.getTimestamp();
        } catch (Throwable t) { return 0; }
    }

    public static String currentClipText() {
        try {
            if (ctx == null) return null;
            android.content.ClipboardManager cm = clipboard();
            if (cm == null || !cm.hasPrimaryClip()) return null;
            android.content.ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return null;
            android.content.ClipData.Item it = cd.getItemAt(0);
            // 关键：绝不能对 URI 项调用 coerceToText()。
            // 对 content:// 它会 openTypedAssetFileDescriptor 把文件流按 UTF-8 解码：
            // 一张 9KB 的 JPEG 会被读成 8000 多个乱码字符，被当成「本地复制的文本」
            // 上报到服务端；下一轮轮询又把它当「远端剪贴板文本」写回 —— 刚写好的
            // 图片剪贴板在 3~12 秒内就被自己冲掉，微信粘贴时只看到 text/plain 乱码，
            // 表现为「点粘贴毫无反应」。图片有独立通道（clipimg / handleIncomingImage），
            // 这里只取真正的文本项。
            CharSequence t = it.getText();
            if (t == null) t = it.getHtmlText();
            if (t == null) return null;
            return t.toString();
        } catch (Throwable t) { return null; }
    }
}
