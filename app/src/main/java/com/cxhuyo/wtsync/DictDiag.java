package com.cxhuyo.wtsync;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 词库读写诊断（仅调试用，默认永不执行）。
 *
 * 触发方式：files/wtsync/diag.cmd 里写一行命令，模块每 1.5s 轮询一次，执行后删除该文件。
 * 命令格式：
 *   add|<N>|<前缀>     追加 N 个「<前缀>1..N」标记词，导入后分多个时间点导出计数
 *   export             只导出计数
 *   restart            只重启词库引擎后导出计数
 *   clean|<前缀>       把该前缀的词 commits 改成 0（软删除，恢复词库整洁）
 *
 * 全程后台线程，不碰 UI。
 */
final class DictDiag {

    private static volatile boolean running = false;
    private static volatile String lastMsg = "";

    static boolean isRunning() { return running; }

    static void schedule() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    for (int i = 0; i < 60 && XiaobuInject.ctx == null; i++) Thread.sleep(500);
                    if (XiaobuInject.ctx == null) return;
                    File dir = new File(XiaobuInject.ctx.getFilesDir(), "wtsync");
                    dir.mkdirs();
                    // 正式版默认不启用：没有 diag.on 开关文件就不起轮询线程，
                    // 免得在宿主的输入法进程里常驻一个 1.5s 唤醒循环白耗电。
                    if (!new File(dir, "diag.on").exists()) return;
                    loop(dir);
                } catch (Throwable t) {
                    MainHookLogger.log("event=diag_sched_err " + t);
                }
            }
        }, "WtDictDiag").start();
    }

    private static void loop(File dir) {
        MainHookLogger.log("event=diag_ready");
        File cmd = new File(dir, "diag.cmd");
        while (true) {
            sleep(1500);
            try {
                if (!cmd.exists()) continue;
                String text = readText(cmd);
                cmd.delete();
                if (text == null || text.trim().isEmpty()) continue;
                exec(dir, text.trim());
            } catch (Throwable t) {
                MainHookLogger.log("event=diag_err " + t);
            }
        }
    }

    /**
     * 直接操作宿主自己的数据库文件（模块与宿主同进程同 uid，开同一份 WAL 库是安全的）。
     *
     * 为什么不搬文件：tab_clipboard_data 和 tab_vocabulary（个人词库）在同一个库里，
     * 换文件会动 inode / SELinux 标签，风险远大于收益。这里只发一条 DELETE，
     * 交给 SQLite 事务处理；Room 的 AFTER DELETE 触发器会把变更通知给面板。
     */
    private static void clipDbCmd(String sub, String arg) {
        android.database.sqlite.SQLiteDatabase db = null;
        try {
            File f = XiaobuInject.ctx.getDatabasePath("KeyBoardDatabase");
            if (!f.exists()) {
                MainHookLogger.log("event=clipdb_nofile " + f);
                return;
            }
            db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    f.getAbsolutePath(), null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
                            | android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            long c0 = count(db, "tab_clipboard_data");
            long v0 = count(db, "tab_vocabulary");
            if ("clear".equals(sub)) {
                db.execSQL("delete from tab_clipboard_data");
            } else if ("dedup".equals(sub)) {
                // 同一段文本只留最早一行（清掉 v3.18.6 超长文本去重失效时插出的重复卡）
                db.execSQL("delete from tab_clipboard_data where clipboard_id not in "
                        + "(select min(clipboard_id) from tab_clipboard_data group by clipboard_text)");
            } else if ("trim".equals(sub)) {
                int keep = 500;
                try { keep = Integer.parseInt(arg); } catch (Throwable ignore) {}
                if (keep < 1) keep = 1;
                db.execSQL("delete from tab_clipboard_data where clipboard_id not in "
                        + "(select clipboard_id from tab_clipboard_data"
                        + " order by clipboard_time desc limit " + keep + ")");
            }
            long c1 = count(db, "tab_clipboard_data");
            long v1 = count(db, "tab_vocabulary");
            MainHookLogger.log("event=clipdb sub=" + sub + " keep=" + arg
                    + " clip=" + c0 + "->" + c1 + " vocab=" + v0 + "->" + v1);
        } catch (Throwable t) {
            MainHookLogger.log("event=clipdb_err " + t);
        } finally {
            try { if (db != null) db.close(); } catch (Throwable ignore) {}
        }
    }

    private static long count(android.database.sqlite.SQLiteDatabase db, String table) {
        return android.database.DatabaseUtils.longForQuery(db, "select count(*) from " + table, null);
    }

    private static void exec(File dir, String line) {
        String[] p = line.split("\\|");
        String op = p[0].trim();
        MainHookLogger.log("event=diag_cmd op=" + op + " arg=" + line);
        running = true;
        try {
            if (XiaobuInject.ctx == null || !XiaobuPhraseStore.init()) {
                MainHookLogger.log("event=diag_abort not_ready");
                return;
            }
            if ("export".equals(op)) {
                exportCount(dir, "x0.json");
            } else if ("exp".equals(op)) {
                exportCount(dir, p.length > 1 ? p[1].trim() : "x0.json");
            } else if ("imp".equals(op)) {
                // imp|<json 文件名>：直接把 PC 造好的 JSON 导进去，再导出计数
                String name = p[1].trim();
                int n0 = exportCount(dir, "y_base.json");
                long t0 = System.currentTimeMillis();
                boolean ok = XiaobuPhraseStore.importUserDict(new File(dir, name).getAbsolutePath());
                long cost = System.currentTimeMillis() - t0;
                int n1 = exportCount(dir, "y_after.json");
                MainHookLogger.log("event=diag_imp file=" + name + " ok=" + ok + " cost=" + cost
                        + "ms before=" + n0 + " after=" + n1 + " delta=" + (n1 - n0));
            } else if ("clip".equals(op)) {
                // clip|<文本>：直接写系统剪贴板，模拟「用户在本机复制了一段文字」。
                // 只用于回归验证「上报去重 / 防回环」，不参与任何正式逻辑。
                String t = line.length() > 5 ? line.substring(5) : "";
                XiaobuInject.setSystemClipboard(t);
                MainHookLogger.log("event=diag_clip len=" + t.length());
            } else if ("pub".equals(op)) {
                // pub|<文本>：绕过本地剪贴板，直接向设备组发布一条剪贴板，
                // 用来验证「自己发的消息回声回来时不会被写进剪贴板」。
                String t = line.length() > 4 ? line.substring(4) : "";
                long v = WtClient.publishForTest(t);
                MainHookLogger.log("event=diag_pub len=" + t.length() + " ver=" + v);
            } else if ("clipdbg".equals(op)) {
                // clipdbg：打印当前能否读到剪贴板、时间戳与去重状态（排障用）
                String t = XiaobuInject.currentClipText();
                long ts = XiaobuInject.currentClipTs();
                MainHookLogger.log("event=diag_clipdbg text=" + (t == null ? -1 : t.length())
                        + " ts=" + ts + " state=" + WtClient.clipStateForDiag());
            } else if ("catchup".equals(op)) {
                // catchup：模拟「键盘显示」事件，完整走一遍补发评估（排障用）。
                // 只读剪贴板、不改内容：内容没变时应当只留一行 clip_catchup_same。
                WtClient.onImeShown();
                MainHookLogger.log("event=diag_catchup");
            } else if ("clipdb".equals(op)) {
                // clipdb|stat        只统计（clip 历史条数 + 词库条数）
                // clipdb|trim|<n>    只保留最新 n 条剪贴板历史（宿主上限 500，但它自己没裁）
                // clipdb|clear       清空剪贴板历史（词库/常用语/设置一概不动）
                // 走宿主自己的数据库文件（模块就跑在输入法进程里、同 uid），不搬文件、不动 inode。
                clipDbCmd(p.length > 1 ? p[1].trim() : "stat",
                        p.length > 2 ? p[2].trim() : "500");
            } else if ("restart".equals(op)) {
                int a = exportCount(dir, "x0.json");
                XiaobuPhraseStore.restartVocabulary();
                sleep(3000);
                int b = exportCount(dir, "x1.json");
                MainHookLogger.log("event=diag_restart before=" + a + " after=" + b + " lost=" + (a - b));
            } else if ("py".equals(op)) {
                // py|<词>：看宿主给的拼音是什么
                String w = p[1].trim();
                MainHookLogger.log("event=diag_py word=" + w
                        + " host=" + XiaobuPhraseStore.wordPinyin(w));
            } else if ("rec".equals(op)) {
                // rec|<词>|<微信拼音>：看校准后的拼音
                String w = p[1].trim();
                String wx = p.length > 2 ? p[2].trim() : "";
                MainHookLogger.log("event=diag_rec word=" + w + " wx=" + wx
                        + " fixed=" + XiaobuPhraseStore.reconcilePinyin(w, wx));
            } else if ("add".equals(op)) {
                int n = Integer.parseInt(p[1].trim());
                String prefix = p.length > 2 ? p[2].trim() : "测试词";
                doAdd(dir, n, prefix);
            } else if ("clean".equals(op)) {
                String prefix = p.length > 1 ? p[1].trim() : "测试词";
                doClean(dir, prefix);
            } else if ("watch".equals(op)) {
                // watch|<当前词量>：直接跑一遍塌库看门狗（配合人为抬高 count.txt 基线做无损验证）
                int now = p.length > 1 ? Integer.parseInt(p[1].trim()) : exportCount(dir, "w0.json");
                boolean hit = XiaobuDictSync.checkCollapse(dir, now);
                MainHookLogger.log("event=diag_watch now=" + now + " collapsed=" + hit);
            } else if ("snap".equals(op)) {
                // snap|<词量>：手动留一份健康备份
                int now = p.length > 1 ? Integer.parseInt(p[1].trim()) : exportCount(dir, "s0.json");
                XiaobuDictSync.snapshotUdBinPub(dir, now);
                MainHookLogger.log("event=diag_snap n=" + now);
            } else {
                MainHookLogger.log("event=diag_unknown op=" + op);
            }
        } finally {
            running = false;
        }
    }

    /** 追加 N 个词 → 导入 → 立即 / 2s / 5s / 10s 各导出一次计数（观察是否为异步落地） */
    private static void doAdd(File dir, int n, String prefix) {
        int n0 = exportCount(dir, "x_base.json");
        String base = readText(new File(dir, "x_base.json"));
        if (base == null) { MainHookLogger.log("event=diag_abort no_base"); return; }

        StringBuilder add = new StringBuilder();
        long ts = System.currentTimeMillis() / 1000;
        StringBuilder pylog = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            String w = prefix + i;
            String py = XiaobuPhraseStore.wordPinyin(w);
            if (py.isEmpty()) py = pinyinOf(prefix) + " " + i;
            if (pylog.length() < 100) pylog.append('|').append(w).append('=').append(py);
            if (add.length() > 0) add.append(',');
            add.append("{\"word\":\"").append(XiaobuDictSync.escPub(w + "(" + py + ")"))
               .append("\",\"type\":1,\"timestamp\":").append(ts)
               .append(",\"commits\":2,\"commits_by_spelling\":{\"")
               .append(XiaobuDictSync.escPub(py.replace(' ', '_'))).append("\":2}}");
        }
        MainHookLogger.log("event=diag_add_py " + pylog);
        String merged = XiaobuDictSync.insertIntoArrayPub(base, "cn_words", add.toString());
        if (merged == null) { MainHookLogger.log("event=diag_abort splice_fail"); return; }
        File f = new File(dir, "x_add.json");
        writeText(f, merged);
        long t0 = System.currentTimeMillis();
        boolean ok = XiaobuPhraseStore.importUserDict(f.getAbsolutePath());
        long cost = System.currentTimeMillis() - t0;
        MainHookLogger.log("event=diag_add_import n=" + n + " ok=" + ok + " cost=" + cost + "ms");

        int n1 = exportCount(dir, "x_t0.json");
        MainHookLogger.log("event=diag_add_t0 base=" + n0 + " expect=" + (n0 + n) + " got=" + n1
                + " lost=" + (n0 + n - n1));
        sleep(2000);
        int n2 = exportCount(dir, "x_t2.json");
        MainHookLogger.log("event=diag_add_t2 got=" + n2 + " lost=" + (n0 + n - n2));
        sleep(5000);
        int n3 = exportCount(dir, "x_t7.json");
        MainHookLogger.log("event=diag_add_t7 got=" + n3 + " lost=" + (n0 + n - n3));
        sleep(10000);
        int n4 = exportCount(dir, "x_t17.json");
        MainHookLogger.log("event=diag_add_t17 got=" + n4 + " lost=" + (n0 + n - n4));
        MainHookLogger.log("event=diag_add_done n=" + n + " landed=" + (n4 - n0));
    }

    /** 把前缀匹配的词 commits 改成 0（软删除），恢复词库整洁 */
    private static void doClean(File dir, String prefix) {
        exportCount(dir, "x_clean0.json");
        String json = readText(new File(dir, "x_clean0.json"));
        if (json == null) return;
        int idx = 0, hit = 0;
        StringBuilder sb = new StringBuilder(json);
        String key = "\"word\":\"";
        List<int[]> spans = new ArrayList<>();
        while ((idx = json.indexOf(key, idx)) >= 0) {
            int vs = idx + key.length();
            int ve = vs;
            while (ve < json.length()) {
                char c = json.charAt(ve);
                if (c == '\\') { ve += 2; continue; }
                if (c == '"') break;
                ve++;
            }
            String w = json.substring(vs, ve);
            if (w.startsWith(prefix)) spans.add(new int[]{vs, ve});
            idx = ve;
        }
        // 从后往前替换，避免位移
        for (int i = spans.size() - 1; i >= 0; i--) {
            int[] s = spans.get(i);
            String w = json.substring(s[0], s[1]);
            String plain = w.indexOf('(') > 0 ? w.substring(0, w.indexOf('(')) : w;
            sb.replace(s[0], s[1], XiaobuDictSync.escPub(plain + "(zz)"));   // 改成无效拼音，等价于停用
            hit++;
        }
        if (hit == 0) { MainHookLogger.log("event=diag_clean none"); return; }
        File f = new File(dir, "x_clean.json");
        writeText(f, sb.toString());
        boolean ok = XiaobuPhraseStore.importUserDict(f.getAbsolutePath());
        MainHookLogger.log("event=diag_clean hit=" + hit + " ok=" + ok);
    }

    /** 粗糙拼音：只给诊断词用（取每个汉字的 unicode 低位拼一个假音节） */
    private static String pinyinOf(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("d").append(Integer.toHexString(s.charAt(i)));
        }
        return sb.toString();
    }

    private static int exportCount(File dir, String name) {
        File f = new File(dir, name);
        f.delete();
        boolean ok = XiaobuPhraseStore.exportUserDict(f.getAbsolutePath());
        String json = readText(f);
        int n = json == null ? -1 : countWords(json);
        MainHookLogger.log("event=diag_export file=" + name + " ok=" + ok
                + " len=" + (json == null ? -1 : json.length()) + " n=" + n);
        return n;
    }

    static int countWords(String json) {
        int n = 0, i = 0;
        String key = "\"word\":\"";
        while ((i = json.indexOf(key, i)) >= 0) { n++; i += key.length(); }
        return n;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignore) {}
    }

    private static String readText(File f) {
        try {
            FileInputStream fi = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fi.read(buf)) > 0) bos.write(buf, 0, n);
            fi.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) { return null; }
    }

    private static void writeText(File f, String s) {
        try {
            FileOutputStream fo = new FileOutputStream(f, false);
            fo.write(s.getBytes("UTF-8"));
            fo.close();
        } catch (Throwable t) { MainHookLogger.log("event=diag_write_fail " + t); }
    }
}
