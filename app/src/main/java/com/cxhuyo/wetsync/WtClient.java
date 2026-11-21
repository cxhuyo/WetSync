package com.cxhuyo.wetsync;

import java.io.File;
import java.io.FileInputStream;
import java.math.BigInteger;

/**
 * 同步引擎：身份 → 握手 → 轮询(拉取/发布剪贴板) + 双向配对。
 *
 * 配对两条路：
 *  A) 本机出码：cmd30 申请六位码 → cmd40 轮询 → 有人加入后 cmd32 确认。
 *  B) 输入对端码：cmd31 join → cmd38 轮询直至 Complete。
 */
public final class WtClient {

    public static volatile WtClient active;

    private volatile boolean running = true;
    private Thread loop;
    private final WxClient wx = new WxClient();

    private long ownUin = 0;
    private long groupId = -1;
    private long lastVersion = 0;
    private String lastLocalText = null;

    // ── 内容指纹去重（防回环 / 防重复上报）────────────────────────
    // lastHash   ：最近一次「已经确认过」的内容（收到的、或发出去的）
    // lastTxHash ：最近一次**成功发布**的内容
    // 两者都用带前缀的 MD5：t:文本 / i:图片
    private String lastHash = null;
    private String lastTxHash = null;
    /** 指纹对应的时间，用于 TTL：超过 10 分钟的同内容视为「用户重新复制」，允许再同步一次 */
    private long lastHashAt = 0L;
    private long lastTxAt = 0L;
    private static final long DEDUP_TTL_MS = 10 * 60 * 1000L;
    /** 同一内容在 2 秒内只发一次：防止「内容变 + 时间戳变」同时命中造成双发，不影响用户回头再复制 */
    private static final long SELF_ECHO_MS = 2000L;
    /** 最近一次把远端内容写进本地剪贴板的时刻，用于抑制「写完立刻反弹」 */
    private long lastRxAt = 0L;
    private static final long RX_SUPPRESS_MS = 3000L;
    /** 状态落盘：版本水位 + 两个指纹，避免进程被杀重启后把服务端「当前剪贴板」重放一遍 */
    private static final String STATE_FILE = "clip_state.txt";
    private long lastStateSave = 0L;
    /** 首次运行（没有状态文件）时，第一轮只接受水位、不覆盖用户剪贴板 */
    private boolean coldAdopt = false;
    /** 上一次观察到的剪贴板时间戳（判定「又复制了一次」，内容相同也能识别） */
    private long lastSeenClipTs = 0L;
    /** 启动时从状态文件读到的「已处理过的剪贴板时间戳」——同一条复制事件不会被重复上报 */
    private long savedClipTs = 0L;
    /** 启动时补充上报：IME 未运行时复制的内容，等第一次连上补发一次 */
    private volatile boolean startupPending = false;
    /**
     * 键盘每次亮起时的「补发评估」标记。
     *
     * 为什么需要：键盘收起时 ColorOS 会把输入法进程整个冻结（cgroup.freeze=1），
     * 冻结期间 getPrimaryClip() 返回 null —— 用户在别的 app 里复制的内容，
     * 模块在这段时间里读不到、也记不下时间戳。键盘一显示进程解冻，
     * onWindowShown 立刻打上这个标记，让紧接着的那轮轮询优先判定一次，
     * 而不是干等 2 秒的轮询周期，更不会因为「什么都没变」被直接跳过。
     *
     * 它只负责「提前评估」，不绕过任何去重：是不是新的一次复制仍由时间戳决定，
     * 内容没变就什么都不发、也不回写剪贴板。
     */
    private volatile boolean imeCatchup = false;
    /**
     * 刚由「接收侧」写进剪贴板的内容指纹（只吞一次）。
     *
     * 为什么需要：键盘收着时写剪贴板往往读不回时间戳（lastSeenClipTs 只能记 0），
     * 等键盘亮起真正读到 ts 时，「时间戳变了」就会被判成用户又复制了一次，
     * 于是刚同步进来的内容又被原样发回对端 —— 用户看到的就是「同步过去又被同步回来」。
     * 这里记一笔「这条是收进来的」，下一次判定时吞掉；吞完即清，用户之后真去复制
     * 同样的内容仍然会正常上报。
     */
    private String rxEchoHash = null;
    /**
     * 「收进来了但写不进剪贴板」的待写文本（对应图片侧的 pendingBytes）。
     *
     * 为什么必须有：键盘收起时 WRITE_CLIPBOARD 是 foreground 语义，setPrimaryClip 会被
     * 静默丢弃。文本没有补写就会**永久丢失**，而且对端再复制同一段内容时服务端版本号
     * 往往不变（内容相同），模块连「重试」的机会都没有 —— 用户看到的就是
     * 「手机唤醒输入法后没同步过来，PC 再复制一次同样的内容也还是没同步」。
     *
     * 落盘保存（clip_pending.txt，base64 防换行/空白被 trim），进程被冻结/杀掉重启后
     * 仍然能补写。判定规则：内容已经在剪贴板里 → 成功；剪贴板里有更晚的用户内容 → 放弃。
     */
    private volatile String pendingText = null;
    private volatile long pendingTextVer = -1;
    private long pendingTextSince = 0L;
    private long nextTextTryAt = 0L;
    private static final String PENDING_TEXT_FILE = "clip_pending.txt";
    /** 待写文本的兜底有效期：真正的保护是「剪贴板里有更晚的内容就放弃」，这个只防无界堆积 */
    private static final long PENDING_TEXT_TTL_MS = 12 * 60 * 60 * 1000L;
    /**
     * 最近一次「从对端收到」的文本（落盘）。
     *
     * 为什么需要：Android 13+ 会在锁屏/一段时间后自动清空剪贴板，ColorOS 的跨屏互联
     * （com.oplus.linker）同步完也会 clearPrimaryClip。结果是：内容其实早就收到了、
     * 也写进过剪贴板，但用户真正去粘贴时剪贴板已经空了；而服务端版本号没变，
     * 下一轮不会再下发 —— 表现就是「手机唤醒输入法后还是没同步过来」。
     * 所以键盘亮起时补一次：剪贴板里没有它、且期间用户没复制别的东西 → 重新写回去。
     */
    private String lastRxText = null;
    private long lastRxTextAt = 0L;
    private static final String LAST_RX_FILE = "clip_lastrx.txt";
    /** 补写时效：超过就认为太旧，不再往空剪贴板里塞 */
    private static final long LAST_RX_TTL_MS = 12 * 60 * 60 * 1000L;
    /** 启动补发只针对「较新」的剪贴板内容，太旧（>30 分钟）视为历史残留，不上传 */
    private static final long STARTUP_MAX_AGE_MS = 30 * 60 * 1000L;
    /** 文本发布失败后重试的持续窗口：超过这个时间仍未成功就放弃，避免离线时每 2 秒打一次网络 */
    private static final long TX_RETRY_MAX_MS = 10 * 60 * 1000L;
    /** 本轮「发了但没成功」的起始时刻（0 = 当前没有待重试项） */
    private long txFailSince = 0L;
    /** [临时诊断] 上一次记录过的 clip_hdr 签名 / 设备组签名，只在变化时打日志 */
    private String diagClipSig = null;
    private String diagDevSig = null;
    private volatile boolean clipboardEnabled = true;
    private volatile String statusText = "未启动";
    private volatile boolean paired = false;
    /** 组功能开关位（服务端 GroupInfo.f5） */
    private volatile long funcMask = 0;
    private volatile String issueCode = null;
    private volatile String joinBindId = null;

    /** 二级页「跨设备复制粘贴」开关 */
    public static void setClipboardEnabled(boolean on) {
        WtClient c = active;
        if (c != null) c.clipboardEnabled = on;
        MainHookLogger.log("event=clip_enabled " + on);
    }

    public boolean isClipboardEnabled() { return clipboardEnabled; }

    public void start() {
        active = this;
        loop = new Thread(this::runLoop, "WtClient");
        loop.start();
    }

    public void stop() { running = false; }

    public static void restart() {
        WtClient old = active;
        if (old != null) old.stop();
        new WtClient().start();
    }


    public String status() { return statusText; }
    public boolean isPaired() { return paired; }
    public String issueCode() { return issueCode; }

    /** 设备组成员 */
    public static final class Dev {
        public final String name;
        public final long uin;
        public final boolean self;
        Dev(String n, long u, boolean s) { name = n; uin = u; self = s; }
    }

    private static volatile java.util.List<Dev> devs = java.util.Collections.emptyList();

    /** 当前设备组成员（本机在前），未入组返回空表 */
    public static java.util.List<Dev> devices() { return devs; }

    static File dir() {
        android.content.Context c = XiaobuInject.ctx;
        File base = c != null ? c.getFilesDir() : new File("/data/data/com.oplus.keyboard/files");
        File d = new File(base, "wtsync");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static long now() { return System.currentTimeMillis(); }

    /** 文本指纹：t:+MD5 前 8 字节（够用且短，便于落盘） */
    static String hashText(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder("t:");
            for (int i = 0; i < 8; i++) sb.append(String.format(java.util.Locale.US, "%02x", d[i]));
            return sb.toString();
        } catch (Throwable t) {
            return "t:" + Integer.toHexString(s.hashCode());
        }
    }

    /** 图片指纹：i:+像素级哈希（与 ClipImageStore 一致） */
    static String hashImage(byte[] b) { return "i:" + ClipImageStore.hashOf(b); }

    /** 内容指纹是否「刚刚同步过」（同一内容 10 分钟内只走一次，避免回环/刷屏） */
    private boolean recentDup(String h, String ref, long at) {
        return h != null && h.equals(ref) && at > 0 && (now() - at) < DEDUP_TTL_MS;
    }

    /** 仅诊断用：把关键去重状态拼成一行 */
    static String clipStateForDiag() {
        WtClient c = active;
        if (c == null) return "no_engine";
        return "ver=" + c.lastVersion + ",seenTs=" + c.lastSeenClipTs
                + ",savedTs=" + c.savedClipTs + ",pending=" + c.startupPending
                + ",cap=" + c.imeCatchup + ",txFail=" + c.txFailSince
                + ",ptext=" + (c.pendingText == null ? 0 : c.pendingText.length())
                + ",ptextVer=" + c.pendingTextVer
                + ",lrx=" + (c.lastRxText == null ? 0 : c.lastRxText.length())
                + ",tx=" + c.lastTxHash + ",rx=" + c.lastHash;
    }

    /** 仅诊断用：绕过本地剪贴板，直接向设备组发一条文本（验证自回声抑制） */
    static long publishForTest(String text) {
        WtClient c = active;
        if (c == null || c.groupId <= 0 || text == null || text.isEmpty()) return -1;
        try {
            return c.wx.publish(c.groupId, text, WxClient.randomHex(32));
        } catch (Throwable e) {
            MainHookLogger.log("event=publish_test_err " + e);
            return -1;
        }
    }

    /**
     * 载入持久化的同步状态。
     *
     * 为什么必须落盘：服务端每次下发的是「设备组当前剪贴板」这一个值（ver/type/from/data），
     * 不是消息队列。旧实现只把水位放在内存里，模块进程被 ColorOS 杀掉重启后 lastVersion 归零，
     * 于是每轮重启都会把「组里最后一次剪贴板」重新应用一遍 —— 实测 03:18:41、03:19:44 两次
     * 重启后重复 clip_rx ver=7657，用户正在复制的东西被旧内容覆盖。这就是「同步出去又被同步回来」。
     */
    private void loadClipState() {
        try {
            String s = read(STATE_FILE);
            if (s == null || s.isEmpty()) {
                coldAdopt = true;
                MainHookLogger.log("event=clip_state_new");
                return;
            }
            String[] p = s.split("\\|", -1);
            if (p.length > 0) {
                try { lastVersion = Long.parseLong(p[0].trim()); } catch (Throwable ignore) {}
            }
            if (p.length > 1 && !p[1].trim().isEmpty()) lastHash = p[1].trim();
            if (p.length > 2 && !p[2].trim().isEmpty()) lastTxHash = p[2].trim();
            if (p.length > 3) {
                try { lastHashAt = Long.parseLong(p[3].trim()); } catch (Throwable ignore) {}
            }
            if (p.length > 4) {
                try { lastTxAt = Long.parseLong(p[4].trim()); } catch (Throwable ignore) {}
            }
            if (p.length > 5) {
                try { savedClipTs = Long.parseLong(p[5].trim()); } catch (Throwable ignore) {}
            }
            // 第 7 段：收进来还没消化掉的内容指纹。必须落盘 —— 进程重启后
            // 「剪贴板里这段是收来的」这个事实不能忘，否则第一轮就会把它发回对端。
            if (p.length > 6 && !p[6].trim().isEmpty()) rxEchoHash = p[6].trim();
            MainHookLogger.log("event=clip_state_load ver=" + lastVersion
                    + " hash=" + lastHash + " tx=" + lastTxHash);
        } catch (Throwable t) {
            coldAdopt = true;
            MainHookLogger.log("event=clip_state_load_err " + t);
        }
    }

    private void saveClipState() {
        try {
            java.io.FileOutputStream fo =
                    new java.io.FileOutputStream(new File(dir(), STATE_FILE), false);
            String line = lastVersion + "|" + (lastHash == null ? "" : lastHash)
                    + "|" + (lastTxHash == null ? "" : lastTxHash)
                    + "|" + lastHashAt + "|" + lastTxAt + "|" + lastSeenClipTs
                    + "|" + (rxEchoHash == null ? "" : rxEchoHash);
            fo.write(line.getBytes("UTF-8"));
            fo.close();
            lastStateSave = now();
        } catch (Throwable t) {
            MainHookLogger.log("event=clip_state_save_fail " + t);
        }
    }

