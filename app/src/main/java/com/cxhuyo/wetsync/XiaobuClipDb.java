package com.cxhuyo.wetsync;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;

/**
 * 小布剪贴板库的【历史垃圾清理】。
 *
 * 需求已收敛：图片不再写进小布剪贴板库（该版本小布面板不渲染图片缩略图、
 * 点击图片行也无任何动作），改为把 MediaStore 的 content:// URI 直接放进系统剪贴板，
 * 由微信/QQ/浏览器长按「粘贴」取出。
 *
 * 所以这里不再提供写库能力，只保留清理：擦掉早期版本写进库的坏记录。
 *
 * 表结构（反编译所得）：
 *   tab_clipboard_data(clipboard_id PK AUTOINCREMENT, clipboard_type, clipboard_text,
 *                      clipboard_preview_text, clipboard_uri, clipboard_time,
 *                      clipboard_mime_types)
 *   tab_clipboard_file(clipboard_file_id, clipboard_file_name, clipboard_file_size,
 *                      clipboard_file_types)
 */
final class XiaobuClipDb {

    private XiaobuClipDb() {}

    /** 面板里单条文本的最大长度：超长的只截断入库，系统剪贴板里仍是完整内容 */
    private static final int MAX_TEXT = 64 * 1024;
    /** 面板上限（宿主配置是 500，但这一版它自己不裁） */
    private static final int MAX_ROWS = 500;

    private static File dbFile(Context ctx) {
        return new File(ctx.getDataDir(), "databases/KeyBoardDatabase");
    }

    /**
     * 把一条「从对端同步进来的文本」补写进小布剪贴板历史。
     *
     * 为什么必须补：宿主是靠自己注册的 OnPrimaryClipChangedListener 读剪贴板来记历史的，
     * 而键盘收起时它自己也读不到（同一个 READ_CLIPBOARD 门禁）—— 于是系统剪贴板里
     * 明明有内容、能粘贴，但面板列表里没有这一条，用户看到的就是「没同步过来」。
     * 模块手里就有文本，直接按宿主的表结构插一行即可，不需要读剪贴板。
     *
     * 去重：同文本 10 分钟内已存在就跳过（宿主已记过 / 同一条内容重复同步）。
     * 顺带把总行数裁到 {@link #MAX_ROWS}，避免主人再看到 1029/500。
     */
    static void recordText(Context ctx, String text) {
        if (ctx == null || text == null || text.isEmpty()) return;
        SQLiteDatabase db = null;
        try {
            File f = dbFile(ctx);
            if (!f.exists()) return;
            // 先按入库长度截断，再去重：去重必须拿「真正要写进去的那串」比，
            // 否则超长文本永远匹配不上已存的行，每调一次就插一行（v3.18.6 的实测事故：
            // 一份 UserScript 被反复插成 64 行，面板里全是同一张卡）。
            String stored = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
            db = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
            long nowMs = System.currentTimeMillis();
            // 去重按「文本」而不是时间窗：同一条内容反复同步/反复补录时，
            // 面板里只应有一行（宿主自己也是按文本去重的，1024 个不同文本对应 1029 行）。
            android.database.Cursor c = db.rawQuery(
                    "SELECT clipboard_id FROM tab_clipboard_data"
                            + " WHERE clipboard_text=? LIMIT 1",
                    new String[]{stored});
            boolean exists = c != null && c.moveToFirst();
            if (c != null) c.close();
            if (exists) {
                MainHookLogger.log("event=clipdb_dup len=" + stored.length());
                return;
            }
            // 宿主自己也在写这个库（WAL 允许多连接，但写锁是串行的），撞锁就退一点再试
            for (int i = 0; ; i++) {
                try {
                    db.execSQL("INSERT INTO tab_clipboard_data"
                                    + " (clipboard_type, clipboard_text, clipboard_preview_text,"
                                    + "  clipboard_uri, clipboard_time, clipboard_mime_types)"
                                    + " VALUES (1, ?, ?, NULL, ?, 'text/plain')",
                            new Object[]{stored, stored, nowMs});
                    break;
                } catch (Throwable t) {
                    if (i >= 2) throw t;
                    try { Thread.sleep(150); } catch (InterruptedException ignore) {}
                }
            }
            // 顺手裁剪（宿主这一版不会自己裁）
            db.execSQL("DELETE FROM tab_clipboard_data WHERE clipboard_id NOT IN"
                    + " (SELECT clipboard_id FROM tab_clipboard_data"
                    + " ORDER BY clipboard_time DESC LIMIT " + MAX_ROWS + ")");
            MainHookLogger.log("event=clipdb_insert len=" + stored.length());
        } catch (Throwable t) {
            MainHookLogger.log("event=clipdb_insert_fail " + t);
        } finally {
            if (db != null) try { db.close(); } catch (Throwable ignore) {}
        }
    }

    /**
     * 早期版本遗留的坏 URI 前缀。
     * 这些行是小布 a$a.a() 摄取外来 URI 的产物：authority 用的是小布 provider 的命名空间，
     * 但路径是单数 clip/images/<id>，与小布 UriMatcher 的 clips/images/# 对不上，且无字节文件，
     * 永远解不出内容（content read 抛 FileNotFoundException）。
     */
    private static final String[] LEGACY_BROKEN_PREFIX = {
            "content://com.oplus.keyboard.provider.clipboard/clip/images/",
            "content://com.oplus.keyboard.provider.clipboard/clip/videos/",
    };

    /** 清理历史遗留的坏记录（含 file 表伴生行），返回删除条数 */
    static int purgeLegacy(Context ctx) {
        int n = 0;
        for (String p : LEGACY_BROKEN_PREFIX) n += deleteByUriPrefix(ctx, p);
        MainHookLogger.log("event=clipdb_purge_legacy n=" + n);
        return n;
    }

    /** 清掉指定 URI 前缀的图片记录（含 file 表伴生行），返回删除条数 */
    private static int deleteByUriPrefix(Context ctx, String prefix) {
        SQLiteDatabase db = null;
        try {
            File f = dbFile(ctx);
            if (!f.exists()) return 0;
            db = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
            android.database.Cursor c = db.rawQuery(
                    "SELECT clipboard_id FROM tab_clipboard_data WHERE clipboard_uri LIKE ?",
                    new String[]{prefix + "%"});
            java.util.List<Long> ids = new java.util.ArrayList<>();
            if (c != null) {
                while (c.moveToNext()) ids.add(c.getLong(0));
                c.close();
            }
            for (Long id : ids) {
                db.execSQL("DELETE FROM tab_clipboard_data WHERE clipboard_id=?", new Object[]{id});
                db.execSQL("DELETE FROM tab_clipboard_file WHERE clipboard_file_id=?", new Object[]{id});
            }
            return ids.size();
        } catch (Throwable t) {
            MainHookLogger.log("event=clipdb_del_fail " + t);
            return 0;
        } finally {
            if (db != null) try { db.close(); } catch (Throwable ignore) {}
        }
    }
}