    // ── 待写文本：收进来了，但当时写不进剪贴板 ──────────────────────
    // 文本没有补写就等于永久丢失，且对端重复复制同一内容时服务端版本号往往不变，
    // 模块连重试的机会都没有。所以这里必须落盘保存（base64，避免 read() 的 trim 破坏内容）。

    private void markPendingText(String text, long ver) {
        if (text == null || text.isEmpty()) return;
        pendingText = text;
        pendingTextVer = ver;
        pendingTextSince = System.currentTimeMillis();
        nextTextTryAt = 0;
        savePendingText();
        MainHookLogger.log("event=clip_rx_pending ver=" + ver + " len=" + text.length());
    }

    private void saveLastRxText(String text) {
        lastRxText = text;
        lastRxTextAt = System.currentTimeMillis();
        try {
            String b64 = android.util.Base64.encodeToString(
                    text.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            java.io.FileOutputStream fo =
                    new java.io.FileOutputStream(new File(dir(), LAST_RX_FILE), false);
            fo.write((lastRxTextAt + "|" + b64).getBytes("UTF-8"));
            fo.close();
        } catch (Throwable t) {
            MainHookLogger.log("event=clip_lastrx_save_fail " + t);
        }
    }

    private void loadLastRxText() {
        try {
            String s = read(LAST_RX_FILE);
            if (s == null || s.isEmpty()) return;
            int i = s.indexOf('|');
            if (i <= 0) return;
            long at = Long.parseLong(s.substring(0, i).trim());
            String t = new String(android.util.Base64.decode(s.substring(i + 1).trim(),
                    android.util.Base64.NO_WRAP), "UTF-8");
            if (t.isEmpty()) return;
            lastRxText = t;
            lastRxTextAt = at;
            MainHookLogger.log("event=clip_lastrx_load age=" + (System.currentTimeMillis() - at)
                    + " len=" + t.length());
        } catch (Throwable t) {
            MainHookLogger.log("event=clip_lastrx_load_err " + t);
        }
    }

    /**
     * 键盘亮起时的「兜底补写」：把最近一次收到的文本重新放回剪贴板。
     *
     * 只处理一种情况：剪贴板里已经**没有**它（被系统自动清空 / 被 linker 同步后 clear），
     * 且这之后用户没有复制过别的内容。内容还在就直接返回 —— 绝不重复写、也不刷新时间戳。
     */
    private void restoreLastRx(String why) {
        String t = lastRxText;
        if (t == null) return;
        long nowMs = System.currentTimeMillis();
        if (lastRxTextAt <= 0 || nowMs - lastRxTextAt > LAST_RX_TTL_MS) return;
        if (pendingText != null) return;          // 还有没写进去的新内容，先让它补
        String cur = XiaobuInject.currentClipText();
        long curTs = XiaobuInject.currentClipTs();
        if (t.equals(cur)) return;                // 剪贴板里就是它 —— 不重复写
        if (curTs > 0 && curTs > lastRxTextAt + 1000L) return;   // 用户之后复制过别的，别覆盖
        // 剪贴板虽然已经被清空（curTs==0），但「收到之后本地还出现过更新的内容」时也不该拿旧的覆盖：
        // 例：PC 同步来 A → 用户在手机上复制了 B → 系统清空 → 键盘亮起时不该把 A 塞回来。
        if (lastSeenClipTs > lastRxTextAt + 1000L) return;
        rxEchoHash = hashText(t);
        boolean ok = XiaobuInject.setSystemClipboard(t);
        MainHookLogger.log("event=clip_lastrx_restore why=" + why + " len=" + t.length()
                + " ok=" + ok + " curTs=" + curTs);
        if (ok) {
            lastSeenClipTs = XiaobuInject.currentClipTs();
            saveClipState();
        }
    }

    private void savePendingText() {
        try {
            String t = pendingText;
            File f = new File(dir(), PENDING_TEXT_FILE);
            if (t == null) { f.delete(); return; }
            String b64 = android.util.Base64.encodeToString(
                    t.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f, false);
            fo.write((pendingTextVer + "|" + pendingTextSince + "|" + b64).getBytes("UTF-8"));
            fo.close();
        } catch (Throwable t) {
            MainHookLogger.log("event=clip_pending_save_fail " + t);
        }
    }

    private void clearPendingText(String why) {
        if (pendingText == null) return;
        MainHookLogger.log("event=clip_pending_done why=" + why + " ver=" + pendingTextVer);
        pendingText = null;
        pendingTextVer = -1;
        pendingTextSince = 0;
        nextTextTryAt = 0;
        savePendingText();
    }

    private void loadPendingText() {
        try {
            String s = read(PENDING_TEXT_FILE);
            if (s == null || s.isEmpty()) return;
            String[] p = s.split("\\|", -1);
            if (p.length < 3) return;
            long ver = Long.parseLong(p[0].trim());
            long since = Long.parseLong(p[1].trim());
            String t = new String(android.util.Base64.decode(p[2].trim(),
                    android.util.Base64.NO_WRAP), "UTF-8");
            if (t.isEmpty()) return;
            if (System.currentTimeMillis() - since > PENDING_TEXT_TTL_MS) {
                MainHookLogger.log("event=clip_pending_stale ver=" + ver);
                new File(dir(), PENDING_TEXT_FILE).delete();
                return;
            }
            pendingText = t;
            pendingTextVer = ver;
            pendingTextSince = since;
            MainHookLogger.log("event=clip_pending_load ver=" + ver + " len=" + t.length());
        } catch (Throwable t) {
            MainHookLogger.log("event=clip_pending_load_err " + t);
        }
    }

    /**
     * 补写待写文本（幂等）。键盘亮起时必抢一次，其余时候跟着轮询节流重试。
     *
     * 三条判据，顺序不能换：
     *   ① 剪贴板里已经是这段内容 → 视为成功（假失败/上次其实写进去了）；
     *   ② 剪贴板里有更晚的内容（ts > 收到时刻）→ 用户自己复制过，放弃，绝不覆盖；
     *   ③ 读不到剪贴板 → 先不动（冻结状态下无从判断），等键盘亮起再试。
     */
    private void flushPendingText(String why) {
        String t = pendingText;
        if (t == null) return;
        long nowMs = System.currentTimeMillis();
        if (nowMs - pendingTextSince > PENDING_TEXT_TTL_MS) { clearPendingText("expired"); return; }
        boolean urgent = "ime_shown".equals(why);
        if (!urgent && nowMs < nextTextTryAt) return;

        String cur = XiaobuInject.currentClipText();
        long curTs = XiaobuInject.currentClipTs();
        if (t.equals(cur)) {
            XiaobuClipDb.recordText(XiaobuInject.ctx, t);   // 面板补录（缺行时）
            rxEchoHash = hashText(t);
            if (curTs > 0) lastSeenClipTs = curTs;
            saveClipState();
            clearPendingText("already");
            return;
        }
        if (curTs > 0 && curTs > pendingTextSince + 1000L) {
            clearPendingText("superseded");
            return;
        }
        if (cur == null && curTs == 0 && !urgent) {
            // 读不到剪贴板（进程被冻结 / 权限没放开）：先不动，免得盖掉用户之后复制的内容
            return;
        }
        nextTextTryAt = nowMs + 1000;
        rxEchoHash = hashText(t);
        boolean ok = XiaobuInject.setSystemClipboard(t);
        MainHookLogger.log("event=clip_pending_write why=" + why + " ver=" + pendingTextVer
                + " len=" + t.length() + " ok=" + ok);
        if (ok) {
            lastSeenClipTs = XiaobuInject.currentClipTs();
            saveClipState();
            XiaobuClipDb.recordText(XiaobuInject.ctx, t);
            clearPendingText("ok");
            statusText = "补写剪贴板 " + t.length() + " 字";
        }
    }

    static String read(String name) {
        try {
            File f = new File(dir(), name);
            if (!f.exists()) return null;
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r < 0) break;
                off += r;
            }
            in.close();
            return new String(b, "UTF-8").trim();
        } catch (Throwable t) { return null; }
    }

    private void runLoop() {
        try { inner(); } catch (Throwable t) { MainHookLogger.log("event=loop_fatal " + t); }
    }

    private void inner() {
        MainHookLogger.log("event=loop_start");
        String id = read("identity.txt");
        if (id == null) {
            // 首次启动：本地匿名注册一个设备身份（不需要微信账号 / 官方输入法）
            statusText = "正在注册设备…";
            try {
                wx.registerNewIdentity();
                persistIdentity();
                id = read("identity.txt");
                MainHookLogger.log("event=identity_saved uin=" + wx.uin);
            } catch (Throwable t) {
                statusText = "注册失败";
                MainHookLogger.log("event=register_fail " + t);
                return;
            }
        }
        if (id == null) { statusText = "缺少身份凭证"; MainHookLogger.log("event=no_identity"); return; }
        String[] parts = id.split("\\n");
        try {
            wx.uin = parts[0].trim();
            if (parts.length > 1) wx.deviceName = parts[1].trim();
            if (parts.length > 2) wx.deviceCode = parts[2].trim();
            if (parts.length > 3) wx.privD = new BigInteger(parts[3].trim(), 16);
            if (parts.length > 4) wx.serverKey = parts[4].trim();
            if (parts.length > 5) wx.uuid = parts[5].trim();
            ownUin = Long.parseLong(wx.uin);
            MainHookLogger.log("event=identity_ok uin=" + wx.uin);
        } catch (Throwable t) {
            statusText = "身份解析失败";
            MainHookLogger.log("event=identity_parse_err " + t);
            return;
        }
        // 握手（失败退避重试，server_key 每次会话都会轮换）
        // 刚注册完时 wx.key 已有值，会话直接可用，不必再握手一遍。
        for (int attempt = 0; wx.key == null && attempt < 5 && running; attempt++) {
            try {
                wx.initialize();
                statusText = "已连接";
                break;
            } catch (Throwable t) {
                MainHookLogger.log("event=hs_fail try=" + attempt + " " + t);
                statusText = "握手中…(" + (attempt + 1) + ")";
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        }
        if (wx.key == null) { statusText = "握手失败"; return; }

        // 基线：启动前就存在的旧内容不上传；但「刚复制、且从未同步过」的要补发一次。
        // 场景：小布输入法进程被系统杀掉期间，用户在手机上复制了内容 ——
        // 旧实现把当前剪贴板一律当基线丢弃，于是那次复制永远不会同步出去，
        // 而且因为 lastLocalText 已等于该内容，用户「再复制一次相同内容」也发不出去。
        loadClipState();
        loadPendingText();
        loadLastRxText();
        loadWatchStamp(dir());
        for (int i = 0; i < 10 && XiaobuInject.ctx == null; i++) {
            try { Thread.sleep(300); } catch (InterruptedException ignore) {}
        }
        lastLocalText = XiaobuInject.currentClipText();
        lastSeenClipTs = XiaobuInject.currentClipTs();
        startupPending = false;
        long clipAge = lastSeenClipTs > 0 ? now() - lastSeenClipTs : Long.MAX_VALUE;
        boolean untouched = lastSeenClipTs > 0 && lastSeenClipTs > savedClipTs;
        if (lastLocalText != null && !lastLocalText.isEmpty() && untouched
                && clipAge >= 0 && clipAge < STARTUP_MAX_AGE_MS) {
            String h0 = hashText(lastLocalText);
            boolean already = recentDup(h0, lastTxHash, lastTxAt)
                    || recentDup(h0, lastHash, lastHashAt);
            if (!already) startupPending = true;
        }
        MainHookLogger.log("event=clip_baseline ver=" + lastVersion
                + " text=" + (lastLocalText == null ? -1 : lastLocalText.length())
                + " ts=" + lastSeenClipTs + " saved=" + savedClipTs
                + " age=" + (clipAge == Long.MAX_VALUE ? -1 : clipAge)
                + " pending=" + startupPending + " coldAdopt=" + coldAdopt);
        int netFail = 0;
        while (running) {
            try {
                poll();
                netFail = 0;
            } catch (Throwable t) {
                MainHookLogger.log("event=sync_err " + t);
                // 连续网络失败：server_key 会轮换，重新握手恢复会话
                if (++netFail >= 3) {
                    netFail = 0;
                    statusText = "重新连接…";
                    try { wx.initialize(); statusText = "已重连"; } catch (Throwable t2) {
                        MainHookLogger.log("event=rehs_fail " + t2);
                    }
                }
            }
            try { dictWatchTick(); } catch (Throwable t) { MainHookLogger.log("event=dictwatch_err " + t); }
            // 用 wait 代替 sleep：本地复制 / 键盘亮起时 onLocalCopy、onImeShown 会 notify，
            // 立刻跑下一轮，把「复制 → 同步」的延迟从最多 2 秒压到几十毫秒。
            try {
                synchronized (flushLock) {
                    if (!pendingFlush) flushLock.wait(POLL_MS);
                }
            } catch (InterruptedException e) { return; }
        }
    }

    private long lastDictWatch = 0;
    private static final long DICT_WATCH_MS = 10 * 60 * 1000L;

    /**
     * 每 10 分钟无条件给词库做一次体检。
     * 宿主引擎塌库、个别词消失都可能发生在「微信没有新词」的空窗期；
     * 只在 mergeInto 里检测会漏掉整段时间（2026-10-28 03:45 那次塌库就是空窗期发生的，
     * 直到 5 分钟后有新词才被看到，而回灌已经无效）。
     */
    private volatile boolean dictWatchBusy = false;
    private static final String WATCH_STAMP = "xd_watch_at.txt";

    /**
     * 词库体检调度：**必须异步**。
     *
     * 体检要导出 1.7 MB 词库、写 2 MB JSON 快照、再写 1 MB ud.bin 快照；
     * 旧实现直接在同步线程里跑，进程每次启动都会把剪贴板轮询卡住 40~60 秒
     * （2026-11-10 实测：16:54:13 之后近一分钟没有任何日志），
     * 正好撞上「刚启动就复制内容」的场景 → 表现为「复制了没同步」。
     */
    private void dictWatchTick() {
        long now = System.currentTimeMillis();
        if (now - lastDictWatch < DICT_WATCH_MS) return;
        if (dictWatchBusy) return;
        android.content.Context ctx = XiaobuInject.ctx;
        if (ctx == null) return;
        dictWatchBusy = true;
        lastDictWatch = now;
        final java.io.File d = new java.io.File(ctx.getFilesDir(), "wtsync");
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                MainHookLogger.log("event=dictwatch_tick ctx=true");
                XiaobuDictSync.periodicCheck(d);
            } catch (Throwable t) {
                MainHookLogger.log("event=dictwatch_err " + t);
            } finally {
                MainHookLogger.log("event=dictwatch_done cost=" + (System.currentTimeMillis() - t0) + "ms");
                writeWatchStamp(d, t0);
                dictWatchBusy = false;
            }
        }, "WtDictWatch").start();
    }

    /** 上次体检时间落盘：进程频繁重启时不会每次都重跑体检 */
    private void loadWatchStamp(java.io.File d) {
        try {
            String v = read(WATCH_STAMP);
            if (v != null && !v.isEmpty()) {
                lastDictWatch = Long.parseLong(v.trim());
            }
        } catch (Throwable ignore) {}
        if (lastDictWatch <= 0) lastDictWatch = now();   // 没有记录：从此刻起 10 分钟后再体检
        MainHookLogger.log("event=dictwatch_stamp last=" + lastDictWatch);
    }

    private void writeWatchStamp(java.io.File d, long at) {
        try {
            java.io.FileOutputStream fo = new java.io.FileOutputStream(new File(d, WATCH_STAMP), false);
            fo.write(String.valueOf(at).getBytes("UTF-8"));
            fo.close();
        } catch (Throwable ignore) {}
    }

    /** 把当前身份（含 uuid）写回 identity.txt：uin / name / code / priv / serverKey / uuid */
    private void persistIdentity() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(wx.uin).append('\n')
              .append(wx.deviceName == null ? "" : wx.deviceName).append('\n')
              .append(wx.deviceCode == null ? "" : wx.deviceCode).append('\n')
              .append(wx.privD == null ? "" : wx.privD.toString(16).toUpperCase()).append('\n')
              .append(wx.serverKey == null ? "" : wx.serverKey).append('\n')
              .append(wx.uuid == null ? "" : wx.uuid).append('\n');
            java.io.File f = new java.io.File(dir(), "identity.txt");
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, false);
            out.write(sb.toString().getBytes("UTF-8"));
            out.close();
            MainHookLogger.log("event=identity_write len=" + sb.length());
        } catch (Throwable t) {
            MainHookLogger.log("event=identity_write_fail " + t);
        }
    }

    /** 本地复制立即触发一次发布（不等轮询，把本机→远端的延迟压到亚秒级） */
    public static void onLocalCopy() {
        WtClient c = active;
        if (c == null) return;
        c.pendingFlush = true;
        synchronized (c.flushLock) { c.flushLock.notifyAll(); }
    }

    /**
     * 本机复制了图片（由 MainHook 的 setPrimaryClip hook 调用）。
     *
     * 必须异步：hook 跑在宿主主线程上，解码 + 压缩一张 4K 图要几十毫秒到几百毫秒，
     * 同步做会直接卡住输入法。
     */
    public static void onLocalImage(final android.content.ClipData clip) {
        final WtClient c = active;
        if (c == null || clip == null || clip.getItemCount() <= 0) return;
        if (!imageSyncEnabled()) return;
        new Thread(() -> {
            try {
                android.content.ClipData.Item item = clip.getItemAt(0);
                android.net.Uri uri = item == null ? null : item.getUri();
                if (uri == null) return;
                ClipImageCodec.Result r = ClipImageCodec.encode(
                        XiaobuInject.ctx, uri, ClipImageCodec.MAX_BYTES);
                if (!r.ok()) {
                    MainHookLogger.log("event=clipimg_skip err=" + r.error
                            + " src=" + r.srcWidth + "x" + r.srcHeight);
                    return;
                }
                String hash = ClipImageStore.hashOf(r.jpeg);
                String ih = "i:" + hash;
                if (c.recentDup(ih, c.lastTxHash, c.lastTxAt)
                        || c.recentDup(ih, c.lastHash, c.lastHashAt)) {
                    MainHookLogger.log("event=clipimg_tx_dup hash=" + hash);
                    return;
                }
                if (ClipImageStore.isDuplicate(hash)) {
                    MainHookLogger.log("event=clipimg_dup hash=" + hash);
                    return;
                }
                long v = c.wx.publishImage(c.groupId, r.jpeg,
                        WxClient.randomHex(32));
                if (v > 0) {
                    ClipImageStore.markSeen(hash);
                    c.lastVersion = v;
                    c.lastHash = ih;
                    c.lastTxHash = ih;
                    c.lastHashAt = now();
                    c.lastTxAt = c.lastHashAt;
                    c.saveClipState();
                    MainHookLogger.log("event=clipimg_tx hash=" + hash
                            + " bytes=" + r.jpeg.length
                            + " " + r.width + "x" + r.height
                            + " src=" + r.srcWidth + "x" + r.srcHeight
                            + " ver=" + v);
                } else {
                    MainHookLogger.log("event=clipimg_tx_fail ver=" + v);
                }
            } catch (Throwable t) {
                MainHookLogger.log("event=clipimg_tx_err " + t);
            }
        }, "WtClipImgTx").start();
    }

    /** 历史遗留坏记录的清理每个进程只做一次 */
    private static final java.util.concurrent.atomic.AtomicBoolean legacyClipPurged =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 收到远端图片：字节托管进 MediaStore → 把这个 content:// URI 放进【系统剪贴板】。
     *
     * 需求收敛为最基础的「PC 复制图片 → 手机端能粘进微信」，不再追求小布剪贴板面板
     * 里显示缩略图（已证实该版本小布面板没有图片渲染、点击图片行也无任何动作）。
     *
     * 为什么改回系统剪贴板：
     *   微信/QQ/浏览器粘贴时读的就是系统剪贴板。只要剪贴板里是一个带 image/* MIME 的
     *   content:// URI，目标 app 长按「粘贴」就能把图取出来——这是最通用、最基础的路径。
     *
     * 为什么用 MediaStore 的 content:// 而不是 file://：
     *   Android 7+ 跨应用分享 file:// 会被 StrictMode / FileUriExposedException 直接拦下；
     *   MediaStore 是系统 provider，任何持有 READ_MEDIA_IMAGES 的 app（微信必然有）都能读。
     *   字节由小布进程写入，按分区存储规则贡献者可免权限读回（真机已验证：diag_read_ok）。
     *
     * 防回环：setSystemClipboardUri 会打 cx.synced 标记，本进程 setPrimaryClip 的 hook
     * 见到该标记就不会把这张图当成「本地新复制」再推回 PC。
     */
    private synchronized void handleIncomingImage(byte[] data, long from, long ver) {
        if (data == null || data.length == 0) {
            lastVersion = ver;
            return;
        }
        String hash = ClipImageStore.hashOf(data);
        String ih = "i:" + hash;
        // 同内容（含「刚发出去的图被服务端原样回传」与「重启后重放同一张」）只推进水位
        if (recentDup(ih, lastHash, lastHashAt) || recentDup(ih, lastTxHash, lastTxAt)) {
            lastVersion = ver;
            saveClipState();
            MainHookLogger.log("event=clipimg_rx_dup hash=" + hash);
            return;
        }
        if (ClipImageStore.isDuplicate(hash)) {
            lastVersion = ver;
            MainHookLogger.log("event=clipimg_rx_dup hash=" + hash);
            return;
        }
        // 字节托管进 MediaStore，拿到 content://media/external/images/media/<id>。
        // 系统 provider，小布进程天然可读，不依赖模块自己的文件与权限。
        // 重试时复用同一份已托管的 URI，避免每 2 秒往相册里塞一张重复图。
        String uriStr = (hash.equals(lastPubHash) && lastPubUri != null)
                ? lastPubUri : ClipImageStore.publish(XiaobuInject.ctx, data);
        if (uriStr == null) {
            lastVersion = ver;
            MainHookLogger.log("event=clipimg_rx_publish_fail hash=" + hash);
            return;
        }
        lastPubUri = uriStr;
        lastPubHash = hash;
        String fileName = "clip_" + hash + ".jpg";

        // 每个进程只清一次：删掉早期版本写进小布库的历史坏记录（面板那条路留下的垃圾）
        if (legacyClipPurged.compareAndSet(false, true)) {
            XiaobuClipDb.purgeLegacy(XiaobuInject.ctx);
        }

        // 唯一写入路径：放进系统剪贴板。之后微信/QQ/浏览器长按「粘贴」即可取出图片。
        boolean ok = XiaobuInject.setSystemClipboardUri(
                android.net.Uri.parse(uriStr), "image/jpeg", fileName);
        MainHookLogger.log("event=clipimg_rx hash=" + hash + " bytes=" + data.length
                + " from=" + from + " ver=" + ver + " clip=" + ok + " uri=" + uriStr);
        if (ok) {
            ClipImageStore.markSeen(hash);
            lastPubUri = null;
            lastPubHash = null;
            lastVersion = ver;
            lastHash = ih;
            lastTxHash = ih;          // 收下来的图不再回传
            lastHashAt = now();
            lastTxAt = lastHashAt;
            lastRxAt = System.currentTimeMillis();
            saveClipState();
            // 写成功即清空待写队列（只清不比它更新的那条）
            if (pendingVer >= 0 && pendingVer <= ver) {
                pendingBytes = null;
                pendingVer = -1;
                MainHookLogger.log("event=clipimg_pending_done ver=" + ver);
            }
            statusText = "收到图片 " + (data.length / 1024) + " KB";
            return;
        }

        // 写入被系统拒绝。根因是 WRITE_CLIPBOARD 这个 appop 默认值 = foreground：
        // 只有输入法窗口真的显示着，系统才允许它写剪贴板；键盘收着时 setPrimaryClip
        // 被静默丢弃（不抛异常，只能靠读回校验发现）。
        //
        // 不改系统设置的做法：把解密好的图片挂在「待写队列」，不推进水位；
        // 每轮心跳轻量重试一次（复用已托管的 URI，不重复下载、不重复入库），
        // 同时由 InputMethodService.onWindowShown 钩子立刻触发一次——
        // 用户一唤出键盘（= 系统放行）就能在毫秒级补写进去，随后长按粘贴即可。
        long now = System.currentTimeMillis();
        if (pendingVer != ver) {
            pendingVer = ver;
            pendingSince = now;
            clipWriteTries = 0;
            MainHookLogger.log("event=clipimg_pending_new ver=" + ver);
        }
        pendingBytes = data;
        clipWriteTries++;
        if (now - pendingSince > PENDING_TTL_MS) {
            // 挂太久（用户十来分钟没碰输入法）就丢弃，免得下次唤醒时用旧图覆盖新复制的内容
            lastVersion = ver;
            pendingBytes = null;
            pendingVer = -1;
            lastPubUri = null;
            lastPubHash = null;
            MainHookLogger.log("event=clipimg_pending_expired ver=" + ver
                    + " tries=" + clipWriteTries);
            return;
        }
        if (clipWriteTries == 1 || clipWriteTries % 20 == 0) {
            MainHookLogger.log("event=clipimg_pending ver=" + ver + " try=" + clipWriteTries
                    + " 等输入法显示后补写");
        }
        startPendingRetry();
    }

    private volatile Thread pendingRetryThread;

    /**
     * 待写队列的独立重试线程：每 2s 试一次，直到写成功或超过 PENDING_TTL_MS。
     *
     * 为什么需要它：补写原先只挂在「心跳又拉到同一个 ver」和「输入法窗口显示」两个时机上。
     * 但 PC 端有时只发一次、服务端不再重发；而键盘如果一直是显示状态，onWindowShown 也不会再触发。
     * 两个时机都不来，图片就永远躺在队列里——用户表现为「复制了但粘贴不出来」。
     */
    private void startPendingRetry() {
        Thread cur = pendingRetryThread;
        if (cur != null && cur.isAlive()) return;
        Thread t = new Thread(() -> {
            while (true) {
                try { Thread.sleep(2000); } catch (Throwable ignore) { return; }
                byte[] data = pendingBytes;
                long ver = pendingVer;
                if (data == null || ver < 0) return;                    // 已写成功
                if (System.currentTimeMillis() - pendingSince > PENDING_TTL_MS) return;
                flushPending("retry");
            }
        }, "WtPendingRetry");
        t.setDaemon(true);
        t.start();
        pendingRetryThread = t;
    }

    /**
     * 输入法窗口刚显示（InputMethodService.onWindowShown）——这是系统放开
     * WRITE_CLIPBOARD 的瞬间，立刻把挂起的图片写进剪贴板。
     * 延迟 120ms 让 IME 的窗口状态先同步到 ActivityManager，否则 appop 判定可能仍是旧值。
     */
    public static void onImeShown() {
        WtClient c = active;
        if (c == null) return;
        try {
            new Thread(() -> {
                try { Thread.sleep(120); } catch (InterruptedException ignore) {}
                c.flushPending("ime_shown");
                // 文本也有待写队列：键盘没弹时收进来的内容，写不进去就挂在这儿，
                // 键盘一出现立刻补写（此刻系统才放开 WRITE_CLIPBOARD）。
                c.flushPendingText("ime_shown");
                // 最后再兜一层：最近收到的那条如果已经被系统/linker 清掉了，重新放回去。
                // 内容还在就什么都不做（不重复写、不刷时间戳）。
                c.restoreLastRx("ime_shown");
                // 键盘一出现，系统才允许输入法读剪贴板。
                // 每次亮起都做一次「补发评估」：进程若在键盘收起期间被冻结，这段时间
                // 复制的内容只有解冻后才读得到，这里主动标记一次让紧接着的轮询立即判定。
                c.imeCatchup = true;
                if (c.lastSeenClipTs == 0) {
                    // 进程启动至今一次都没读成功（启动时被权限挡住）→ 打开「待补发」
                    c.startupPending = true;
                    MainHookLogger.log("event=clip_catchup_ime_shown seenTs=0");
                }
                c.pendingFlush = true;
                synchronized (c.flushLock) { c.flushLock.notifyAll(); }
            }, "WtFlush").start();
        } catch (Throwable ignore) {}
    }

    /** 立即尝试把挂起的图片写进剪贴板（幂等；没有挂起项则什么都不做）。 */
    private void flushPending(String why) {
        byte[] data = pendingBytes;
        long ver = pendingVer;
        if (data == null || ver < 0) return;
        // 心跳轮询要节流，但「输入法刚显示」这个时机必须无条件抢一次，
        // 否则会被上一轮心跳的 300ms 锁挡掉，白等一个轮询周期。
        boolean urgent = "ime_shown".equals(why);
        if (!urgent && System.currentTimeMillis() < nextTryAt) return;
        nextTryAt = System.currentTimeMillis() + 300;
        MainHookLogger.log("event=clipimg_flush why=" + why + " ver=" + ver);
        handleIncomingImage(data, 0, ver);
    }

    /** 已托管但还没成功写进剪贴板的 URI（写失败重试时复用，避免重复入库） */
    private String lastPubUri = null;
    private String lastPubHash = null;
    private int clipWriteTries = 0;

    /**
     * 待写队列：解密成功但当时没权限写剪贴板的图片。
     * 不推进水位、不重复下载，等输入法窗口出现（系统放行 WRITE_CLIPBOARD）再补写。
     */
    private volatile byte[] pendingBytes = null;
    private volatile long pendingVer = -1;
    private long pendingSince = 0;
    private volatile long nextTryAt = 0;
    /** 挂起有效期：超过这个时间还没写进去就丢弃，避免旧图覆盖用户后来复制的内容 */
    private static final long PENDING_TTL_MS = 10 * 60 * 1000L;

    /** COS 下载超时 */
    private static final int COS_TIMEOUT_MS = 15000;
    /** 单个 COS 对象体积上限，防止被超大数据拖死 */
    private static final int COS_MAX_BYTES = 24 * 1024 * 1024;
    /** 同一个 ver 的下载重试计数（失败不推进水位，但要限次数） */
    private int cosRetry = 0;
    private long cosRetryVer = -1;

    /**
     * 处理 type=6（图片走 COS）的剪贴板消息。
     *
     * 载荷是扁平 JSON（2026-10-27 抓包 + mkdir700/wx-ime-sdk 交叉验证）：
     *   {"version":1,"path":"https://…myqcloud.com/clipboard/…",
     *    "md5":"<明文 32 位 MD5>","key":"<base64(16 字节)>"}
     *
     * 关键：COS 上存的 **不是原图**，而是 AES-128-CTR 密文，key 与 IV 是同一段 16 字节。
     * 之前只抠了 path 就把下载回来的密文当图片解，头字节全随机、长度又不是 16 的倍数，
     * 所以永远 kind=other。解密后还要用 md5 校验，校验不过宁可丢弃也不写进剪贴板。
     */
    private void handleIncomingCos(byte[] payload, long from, long ver) {
        // 这条还在待写队列里：说明上次是「键盘没显示，系统不让写剪贴板」。
        // 本轮心跳只重试写剪贴板，绝不重复下载/解密 COS 上的同一张图。
        if (ver == pendingVer && pendingBytes != null) {
            if (System.currentTimeMillis() >= nextTryAt) {
                nextTryAt = System.currentTimeMillis() + 2000;
                handleIncomingImage(pendingBytes, from, ver);
            }
            return;
        }
        String json;
        try {
            json = new String(payload, "UTF-8");
        } catch (Throwable t) {
            lastVersion = ver;
            return;
        }
        String url = extractJsonField(json, "path");
        String md5 = extractJsonField(json, "md5");
        String b64Key = extractJsonField(json, "key");
        byte[] imgKey = null;
        if (b64Key != null) {
            try {
                imgKey = android.util.Base64.decode(b64Key, android.util.Base64.NO_WRAP);
            } catch (Throwable ignore) {}
        }
        MainHookLogger.log("event=clipcos_ref len=" + payload.length
                + " url=" + (url != null) + " md5=" + (md5 != null)
                + " key=" + (imgKey == null ? -1 : imgKey.length));

        if (url == null || !url.startsWith("http")) {
            lastVersion = ver;
            MainHookLogger.log("event=clipcos_nopath");
            return;
        }

        byte[] cipher = httpGet(url, COS_MAX_BYTES);
        if (cipher == null || cipher.length == 0) {
            if (cosRetryVer != ver) { cosRetryVer = ver; cosRetry = 0; }
            if (++cosRetry <= 3) {
                // 不推进水位，下一轮（2s 后）重试
                MainHookLogger.log("event=clipcos_dl_fail ver=" + ver + " try=" + cosRetry);
                return;
            }
            lastVersion = ver;
            MainHookLogger.log("event=clipcos_give_up ver=" + ver);
            return;
        }

        byte[] plain = cipher;
        if (imgKey != null && imgKey.length == 16) {
            byte[] dec = aesCtr(cipher, imgKey);
            if (dec != null) {
                boolean ok = md5 == null || md5.equalsIgnoreCase(md5Hex(dec));
                MainHookLogger.log("event=clipcos_dec in=" + cipher.length
                        + " out=" + dec.length + " md5ok=" + ok);
                if (!ok) {                       // 解错了就别往下走，免得把坏图写进剪贴板
                    lastVersion = ver;
                    return;
                }
                plain = dec;
            }
        }

        // 不依赖魔数：直接交给 BitmapFactory 试解码，PNG/WebP/GIF/HEIF 都能吃
        byte[] norm = maybeGunzip(plain);
        byte[] jpeg = toJpeg(norm);
        if (jpeg != null) {
            MainHookLogger.log("event=clipcos_ok kind=image bytes=" + norm.length
                    + " jpeg=" + jpeg.length);
            handleIncomingImage(jpeg, from, ver);      // 内部会推进水位
            return;
        }

        // 非图片：能当文本就贴到剪贴板，否则只记一笔
        lastVersion = ver;
        try {
            String t = new String(norm, "UTF-8");
            if (looksLikeText(t)) {
                lastLocalText = t;
                rxEchoHash = hashText(t);            // 收进来的文本，别在下一轮当成本地复制发回去
                XiaobuInject.setSystemClipboard(t);
                MainHookLogger.log("event=clipcos_rx_text len=" + t.length());
                statusText = "收到剪贴板 " + t.length() + " 字";
                return;
            }
        } catch (Throwable ignore) {}
        MainHookLogger.log("event=clipcos_ok kind=other bytes=" + norm.length
                + " magic=" + isImageBytes(norm));
    }

    /** AES-128-CTR：解微信输入法的图片对象，key 与 IV 复用同一段 16 字节。 */
    private static byte[] aesCtr(byte[] data, byte[] key) {
        try {
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(key, "AES"),
                    new javax.crypto.spec.IvParameterSpec(key));
            return c.doFinal(data);
        } catch (Throwable t) {
            MainHookLogger.log("event=clipcos_dec_err " + t);
            return null;
        }
    }

    private static String md5Hex(byte[] b) {
        try {
            return WtCrypto.toHex(java.security.MessageDigest.getInstance("MD5").digest(b))
                    .toLowerCase();
        } catch (Throwable t) { return ""; }
    }

    /** COS 若返回 gzip 传输编码，这里先解开；解不开就原样返回。 */
    private static byte[] maybeGunzip(byte[] b) {
        if (b == null || b.length < 2) return b;
        if ((b[0] & 0xFF) != 0x1F || (b[1] & 0xFF) != 0x8B) return b;
        java.io.InputStream in = null;
        try {
            in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(b));
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > COS_MAX_BYTES) return b;
                bo.write(buf, 0, n);
            }
            MainHookLogger.log("event=clipcos_gunzip " + b.length + "->" + total);
            return bo.toByteArray();
        } catch (Throwable t) {
            return b;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignore) {}
        }
    }

    /**
     * 把 type=2/4 的 f6 纯文本引用转成 handleIncomingCos 需要的 JSON 形态。
     *
     * 实测形态（2026-11-16 16:29 / 16:40 两条）以 https:// 开头、1146 字节，比 type=6 的
     * JSON（1243 字节）少了字段名 —— 按「URL [+ 分隔符 + md5][+ 分隔符 + key]」解析：
     * URL 原样保留（COS 签名参数在 query 里，裁掉就 403），md5/key 从剩余片段或 URL query 里找。
     * 找不到 key 也不致命：明文对象能直接解码；加密对象会走 handleIncomingCos 的失败兜底，
     * 只记一笔日志，绝不把半截字节写成剪贴板里的坏图。
     */
    private static String cosRefToJson(byte[] data) {
        String s;
        try { s = new String(data, "UTF-8").trim(); } catch (Throwable t) { return null; }
        if (s.isEmpty()) return null;
        if (s.charAt(0) == '{') return s;               // 已经是 JSON，原样交给下一条链路

        String url = null, md5 = null, key = null;
        String[] parts = s.split("[\\r\\n\\x00\\x01]+");
        for (String raw : parts) {
            String p = raw == null ? "" : raw.trim();
            if (p.isEmpty()) continue;
            if (p.startsWith("http") && p.indexOf("://") > 0) {
                if (url == null) url = p;               // 连签名 query 一起保留
                int q = p.indexOf('?');
                if (q > 0 && q + 1 < p.length()) {
                    for (String kv : p.substring(q + 1).split("&")) {
                        int e = kv.indexOf('=');
                        if (e <= 0) continue;
                        String k = kv.substring(0, e).toLowerCase();
                        String v = kv.substring(e + 1);
                        if (md5 == null && k.contains("md5")) md5 = v.toLowerCase();
                        if (key == null && k.endsWith("key") && v.length() >= 16) key = v;
                    }
                }
            } else if (p.matches("[0-9a-fA-F]{32}")) {
                if (md5 == null) md5 = p.toLowerCase();
            } else if (key == null && p.length() >= 16 && p.length() <= 64
                    && p.matches("[A-Za-z0-9+/=_-]+")) {
                key = p;                                // base64 的 16 字节 key（24 字符）
            }
        }
        if (url == null) return null;
        StringBuilder b = new StringBuilder();
        b.append("{\"version\":1,\"path\":\"").append(jsonEsc(url)).append('"');
        if (md5 != null) b.append(",\"md5\":\"").append(jsonEsc(md5)).append('"');
        if (key != null) b.append(",\"key\":\"").append(jsonEsc(key)).append('"');
        b.append('}');
        MainHookLogger.log("event=clipcos_ref_parse md5=" + (md5 != null)
                + " key=" + (key == null ? -1 : key.length()) + " parts=" + parts.length);
        return b.toString();
    }

    private static String jsonEsc(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 从扁平 JSON 里抠字符串字段（值里不含转义引号，扫引号即可）。 */
    private static String extractJsonField(String json, String name) {
        try {
            int k = json.indexOf("\"" + name + "\"");
            if (k < 0) return null;
            int c = json.indexOf(':', k + name.length() + 2);
            if (c < 0) return null;
            int q1 = json.indexOf('"', c + 1);
            if (q1 < 0) return null;
            int q2 = json.indexOf('"', q1 + 1);
            if (q2 < 0) return null;
            return json.substring(q1 + 1, q2).replace("\\/", "/");
        } catch (Throwable t) { return null; }
    }

    /** 简易 GET；任何异常都返回 null，绝不把异常抛进轮询线程。 */
    private static byte[] httpGet(String url, int max) {
        // 1) 常规路径：系统 DNS + HttpURLConnection
        byte[] direct = httpGetDirect(url, max);
        if (direct != null) return direct;

        // 2) 兜底路径：进程内 DNS 被旁路时（ColorOS dns_proxyd=1、输入法自带私有 HTTPDNS），
        //    InetAddress.getAllByName 会直接抛 UnknownHostException。这里自己用 UDP 查 A 记录，
        //    再「IP 直连 + SNI 仍用域名」把请求发出去（COS 预签名校验的是 Host，不是 IP）。
        String host, file;
        try {
            java.net.URL u = new java.net.URL(url);
            host = u.getHost();
            file = u.getFile();
        } catch (Throwable t) {
            MainHookLogger.log("event=cos_url_bad " + t);
            return null;
        }
        String ip = resolveHost(host);
        java.util.List<String> cands = new java.util.ArrayList<>();
        if (ip != null) cands.add(ip);
        if (host != null && host.endsWith(".myqcloud.com")) {
            for (String s : COS_STATIC_IPS) if (!cands.contains(s)) cands.add(s);
        }
        if (cands.isEmpty()) {
            MainHookLogger.log("event=cos_dns_fail host=" + host);
            return null;
        }
        for (String cand : cands) {
            try {
                byte[] b = HttpChannel.getViaIp(host, file, cand);
                if (b != null && b.length > 0 && b.length <= max) {
                    MainHookLogger.log("event=cos_via_ip ip=" + cand + " bytes=" + b.length);
                    return b;
                }
                MainHookLogger.log("event=cos_via_ip_empty ip=" + cand
                        + " bytes=" + (b == null ? -1 : b.length));
            } catch (Throwable t) {
                MainHookLogger.log("event=cos_via_ip_err ip=" + cand + " " + t);
            }
        }
        // 全部 IP 都失败：怀疑解析结果过期，清缓存让下一轮重新解析
        synchronized (dnsCache) {
            dnsCache.remove(host);
            dnsCacheAt.remove(host);
        }
        return null;
    }

    /** 腾讯云 COS/CDN 常用节点，DNS 全挂时的最后兜底（2026-10 实测 ap-shanghai）。 */
    private static final String[] COS_STATIC_IPS = {
            "180.111.196.17", "180.111.196.26", "121.229.155.237"
    };

    /**
     * 进程内解析域名：缓存 → 系统 resolver → DoH(IP 直连) → UDP DNS → 静态 IP 池。
     *
     * 顺序不是随便排的：这台机器上系统 resolver 和 UDP 53 都不可用（前者秒抛
     * UnknownHostException，后者要等满 4×2.5s 才返回 null），只有 443 上的 DoH 能通。
     * 所以 DoH 排在 UDP 前面，并且结果缓存 10 分钟——否则每来一张图都要重解析几十秒。
     */
    private static String resolveHost(String host) {
        if (host == null) return null;
        synchronized (dnsCache) {
            Long at = dnsCacheAt.get(host);
            String hit = dnsCache.get(host);
            if (hit != null && at != null && System.currentTimeMillis() - at < DNS_TTL_MS) {
                return hit;
            }
        }

        String ip = null;
        try {
            java.net.InetAddress[] all = java.net.InetAddress.getAllByName(host);
            if (all != null) {
                for (java.net.InetAddress a : all) {
                    if (a instanceof java.net.Inet4Address) { ip = a.getHostAddress(); break; }
                }
            }
        } catch (Throwable ignore) {}

        if (ip == null) ip = resolveViaDoh(host);
        if (ip == null) {
            ip = DnsUdp.resolveA(host, new String[]{"223.5.5.5"}, 1500);
            if (ip != null) MainHookLogger.log("event=dns_udp host=" + host + " ip=" + ip);
        }
        if (ip != null) {
            synchronized (dnsCache) {
                dnsCache.put(host, ip);
                dnsCacheAt.put(host, System.currentTimeMillis());
            }
        }
        return ip;
    }

    private static final java.util.HashMap<String, String> dnsCache = new java.util.HashMap<>();
    private static final java.util.HashMap<String, Long> dnsCacheAt = new java.util.HashMap<>();
    private static final long DNS_TTL_MS = 10 * 60 * 1000L;

    /**
     * DoH（DNS over HTTPS）：UDP 53 被拦时唯一还能用的正规解析方式。
     * 443 是通的，用「IP 直连 + SNI 仍为域名」发 GET，返回 JSON，抠出 type=A 的 data。
     */
    private static String resolveViaDoh(String host) {
        String[][] servers = {
                {"doh.pub", "1.12.12.12", "/dns-query?name=" + host + "&type=A"},
                {"dns.alidns.com", "223.5.5.5", "/resolve?name=" + host + "&type=1"},
        };
        for (String[] sv : servers) {
            try {
                byte[] rsp = HttpChannel.getViaIp(sv[0], sv[2], sv[1], 5000, 8000);
                if (rsp == null || rsp.length == 0) continue;
                String ip = firstAFromJson(new String(rsp, "UTF-8"));
                if (ip != null) {
                    MainHookLogger.log("event=doh_ok host=" + host + " ip=" + ip + " via=" + sv[0]);
                    return ip;
                }
            } catch (Throwable t) {
                MainHookLogger.log("event=doh_err via=" + sv[0] + " " + t);
            }
        }
        return null;
    }

    private static String firstAFromJson(String json) {
        int i = 0;
        while ((i = json.indexOf("\"data\"", i)) >= 0) {
            int c = json.indexOf(':', i);
            if (c < 0) return null;
            int q1 = json.indexOf('"', c);
            int q2 = q1 < 0 ? -1 : json.indexOf('"', q1 + 1);
            if (q2 < 0) return null;
            String v = json.substring(q1 + 1, q2);
            if (v.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return v;
            i = q2;
        }
        return null;
    }

    private static byte[] httpGetDirect(String url, int max) {
        java.net.HttpURLConnection conn = null;
        java.io.InputStream in = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(COS_TIMEOUT_MS);
            conn.setReadTimeout(COS_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "wtsync/1.0");
            int code = conn.getResponseCode();
            if (code != 200) {
                MainHookLogger.log("event=cos_http_code code=" + code + " urllen=" + url.length());
                return null;
            }
            in = conn.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) return null;
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } catch (Throwable t) {
            MainHookLogger.log("event=cos_http_err cls=" + t.getClass().getName()
                    + " msg=" + t.getMessage());
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignore) {}
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignore) {}
        }
    }

    /** 按魔数判断是不是图片，不依赖扩展名/Content-Type。 */
    private static boolean isImageBytes(byte[] b) {
        if (b == null || b.length < 12) return false;
        int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF, b2 = b[2] & 0xFF, b3 = b[3] & 0xFF;
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return true;                // JPEG
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return true;  // PNG
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return true;                // GIF
        if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) return true;  // RIFF/WEBP
        if (b0 == 0x42 && b1 == 0x4D) return true;                              // BMP
        return false;
    }

    /** 统一转 JPEG：MediaStore 落盘那条路是按 .jpg + image/jpeg 写的。 */
    private static byte[] toJpeg(byte[] src) {
        if (src != null && src.length > 3
                && (src[0] & 0xFF) == 0xFF && (src[1] & 0xFF) == 0xD8) return src;
        try {
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory
                    .decodeByteArray(src, 0, src.length);
            if (bmp == null) return null;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, bo);
            bmp.recycle();
            return bo.toByteArray();
        } catch (Throwable t) { return null; }
    }

    /** 粗略判断字节是否像可读文本（无 NUL、控制字符占比低）。 */
    private static boolean looksLikeText(String s) {
        if (s == null || s.isEmpty() || s.length() > 256 * 1024) return false;
        if (s.indexOf(0) >= 0) return false;
        int bad = 0;
        int n = Math.min(s.length(), 4000);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c < 32 && c != '\n' && c != '\r' && c != '\t') bad++;
        }
        return bad * 100 < n;                             // 控制字符 <1%
    }

    private final Object flushLock = new Object();
    private volatile boolean pendingFlush = false;
    private static final long POLL_MS = 2000;
    /** 常用语后台同步间隔 */
    private static final long HOTWORD_SYNC_MS = 60000;
    /** 上一次常用语同步时间，防止重入（同步本身跑在独立线程） */
    private static volatile long lastHwSync = 0;
    /** 同步进行中标志：进页面自动同步 / 定时同步 / 手动同步可能同时触发，并发会重复推送 */
    private static final java.util.concurrent.atomic.AtomicBoolean hwSyncing =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void poll() throws Exception {
        WxClient.Pb gr = wx.group(3);
        Long st = gr.l(2);
        if (st != null && st == 10020L) {
            if (paired) { paired = false; groupId = -1; }
            devs = java.util.Collections.emptyList();
            statusText = "未关联设备组";
            return;
        }
        WxClient.Pb g = WxClient.groupOf(gr);
        if (g == null) { statusText = "组信息异常"; return; }
        Long gid = g.l(1);
        if (gid == null || gid <= 0) {
            devs = java.util.Collections.emptyList();
            statusText = "未关联设备组"; paired = false; return;
        }
        boolean wasPaired = paired;
        groupId = gid;
        Long fm = g.l(5);
        if (fm != null) funcMask = fm;
        paired = true;
        statusText = wasPaired ? "同步中" : "已关联，开始同步";

        // 解析设备组成员 Group{f2=repeated BoundInfo{f1=uin, f2=GroupDeviceInfo{f1=name}}}
        try {
            java.util.List<Object> binds = g.m.get(2);
            java.util.List<Dev> list = new java.util.ArrayList<>();
            if (binds != null) {
                for (Object o : binds) {
                    if (!(o instanceof byte[])) continue;
                    WxClient.Pb bi = WxClient.parse((byte[]) o);
                    Long u = bi.l(1);
                    WxClient.Pb di = bi.sub(2);
                    String name = di == null ? null : di.s(1);
                    if (name == null || name.isEmpty()) name = "未知设备";
                    list.add(new Dev(name, u == null ? 0 : u, u != null && u == ownUin));
                }
            }
            list.sort((a, b) -> Boolean.compare(b.self, a.self));
            devs = list;
            // [临时诊断] 设备组里到底有哪些设备（PC 端在不在这个组里）
            StringBuilder dsb = new StringBuilder();
            for (Dev dv : list) {
                dsb.append(dv.name).append('/').append(dv.uin)
                        .append(dv.self ? "(self)" : "").append(';');
            }
            String dsig = dsb.toString();
            if (!dsig.equals(diagDevSig)) {
                diagDevSig = dsig;
                MainHookLogger.log("event=devs_n n=" + list.size() + " [" + dsig + "]");
            }
        } catch (Throwable t) { MainHookLogger.log("event=devs_err " + t); }

        // ---- 拉取远端剪贴板 ----
        WxClient.Pb clip = WxClient.clipboardOf(g);
        if (clipboardEnabled && clip != null) {
            Long ver = clip.l(1);
            Long type = clip.l(2);
            byte[] data = clip.b(6);
            Long from = clip.l(7);
            boolean isText = type != null && type == WxClient.CLIP_TYPE_TEXT_ORI;
            boolean isImage = type != null && type == WxClient.CLIP_TYPE_IMAGE_ORI;
            boolean isCos = type != null && type == WxClient.CLIP_TYPE_COS;
            // type=2/4：PC 端（微信输入法 Windows）还有第三种图片引用形态 —— f6 里既不是
            // 内联图字节（type=3），也不是 {"path":…} 的 JSON（type=6），而是一段**纯文本**：
            // COS 直链，后面可能跟 md5 / AES key。2026-11-16 实测两次（16:29、16:40）都因为
            // 没有任何分支匹配而被静默丢弃 —— 用户看到的就是「PC 复制图片，手机没反应」。
            boolean isCosRef = type != null && (type == 2 || type == 4);

            // [临时诊断] 记录服务端返回的剪贴板头，用于判定 PC 端有没有上传内容、
            // 以及上传的是哪种 type（1=文字 3=图片内联 4=图片走 COS）。
            // 只在签名变化时落一行，避免刷屏。
            try {
                byte[] bin = clip.b(6);
                String tx = clip.s(3);
                String sig = "ver=" + ver + " type=" + type + " from=" + from
                        + " bin=" + (bin == null ? -1 : bin.length)
                        + " txt=" + (tx == null ? -1 : tx.length());
                if (!sig.equals(diagClipSig)) {
                    diagClipSig = sig;
                    MainHookLogger.log("event=clip_hdr " + sig);
                    // [临时诊断] 把未知 type 的内容打个指纹，用来判断它是图片字节、
                    // COS 路径、还是文件列表。
                    if (bin != null && bin.length > 0) {
                        StringBuilder hx = new StringBuilder();
                        int hn = Math.min(16, bin.length);
                        for (int i = 0; i < hn; i++) {
                            hx.append(String.format("%02X", bin[i]));
                        }
                        StringBuilder asc = new StringBuilder();
                        int an = Math.min(120, bin.length);
                        for (int i = 0; i < an; i++) {
                            int c = bin[i] & 0xFF;
                            asc.append(c >= 32 && c < 127 ? (char) c : '.');
                        }
                        MainHookLogger.log("event=clip_bin head=" + hx + " asc=" + asc);
                    }
                    if (tx != null && tx.length() > 0) {
                        MainHookLogger.log("event=clip_txtdata v="
                                + (tx.length() > 160 ? tx.substring(0, 160) : tx));
                    }
                    // type=2/4 的 f6 是纯文本（COS 直链，可能还带 md5/key），
                    // 把开头一段完整打出来，便于确认分隔符与字段顺序。
                    if (isCosRef && bin != null && bin.length > 0) {
                        try {
                            String raw = new String(bin, "UTF-8");
                            if (raw.length() > 700) raw = raw.substring(0, 700);
                            MainHookLogger.log("event=clip_ref_raw len=" + bin.length + " raw=" + raw);
                        } catch (Throwable ignore) {}
                    }
                }
            } catch (Throwable ignore) {}

            // 首次运行没有状态文件：服务端返回的是「组里最后一次剪贴板」，
            // 直接应用会把用户此刻复制的东西覆盖成旧内容。这里只接水位、不动剪贴板。
            if (coldAdopt && ver != null && ver > 0) {
                coldAdopt = false;
                lastVersion = ver;
                saveClipState();
                MainHookLogger.log("event=clip_cold_adopt ver=" + ver + " type=" + type);
            } else if (isImage && ver != null && ver > lastVersion && data != null) {
                if (from != null && from == ownUin) {
                    lastVersion = ver;                       // 自己的图片回声
                    MainHookLogger.log("event=clipimg_echo ver=" + ver);
                } else if (imageSyncEnabled()) {
                    handleIncomingImage(data, from == null ? 0 : from, ver);
                } else {
                    lastVersion = ver;                       // 开关关着，只推进水位
                }
            } else if (isCos && ver != null && ver > lastVersion && data != null) {
                // PC 端（微信输入法 Windows）走的是「先传 COS，再发引用」这条路：
                // f6 里只有 {"version":1,"path":"https://…"}，图片本体在腾讯云 COS 上。
                // 必须自己下载，否则这条消息等于没收到。
                if (from != null && from == ownUin) {
                    lastVersion = ver;
                    MainHookLogger.log("event=clipcos_echo ver=" + ver);
                } else if (imageSyncEnabled()) {
                    handleIncomingCos(data, from == null ? 0 : from, ver);
                } else {
                    lastVersion = ver;
                }
            } else if (isCosRef && ver != null && ver > lastVersion && data != null) {
                // f6 是纯文本的 COS 直链：转成 handleIncomingCos 认的 JSON 形态后完全复用它的
                // 下载 / AES-CTR 解密 / 兜底当文本贴 的逻辑，避免两套实现走偏。
                if (from != null && from == ownUin) {
                    lastVersion = ver;
                    MainHookLogger.log("event=clipcos_ref_echo ver=" + ver);
                } else if (imageSyncEnabled()) {
                    String json = cosRefToJson(data);
                    if (json == null) {
                        lastVersion = ver;
                        MainHookLogger.log("event=clipcos_ref_bad ver=" + ver);
                    } else {
                        handleIncomingCos(json.getBytes("UTF-8"),
                                from == null ? 0 : from, ver);
                    }
                } else {
                    lastVersion = ver;
                }
            } else if (isText && ver != null && ver > lastVersion && data != null) {
                String text = new String(data, "UTF-8");
                if (text.indexOf(0) >= 0 || text.length() > 1024 * 1024) {
                    lastVersion = ver;                       // 不可信内容只推进水位
                } else if (from == null || from == ownUin) {
                    // 自己的回声，或归属不明（服务端未带 from）——两种都**不能**写剪贴板。
                    // 曾经的 bug：只判 from != null && from == ownUin，from 为 null 时
                    // 落进下面的「远端文本」分支，把本机自己上报的乱码原样写回，
                    // 覆盖掉刚写好的图片剪贴板。
                    lastVersion = ver;
                    MainHookLogger.log("event=clip_echo ver=" + ver + " from=" + from);
                } else {
                    lastVersion = ver;
                    String h = hashText(text);
                    boolean dup = recentDup(h, lastHash, lastHashAt)
                            || recentDup(h, lastTxHash, lastTxAt);
                    // 内容相同 → 只推进水位，不碰剪贴板：
                    // 既避免「同步出去的内容又被打回来」，也避免版本号抖动把用户刚复制的东西覆盖掉。
                    if (dup) {
                        lastLocalText = text;
                        lastHash = h;
                        saveClipState();
                        MainHookLogger.log("event=clip_rx_dup ver=" + ver + " len=" + text.length());
                    } else {
                        lastLocalText = text;                    // 防回环
                        lastHash = h;
                        lastTxHash = h;                          // 同一内容不再回传
                        lastHashAt = now();
                        lastTxAt = now();
                        lastRxAt = System.currentTimeMillis();
                        String curNow = XiaobuInject.currentClipText();
                        // 收到的内容一律先打「这是收来的」标记 —— 不管写没写、剪贴板里是不是
                        // 已经有同一段。否则「剪贴板里已有 → 不写 → 没打标记」这条缝隙会让
                        // 它在下一轮被判成用户新复制，发回对端，形成 A→B→A 的无限回声
                        // （2026-11-16 实测：PC 同步来的 1379 字被手机原样发回，版本号一路 +1）。
                        rxEchoHash = h;
                        boolean wrote;
                        if (text.equals(curNow)) {
                            wrote = true;                    // 剪贴板里已经是这段，不必再写
                        } else {
                            wrote = XiaobuInject.setSystemClipboard(text);
                        }
                        if (!wrote) {
                            // 键盘没弹（WRITE_CLIPBOARD=foreground）→ 写不进去，挂待写队列，
                            // 等键盘亮起 / 权限放开再补写。文本丢了就永远补不回来。
                            markPendingText(text, ver);
                            // 同一时刻宿主也读不到剪贴板（同一个门禁），它自己不会把这条记进历史，
                            // 面板里就会缺一条「系统剪贴板有、列表里没有」的假不同步 —— 模块替它补录。
                            // 只在 wrote==false 时补：读得到的时候宿主自己会记，补了反而重复。
                            try { XiaobuClipDb.recordText(XiaobuInject.ctx, text); }
                            catch (Throwable ignore) {}
                        }
                        // 记下「最近一次收到的文本」：剪贴板被系统/linker 清空后，
                        // 键盘亮起时还能把它补回去（版本号不变也能补）。
                        saveLastRxText(text);
                        // 记下写入后的时间戳：否则下一轮轮询会把「我们自己写进去的内容」
                        // 当成用户新复制（虽然还有 hash 去重兜底，但没必要多走一遍）
                        lastSeenClipTs = XiaobuInject.currentClipTs();
                        // 落盘要放在写之后：rxEchoHash 必须一起存进去，
                        // 否则进程重启后「这段是收来的」就忘了，第一轮会把它发回对端。
                        saveClipState();
                        MainHookLogger.log("event=clip_rx ver=" + ver + " from=" + from + " len=" + text.length());
                        statusText = "收到剪贴板 " + text.length() + " 字";
                    }
                }
            }
        }

        // 待写文本补写：键盘没弹时收进来的内容，每轮轻量重试一次（内部有 1s 节流 + 三道判据）
        try { flushPendingText("tick"); } catch (Throwable t) {
            MainHookLogger.log("event=clip_pending_err " + t);
        }

        // ---- 发布本地剪贴板 ----
        // 触发条件（三者任一）：
        //   ① 内容变了（hash 不同）—— 普通复制；
        //   ② 时间戳变了（内容可以相同）—— 用户又复制了一次同一段文字；
        //   ③ startupPending —— 输入法进程刚起、而剪贴板里是「没来得及同步」的新内容。
        // 旧实现只看 ①（且用字符串比较），②③ 都会被漏掉。
        String cur = XiaobuInject.currentClipText();
        long curTs = XiaobuInject.currentClipTs();
        if (clipboardEnabled && cur != null) {
            String h = hashText(cur);
            boolean contentChanged = !cur.equals(lastLocalText);
            boolean stampChanged = curTs > 0 && curTs != lastSeenClipTs;
            boolean startup = startupPending;
            // 键盘亮起带来的「补发评估」，只消费一次
            boolean catchup = imeCatchup;
            imeCatchup = false;
            if (!contentChanged && !stampChanged && !startup) {
                // 什么都没变：不进入下面的判断（避免每 2 秒空转打日志）
                if (catchup) {
                    // 键盘亮起后评估的结果：剪贴板里仍是上次已确认过的那一条。
                    // 按设计不补发、更不回写剪贴板 —— 回写只会刷新时间戳，
                    // 反过来被自己当成一次新的复制事件。
                    MainHookLogger.log("event=clip_catchup_same ts=" + curTs);
                }
                if (now() - lastStateSave > 30_000L) saveClipState();
            } else {
            long prevSeenTs = lastSeenClipTs;
            String prevText = lastLocalText;
            lastLocalText = cur;
            lastSeenClipTs = curTs;
            startupPending = false;
            if (!contentChanged) {
                // 内容没变、只是时间戳变了：说明剪贴板被**别的进程**重新 set 了一次
                // （现象：同一段文字每隔几十秒被重发一次）。记下 label 归属便于定位来源。
                MainHookLogger.log("event=clip_stamp_only len=" + cur.length()
                        + " ts=" + curTs + " desc=" + XiaobuInject.currentClipDesc());
            }
            long nowMs = System.currentTimeMillis();
            // 注意：这里**不再**用 10 分钟内容指纹拦「用户主动复制」。
            // 2026-11-10 反馈：IME 没运行时复制的内容，唤醒后因指纹命中被当成重复而丢弃。
            // 语义澄清：指纹只用于「接收侧不重复写剪贴板」，发送侧只防「自己触发的回声」。
            boolean justSent = lastTxHash != null && lastTxHash.equals(h)
                    && lastTxAt > 0 && (nowMs - lastTxAt) < SELF_ECHO_MS;
            boolean tooOld = startup && curTs > 0 && (nowMs - curTs) >= STARTUP_MAX_AGE_MS;
            if (rxEchoHash != null && rxEchoHash.equals(h)) {
                // 这条是对端同步进来的，不是用户新复制：吞掉一次，绝不发回去
                rxEchoHash = null;
                MainHookLogger.log("event=clip_tx_skip reason=rx_echo");
            } else if (tooOld) {
                MainHookLogger.log("event=clip_tx_skip reason=startup_stale age="
                        + (nowMs - curTs));
            } else if (nowMs - lastRxAt < RX_SUPPRESS_MS) {
                // 刚写完对端内容：3 秒抑制窗，杜绝「A→B 后 B 立刻回传 A」
                lastHash = h;
                MainHookLogger.log("event=clip_tx_skip reason=just_applied");
            } else if (justSent) {
                // 2 秒内同内容只发一次（消除「内容变+时间戳变」双命中）
                MainHookLogger.log("event=clip_tx_dup why=just_sent");
            } else if (!cur.isEmpty() && cur.length() <= 256 * 1024) {
                String trace = WxClient.randomHex(32);
                long v;
                try {
                    v = wx.publish(groupId, cur, trace);
                } catch (Throwable txErr) {
                    // 抛异常同样算「没发出去」：水位退回去，下一轮再试
                    rollbackTx(prevSeenTs, prevText);
                    MainHookLogger.log("event=clip_tx_fail v=-1 len=" + cur.length()
                            + " err=" + txErr);
                    throw txErr;
                }
                if (v > 0) {
                    txFailSince = 0;
                    if (v > lastVersion) lastVersion = v;
                    lastTxHash = h;
                    lastHash = h;
                    lastTxAt = now();
                    lastHashAt = lastTxAt;
                    saveClipState();
                    MainHookLogger.log("event=clip_tx ver=" + v + " len=" + cur.length());
                    statusText = "已发送剪贴板 " + cur.length() + " 字";
                } else {
                    rollbackTx(prevSeenTs, prevText);
                    MainHookLogger.log("event=clip_tx_fail v=" + v + " len=" + cur.length());
                }
            }
            }
        }
        if (now() - lastStateSave > 30_000L) saveClipState();
        // ---- 常用语定时同步（仅在「同步常用语」开关打开时，60s 一次）----
        // 不做 2s 级轮询：cmd34 带热词是全量报文，没必要那么频繁。
        if ((funcMask & WxClient.FUNC_HOT_WORDS) != 0) {
            long now = System.currentTimeMillis();
            if (now - lastHwSync > HOTWORD_SYNC_MS) {
                lastHwSync = now;
                try { syncHotWordsNow(null); } catch (Throwable t) {
                    MainHookLogger.log("event=hotwords_tick_err " + t);
                }
            }
        }
        // ---- 个人词库定时同步（微信输入法 → 小布，5 分钟一次）----
        // 纯本地操作，不依赖设备组；只有确实新增了词条才重启词库引擎。
        if (userDictEnabled() && System.currentTimeMillis() - lastUdSync > USERDICT_SYNC_MS) {
            try { syncUserDictNow(null); } catch (Throwable t) {
                MainHookLogger.log("event=userdict_tick_err " + t);
            }
        }
        // 本地复制事件：立刻再跑一轮（拉取 + 发布），把延迟压到最小
        if (pendingFlush) {
            pendingFlush = false;
            try { poll(); } catch (Throwable t) { MainHookLogger.log("event=flush_err " + t); }
        }
    }

    /**
     * 发布失败时把「已确认水位」退回上一次。
     *
     * 旧实现无论发没发出去都认下 lastSeenClipTs，于是**一次失败就等于把这次复制永久丢弃**：
     * 内容没变、时间戳也标记成「已处理」，之后每轮都判成「什么都没变」，再也不发。
     * IME 刚解冻时网络栈往往还没恢复，这是最典型的一次性失败场景（用户看到的现象就是
     * 「键盘唤醒后还是没同步过去」）。退回水位后，下一轮轮询会重新判成「时间戳变了」再发一次。
     *
     * 用 txFailSince 限一个 10 分钟窗口：长时间离线时不至于每 2 秒打一次网络。
     */
    private void rollbackTx(long prevSeenTs, String prevText) {
        long nowMs = System.currentTimeMillis();
        if (txFailSince == 0) txFailSince = nowMs;
        if (nowMs - txFailSince > TX_RETRY_MAX_MS) {
            // 太久发不出去：放弃这次补发，认下当前水位（下一个复制事件会重新触发）
            txFailSince = 0;
            MainHookLogger.log("event=clip_tx_retry_giveup");
            return;
        }
        lastSeenClipTs = prevSeenTs;
        lastLocalText = prevText;
    }

    // ================= 个人词库：微信输入法 → 小布 =================

    private static volatile long lastUdSync = 0;
    private static final long USERDICT_SYNC_MS = 5 * 60 * 1000L;
    private static final java.util.concurrent.atomic.AtomicBoolean udSyncing =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 「同步个人词库」开关（本地保存：这条链路不需要设备组） */
    public static boolean userDictEnabled() {
        try {
            String s = read("ud_on.txt");
            return s != null && s.trim().startsWith("1");
        } catch (Throwable t) { return false; }
    }

    /** 图片同步开关；文件不存在时默认开启（不写文件即视为开） */
    public static boolean imageSyncEnabled() {
        try {
            String s = read("img_on.txt");
            if (s == null || s.trim().isEmpty()) return true;   // 缺省开
            return s.trim().startsWith("1");
        } catch (Throwable t) { return true; }
    }

    public static void setImageSyncEnabled(boolean on) {
        java.io.FileOutputStream fos = null;
        try {
            fos = new java.io.FileOutputStream(new File(dir(), "img_on.txt"));
            fos.write((on ? "1" : "0").getBytes("UTF-8"));
            fos.flush();
        } catch (Throwable t) {
            MainHookLogger.log("event=img_switch_write_fail " + t);
        } finally {
            if (fos != null) try { fos.close(); } catch (Throwable ignore) {}
        }
    }

    public static void setUserDictEnabled(boolean on) {
        try {
            java.io.FileOutputStream fo =
                    new java.io.FileOutputStream(new File(dir(), "ud_on.txt"), false);
            fo.write((on ? "1" : "0").getBytes("UTF-8"));
            fo.close();
        } catch (Throwable t) {
            MainHookLogger.log("event=userdict_flag_fail " + t);
        }
    }


    public static void syncUserDictNow(final DevOpCb cb) {
        MainHookLogger.log("event=udsync_enter diag=" + DictDiag.isRunning()
                + " busy=" + udSyncing.get() + " enabled=" + userDictEnabled());
        if (DictDiag.isRunning()) {
            MainHookLogger.log("event=userdict_skip reason=diag");
            if (cb != null) cb.onResult(true, "诊断进行中…");
            return;
        }
        if (!udSyncing.compareAndSet(false, true)) {
            if (cb != null) cb.onResult(true, "词库同步进行中…");
            return;
        }
        new Thread(() -> {
            boolean ok = false;
            String msg = "词库同步失败";
            try {
                lastUdSync = System.currentTimeMillis();
                android.content.Context ctx = XiaobuInject.ctx;
                MainHookLogger.log("event=udsync_thread ctx=" + (ctx != null));
                if (ctx == null) { msg = "输入法未就绪"; return; }
                java.io.File d = new java.io.File(ctx.getFilesDir(), "wtsync");
                d.mkdirs();
                WtClient c = active;

                // 引擎刚启动时 active 还是 null、groupId 也还没拿到（cmd34 还在路上）。
                // 这里等一会儿再判断，否则用户刚打开页面就点同步会误报「尚未关联设备」。
                for (int i = 0; i < 30 && (c == null || c.groupId <= 0); i++) {
                    try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                    c = active;
                }
                MainHookLogger.log("event=udsync_ready client=" + (c != null)
                        + " gid=" + (c == null ? -1 : c.groupId) + " since=" + readMsgId(d));

                // ① 首选：跟随微信客户端的做法，用 msgId 游标拉增量（完全不依赖微信客户端）
                if (c != null && c.paired && c.groupId > 0) {
                    long since = readMsgId(d);
                    WxClient.DictDelta delta = c.wx.fetchDictDelta(c.groupId, since);
                    // 游标失效自愈：服务端对「太旧 / 不在保留窗口」的 since 会直接报错
                    // （实测 err=10024；此时拿不到任何数据，等于词库同步永久卡死）。
                    // 做法是归零重来一次 —— since=0 的请求不带游标，服务端会回「最新一批 + 新游标」。
                    if (delta == null && since > 0) {
                        MainHookLogger.log("event=userdict_cursor_reset since=" + since);
                        writeMsgIdForce(d, 0);
                        since = 0;
                        delta = c.wx.fetchDictDelta(c.groupId, 0);
                    }
                    if (delta != null) {
                        writeMsgId(d, delta.msgId);
                        if (delta.entries.isEmpty()) {
                            ok = true;
                            msg = "个人词库已是最新（" + (since > 0 ? "游标 " + delta.msgId : "暂无新词") + "）";
                            MainHookLogger.log("event=userdict_sync ok=true via=inc empty msgId=" + delta.msgId);
                            return;
                        }
                        int added = XiaobuDictSync.mergeInto(d, delta.entries);
                        if (added < 0) { msg = "写入小布词库失败"; return; }
                        ok = true;
                        msg = added == 0
                                ? "词库已是最新（本次拉到 " + delta.entries.size() + " 条变更）"
                                : "已同步 " + added + " 条新词（本次拉到 " + delta.entries.size() + " 条变更）";
                        MainHookLogger.log("event=userdict_sync ok=true via=inc added=" + added
                                + " got=" + delta.entries.size() + " msgId=" + delta.msgId);
                        return;
                    }
                    MainHookLogger.log("event=userdict_inc_fail fallback_bridge");
                }

                // ② 未入组 / 增量失败：没有设备组就没有共享词库，只能提示
                if (c == null || !c.paired || c.groupId <= 0) {
                    msg = "尚未关联设备：个人词库存放在设备组里，先关联一台设备";
                } else {
                    msg = "拉取词库失败，稍后再试";
                }
                MainHookLogger.log("event=userdict_no_source paired="
                        + (c != null && c.paired) + " gid=" + (c == null ? -1 : c.groupId));
            } catch (Throwable t) {
                MainHookLogger.log("event=userdict_sync_err " + t);
                msg = "词库同步失败：" + t.getMessage();
            } finally {
                udSyncing.set(false);
                if (cb != null) cb.onResult(ok, msg);
            }
        }, "WtUserDict").start();
    }

    /** 增量游标（= 微信的 local_msg_id），存本地文件 */
    private static long readMsgId(java.io.File dir) {
        try {
            String s = read("ud_msgid.txt");
            if (s == null) return 0;
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 强制写游标（允许写 0：用于「游标失效 → 归零重来」） */
    private static void writeMsgIdForce(java.io.File dir, long id) {
        try {
            java.io.FileOutputStream fo =
                    new java.io.FileOutputStream(new java.io.File(dir, "ud_msgid.txt"), false);
            fo.write(String.valueOf(id).getBytes("UTF-8"));
            fo.close();
        } catch (Throwable t) {
            MainHookLogger.log("event=ud_msgid_write_fail " + t);
        }
    }

    private static void writeMsgId(java.io.File dir, long id) {
        if (id <= 0) return;
        try {
            java.io.FileOutputStream fo =
                    new java.io.FileOutputStream(new java.io.File(dir, "ud_msgid.txt"), false);
            fo.write(String.valueOf(id).getBytes("UTF-8"));
            fo.close();
        } catch (Throwable t) {
            MainHookLogger.log("event=ud_msgid_write_fail " + t);
        }
    }

    // ================= 配对：本机出码（对端来加入） =================
    public void startIssue() {
        issueCode = null;
        new Thread(() -> {
            try {
                String[] r = wx.requestCode();
                if (r == null) { statusText = "申请关联码失败"; return; }
                final String code = r[0];
                issueCode = code;
                statusText = "关联码 " + code + "，请在另一台设备上输入";
                MainHookLogger.log("event=issue_code " + code);
                for (int i = 0; i < 75 && running; i++) {
                    Thread.sleep(2000);
                    WxClient.Pb p = wx.pollCode(code);
                    Long state = p.l(1);
                    if (state == null) continue;
                    if (state == 1) {
                        String bindId = p.s(2);
                        WxClient.Pb dev = p.sub(4);
                        String peer = dev == null ? "未知设备" : dev.s(1);
                        statusText = "「" + peer + "」请求关联，确认中…";
                        MainHookLogger.log("event=peer_join " + peer);
                        long gid = wx.confirmBind(bindId);
                        if (gid > 0) {
                            groupId = gid;
                            paired = true;
                            statusText = "关联成功（设备组 " + gid + "）";
                            MainHookLogger.log("event=paired gid=" + gid);
                        } else {
                            statusText = "确认失败";
                        }
                        issueCode = null;
                        return;
                    }
                    if (state == 2) { statusText = "关联码已失效"; issueCode = null; return; }
                }
                statusText = "等待超时";
                issueCode = null;
            } catch (Throwable t) {
                MainHookLogger.log("event=issue_err " + t);
                statusText = "出码失败: " + t;
            }
        }, "WtIssue").start();
    }

    // ================= 配对：输入对端码加入 =================
    /** 关联结果回调（供弹窗提示：成功 / 匹配码错误 / 超时） */
    public interface JoinCb { void onResult(boolean ok, String msg); }

    /** 设备管理结果回调（改名 / 解除关联） */
    public interface DevOpCb { void onResult(boolean ok, String msg); }

    public static volatile JoinCb joinCb;

    private static void reportJoin(boolean ok, String msg) {
        JoinCb cb = joinCb;
        joinCb = null;
        if (cb != null) {
            try { cb.onResult(ok, msg); } catch (Throwable ignore) {}
        }
    }

    public void startJoin(final String code) {
        if (code == null || code.length() != 6) {
            statusText = "关联码必须是 6 位";
            reportJoin(false, "关联码必须是 6 位");
            return;
        }
        statusText = "正在关联…";
        new Thread(() -> {
            try {
                String id = wx.join(code);
                if (id == null) {
                    statusText = "关联失败，请检查关联码";
                    reportJoin(false, "关联码不正确，请检查后重试");
                    return;
                }
                joinBindId = id;
                statusText = "已提交，等待对方确认…";
                MainHookLogger.log("event=join_ok bind=" + id);
                for (int i = 0; i < 75 && running; i++) {
                    Thread.sleep(2000);
                    int state = wx.fetchBind(id);
                    if (state == 1) {
                        paired = true;
                        statusText = "关联成功";
                        MainHookLogger.log("event=join_complete");
                        reportJoin(true, "关联成功");
                        return;
                    }
                    if (state == 2) {
                        statusText = "关联已取消";
                        reportJoin(false, "关联已取消");
                        return;
                    }
                }
                statusText = "等待确认超时";
                reportJoin(false, "等待确认超时");
            } catch (Throwable t) {
                MainHookLogger.log("event=join_err " + t);
                statusText = "关联失败: " + t;
                reportJoin(false, "关联失败：" + t.getMessage());
            }
        }, "WtJoin").start();
    }

    /**
     * 修改设备名称（服务器侧备注名）。
     * 走 cmd79 UpdateDeviceInfoReq，服务端保存后，同组其它设备下一次 cmd34 即可看到新名字。
     */
    public static void renameDevice(final long targetUin, final String name, final DevOpCb cb) {
        final WtClient c = active;
        if (c == null) { if (cb != null) cb.onResult(false, "同步引擎未运行"); return; }
        final String nm = name == null ? "" : name.trim();
        if (nm.isEmpty()) { if (cb != null) cb.onResult(false, "名称不能为空"); return; }
        new Thread(() -> {
            boolean ok = false;
            String msg;
            try {
                ok = c.wx.updateDeviceRemark(targetUin, nm);
                msg = ok ? "名称已修改" : "修改失败，请检查网络后重试";
            } catch (Throwable t) {
                MainHookLogger.log("event=rename_err " + t);
                msg = "修改失败：" + t.getMessage();
            }
            MainHookLogger.log("event=rename_done ok=" + ok + " uin=" + targetUin);
            if (ok) c.refreshGroupNow();
            if (cb != null) cb.onResult(ok, msg);
        }, "WtRename").start();
    }

    /** 解除某台设备的关联（cmd35 UnbindReq） */
    public static void unbindDevice(final long targetUin, final DevOpCb cb) {
        final WtClient c = active;
        if (c == null) { if (cb != null) cb.onResult(false, "同步引擎未运行"); return; }
        // 安全护栏：不允许解除自己（否则会把自己踢出设备组）
        if (targetUin == 0 || targetUin == c.ownUin) {
            MainHookLogger.log("event=unbind_self_guard uin=" + targetUin);
            if (cb != null) cb.onResult(false, "不能解除本机设备");
            return;
        }
        new Thread(() -> {
            boolean ok = false;
            String msg;
            try {
                ok = c.wx.unbindDevice(targetUin);
                msg = ok ? "已解除关联" : "解除失败，请检查网络后重试";
            } catch (Throwable t) {
                MainHookLogger.log("event=unbind_err " + t);
                msg = "解除失败：" + t.getMessage();
            }
            MainHookLogger.log("event=unbind_done ok=" + ok + " uin=" + targetUin);
            if (ok) c.refreshGroupNow();
            if (cb != null) cb.onResult(ok, msg);
        }, "WtUnbind").start();
    }

    /** 当前组功能开关位（1=剪贴板 2=常用语 4=个人词库 8=语音速记） */
    public static long funcSwitchMask() {
        WtClient c = active;
        return c == null ? 0 : c.funcMask;
    }

    /** 本机在组里的 uin */
    public long selfUin() { return ownUin; }

    public static boolean funcEnabled(int bit) {
        return (funcSwitchMask() & bit) != 0;
    }

    /**
     * 切换组功能开关：先按当前掩码改位，再 cmd39 写回服务器。
     * 这样既不会误关其它功能，也能让同组其它设备看到一致状态。
     */
    public static void setFuncSwitch(final int bit, final boolean on, final DevOpCb cb) {
        final WtClient c = active;
        if (c == null) { if (cb != null) cb.onResult(false, "同步引擎未运行"); return; }
        new Thread(() -> {
            boolean ok = false;
            String msg;
            try {
                long gid = c.groupId;
                if (gid <= 0) { if (cb != null) cb.onResult(false, "尚未关联设备"); return; }
                long mask = c.funcMask;
                long next = on ? (mask | bit) : (mask & ~bit);
                ok = c.wx.setGroupFuncSwitch(gid, next);
                if (ok) {
                    c.funcMask = next;
                    msg = on ? "已开启" : "已关闭";
                } else {
                    msg = "设置失败，请检查网络后重试";
                }
            } catch (Throwable t) {
                MainHookLogger.log("event=funcswitch_err " + t);
                msg = "设置失败：" + t.getMessage();
            }
            if (ok) c.refreshGroupNow();
            if (cb != null) cb.onResult(ok, msg);
        }, "WtFunc").start();
    }

    /** 发布热词：读当前 → 版本+1 → 追加条目 → 回写；成功后回读校验 */
    public boolean pushHotWords(java.util.List<byte[]> extra) {
        try {
            WxClient.Pb gr = wx.group(7);
            WxClient.Pb g = WxClient.groupOf(gr);
            if (g == null) return false;
            long gid = g.l(1) == null ? 0 : g.l(1);
            byte[] raw = g.b(4);
            WxClient.HotWords h = WxClient.parseHotWords(raw);
            StringBuilder sb = new StringBuilder();
            for (WxClient.HotWords.Entry e : h.parsed) sb.append('|').append(e.code).append('=').append(e.text);
            MainHookLogger.log("event=hotwords_cur v=" + h.version + " n=" + h.entries.size()
                    + " pub=" + h.publisher + " ts=" + h.ts + " data=" + (h.data == null ? -1 : h.data.length)
                    + " list=" + sb);
            java.util.List<byte[]> add = new java.util.ArrayList<>();
            if (extra != null) add.addAll(extra);
            // 基准版本必须原样回传（服务端乐观并发校验），只有 publisher/ts 允许覆盖。
            long[][] combos = new long[][]{
                    {ownUin, System.currentTimeMillis(), 0},        // P0 发布者=本机
                    {h.publisher, System.currentTimeMillis(), 1},   // P1 保留原发布者
                    {h.publisher, h.ts, 2},                         // P2 全保留
            };
            for (long[] cb : combos) {
                byte[] out = WxClient.buildHotWords(h, cb[0], cb[1], add);
                WxClient.HwRsp r = wx.sendUserHotWords(gid, out);
                MainHookLogger.log("event=hotwords_push p=" + cb[2] + " ok=" + r.ok()
                        + " errCode=" + r.errCode + " newVer=" + r.version
                        + " baseVer=" + h.version + " add=" + add.size());
                if (r.ok()) return true;
                // 版本冲突说明服务器版本已变，后面的组合也不会过，直接放弃
                if (r.errCode == 10012) break;
            }
            return false;
        } catch (Throwable t) {
            MainHookLogger.log("event=hotwords_push_err " + t);
            return false;
        }
    }

    /**
     * 常用语双向同步（「同步常用语」开关打开时生效）—— 增删都同步。
     *
     * 微信输入法的热词是**整份 data 快照**（UserHotWords.data），没有墓碑/删除标记，
     * 谁写谁覆盖。所以「远端没有」这一条信息不足以判断是「对端删了」还是「本地新加」，
     * 必须自己维护基线：把「上次同步时组里有哪些条目」存进 hw_synced.txt（每行一个 key 的 Base64）。
     *
     * 完整流程：
     *   ① 基线里在、这次组里没了  ⇒ 对端删除 → 本地 phraseDelete
     *   ② 基线里在、组里还在、本地没了 ⇒ 本机删除 → 记入 drop 列表，稍后连组一起删
     *   ③ 组里有、本地没有（且不在 drop 里）⇒ 拉进小布
     *   ④ 本地有、组里没有、基线里也没有 ⇒ 真正的新增 → 发布到组
     *   ⑤ 发布时把 drop 的条目从 data 里剔除，和新增合成一次 cmd37，避免两次版本推进
     */
    public static void syncHotWordsNow(final DevOpCb cb) {
        final WtClient c = active;
        if (c == null) { if (cb != null) cb.onResult(false, "同步引擎未运行"); return; }
        if (!hwSyncing.compareAndSet(false, true)) {
            MainHookLogger.log("event=hotwords_sync_skip busy");
            if (cb != null) cb.onResult(true, "同步正在进行中…");
            return;
        }
        new Thread(() -> {
          try {
            lastHwSync = System.currentTimeMillis();   // 手动触发也算一次心跳，避免紧接着又被定时器拉起
            boolean ok = false;
            String msg = "同步失败";
            int pulled = 0, pushed = 0, removed = 0, dropped = 0;
            try {
                if (!c.paired) { if (cb != null) cb.onResult(false, "尚未关联设备"); return; }
                long gid = c.groupId;
                if (gid <= 0) { if (cb != null) cb.onResult(false, "尚未关联设备"); return; }
                WxClient.Pb g = WxClient.groupOf(c.wx.group(7));
                if (g == null) { if (cb != null) cb.onResult(false, "读取组信息失败"); return; }
                WxClient.HotWords h = WxClient.parseHotWords(g.b(4));
                java.util.Set<String> prev = readSyncedKeys();
                boolean firstRun = (prev == null);
                if (firstRun) prev = new java.util.LinkedHashSet<>();
                MainHookLogger.log("event=hotwords_sync_start v=" + h.version + " remote=" + h.parsed.size()
                        + " local_before=" + XiaobuPhraseStore.count()
                        + " prev=" + prev.size() + (firstRun ? " (seed)" : ""));

                // 组里当前条目
                java.util.LinkedHashMap<String, WxClient.HotWords.Entry> remoteMap =
                        new java.util.LinkedHashMap<>();
                for (WxClient.HotWords.Entry e : h.parsed) {
                    remoteMap.put(XiaobuPhraseStore.key(e.code, e.text), e);
                }
                java.util.Set<String> remote = new java.util.LinkedHashSet<>(remoteMap.keySet());

                java.util.List<XiaobuPhraseStore.Phrase> local = XiaobuPhraseStore.list();
                java.util.Set<String> localKeys = new java.util.LinkedHashSet<>();
                for (XiaobuPhraseStore.Phrase p : local) localKeys.add(p.key());

                // ---------- ② 本机删除 → 记入 drop（先算，避免 ③ 又把它拉回来）----------
                java.util.Set<String> dropKeys = new java.util.LinkedHashSet<>();
                if (!firstRun && !localKeys.isEmpty()) {
                    for (String k : remote) {
                        if (localKeys.contains(k)) continue;
                        if (!prev.contains(k)) continue;   // 从没同步过，不算删除
                        dropKeys.add(k);
                    }
                    // 安全阀：本地一条不剩却要清空整个组，更像是引擎查询异常，不动手。
                    if (!dropKeys.isEmpty() && dropKeys.size() == remote.size() && localKeys.isEmpty()) {
                        MainHookLogger.log("event=hotwords_drop_guard n=" + dropKeys.size());
                        dropKeys.clear();
                    }
                    if (!dropKeys.isEmpty()) {
                        MainHookLogger.log("event=hotwords_local_deleted n=" + dropKeys.size());
                    }
                }

                // ---------- ① 对端删除 → 本地跟着删 ----------
                if (!firstRun) {
                    java.util.List<XiaobuPhraseStore.Phrase> doomed = new java.util.ArrayList<>();
                    for (XiaobuPhraseStore.Phrase p : local) {
                        String k = p.key();
                        if (remote.contains(k)) continue;
                        if (!prev.contains(k)) continue;   // 本地独有（还没推上去），不能动
                        doomed.add(p);
                    }
                    // 安全阀：要删光全部本地条目且组里一条不剩，更像是读到了被重置的空组。
                    boolean looksWiped = !local.isEmpty() && remote.isEmpty()
                            && doomed.size() == local.size() && local.size() >= 3;
                    if (looksWiped) {
                        MainHookLogger.log("event=hotwords_delete_guard doomed=" + doomed.size()
                                + " local=" + local.size() + " remote=0");
                    } else if (!doomed.isEmpty()) {
                        for (XiaobuPhraseStore.Phrase p : doomed) {
                            if (XiaobuPhraseStore.remove(p)) removed++;
                        }
                        MainHookLogger.log("event=hotwords_deleted n=" + removed + " want=" + doomed.size());
                    }
                }

                // ---------- ③ 下行：组 → 小布（跳过待删除的）----------
                java.util.List<XiaobuPhraseStore.Phrase> in = new java.util.ArrayList<>();
                for (java.util.Map.Entry<String, WxClient.HotWords.Entry> en : remoteMap.entrySet()) {
                    if (dropKeys.contains(en.getKey())) continue;
                    WxClient.HotWords.Entry e = en.getValue();
                    if (e.text == null || e.text.trim().isEmpty()) continue;
                    XiaobuPhraseStore.Phrase p = new XiaobuPhraseStore.Phrase();
                    p.content = e.text;
                    p.code = e.code == null ? "" : e.code;
                    in.add(p);
                }
                pulled = XiaobuPhraseStore.importMissing(in);

                // ---------- ④ 上行：新增（本地有、组里没有、基线里也没有）----------
                java.util.List<byte[]> add = new java.util.ArrayList<>();
                java.util.List<String> addKeys = new java.util.ArrayList<>();
                long idBase = System.currentTimeMillis();
                if (firstRun) {
                    // 首次运行没有基线：只建快照，不做任何上行，
                    // 否则会把「历史上早就被删掉、而本地还留着」的条目一口气全推回去。
                    writeSyncedKeys(remote);
                    MainHookLogger.log("event=hotwords_seed n=" + remote.size());
                } else {
                    java.util.Set<String> nowLocal = new java.util.LinkedHashSet<>();
                    for (XiaobuPhraseStore.Phrase p : XiaobuPhraseStore.list()) {
                        if (p.content == null || p.content.trim().isEmpty()) continue;
                        String k = p.key();
                        nowLocal.add(k);
                        if (remote.contains(k)) continue;
                        if (prev.contains(k)) continue;   // 已按「对端删除」处理（本地也删了），不回推
                        add.add(WxClient.buildHotWordEntry(idBase--, p.code, p.content));
                        addKeys.add(k);
                    }
                    if (!dropKeys.isEmpty()) dropped = dropKeys.size();
                }

                // ---------- ⑤ 一次发布：剔除 drop + 追加 add ----------
                if (!add.isEmpty() || !dropKeys.isEmpty()) {
                    java.util.List<byte[]> keep = new java.util.ArrayList<>();
                    for (int i = 0; i < h.parsed.size(); i++) {
                        WxClient.HotWords.Entry e = h.parsed.get(i);
                        if (dropKeys.contains(XiaobuPhraseStore.key(e.code, e.text))) continue;
                        keep.add(h.entries.get(i));
                    }
                    h.entries.clear();
                    h.entries.addAll(keep);
                    byte[] out = WxClient.buildHotWords(h, c.ownUin, System.currentTimeMillis(), add);
                    WxClient.HwRsp r = c.wx.sendUserHotWords(gid, out);
                    MainHookLogger.log("event=hotwords_sync_push ok=" + r.ok() + " errCode=" + r.errCode
                            + " baseVer=" + h.version + " newVer=" + r.version
                            + " add=" + add.size() + " drop=" + dropKeys.size());
                    if (r.ok()) {
                        pushed = add.size();
                    } else {
                        if (cb != null) cb.onResult(false, "发布到组失败（errCode=" + r.errCode + "）");
                        return;
                    }
                }
                ok = true;
                // 更新基线：组里的 - 已删的 + 这次推上去的
                remote.removeAll(dropKeys);
                remote.addAll(addKeys);
                writeSyncedKeys(remote);
                StringBuilder extra = new StringBuilder();
                if (removed > 0) extra.append("，删除 ").append(removed).append(" 条");
                if (dropped > 0) extra.append("，同步删除 ").append(dropped).append(" 条");
                msg = "同步完成：拉取 " + pulled + " 条，推送 " + pushed + " 条" + extra;
                MainHookLogger.log("event=hotwords_sync ok=" + ok + " pulled=" + pulled
                        + " pushed=" + pushed + " removed=" + removed + " dropped=" + dropped
                        + " local_after=" + XiaobuPhraseStore.count());
            } catch (Throwable t) {
                MainHookLogger.log("event=hotwords_sync_err " + t);
                msg = "同步失败：" + t.getMessage();
            }
            if (cb != null) cb.onResult(ok, msg);
          } finally {
            hwSyncing.set(false);     // 任何路径（含提前 return / 异常）都要解锁
          }
        }, "WtHwSync").start();
    }

    private static String b64(String s) {
        try {
            return android.util.Base64.encodeToString(s.getBytes("UTF-8"),
                    android.util.Base64.NO_WRAP);
        } catch (Throwable t) { return ""; }
    }

    private static String unb64(String s) {
        try {
            return new String(android.util.Base64.decode(s, android.util.Base64.NO_WRAP), "UTF-8");
        } catch (Throwable t) { return null; }
    }

    /** 读「上次同步时组里已有的条目」key 集合；返回 null 表示还没有基线（首次同步） */
    private static java.util.Set<String> readSyncedKeys() {
        String raw = read("hw_synced2.txt");
        if (raw == null) return null;
        java.util.Set<String> s = new java.util.LinkedHashSet<>();
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String k = unb64(t);
            if (k != null) s.add(k);
        }
        return s;
    }

    private static void writeSyncedKeys(java.util.Set<String> keys) {
        try {
            StringBuilder sb = new StringBuilder();
            for (String k : keys) sb.append(b64(k)).append('\n');
            java.io.FileOutputStream out =
                    new java.io.FileOutputStream(new File(dir(), "hw_synced2.txt"), false);
            out.write(sb.toString().getBytes("UTF-8"));
            out.close();
        } catch (Throwable t) {
            MainHookLogger.log("event=hw_synced_write_fail " + t);
        }
    }

    /** 当前组 id（0 = 未入组） */
    public long groupIdValue() { return groupId; }


    /** 立刻拉一次组信息（改名/解绑后让设备列表尽快刷新） */
    public void refreshGroupNow() {
        try {
            WxClient.Pb gr = wx.group(3);
            Long st = gr.l(2);
            if (st != null && st == 10020L) return;
            WxClient.Pb g = WxClient.groupOf(gr);
            if (g == null) return;
            java.util.List<Object> binds = g.m.get(2);
            java.util.List<Dev> list = new java.util.ArrayList<>();
            if (binds != null) {
                for (Object o : binds) {
                    if (!(o instanceof byte[])) continue;
                    WxClient.Pb bi = WxClient.parse((byte[]) o);
                    Long u = bi.l(1);
                    WxClient.Pb di = bi.sub(2);
                    String name = di == null ? null : di.s(1);
                    if (name == null || name.isEmpty()) name = "未知设备";
                    list.add(new Dev(name, u == null ? 0 : u, u != null && u == ownUin));
                }
            }
            list.sort((a, b) -> Boolean.compare(b.self, a.self));
            devs = list;
            Long fm2 = g.l(5);
            if (fm2 != null) funcMask = fm2;
            MainHookLogger.log("event=group_refresh n=" + list.size() + " func=" + funcMask);
        } catch (Throwable t) {
            MainHookLogger.log("event=group_refresh_err " + t);
        }
    }

    /** 匹配码关联（旧入口，保留兼容） */
    public void bindCode(final String code) { startJoin(code); }
}
