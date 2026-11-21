package com.cxhuyo.wetsync;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 剪贴板图片的托管：把 JPEG 变成「别的进程能读的 content:// URI」。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 为什么剪贴板里不能只放内存字节？
 *
 *   Android 剪贴板（system_server 的 ClipboardService）只认三种 Item：
 *   文本 / URI / Intent。它本身不托管二进制，也没有「内存图片」这一形态。
 *   图片放进剪贴板 = 放一个 content:// 引用，真正的字节必须由某个
 *   进程的 ContentProvider 在别人 openInputStream 时吐出来。
 *   所以「只存在剪贴板内存里」在系统层面不存在，最小代价是
 *   「存在本进程的私有缓存目录 + 用本进程已有的 provider 暴露出去」。
 * ═══════════════════════════════════════════════════════════════════════
 *
 * v3.11.0 起的主路径：**小布自己的 FileProvider + 私有缓存**
 *
 *   小布 APK 声明了（已从已安装 APK 的清单里核实）：
 *     provider  androidx.core.content.FileProvider
 *     authority com.oplus.keyboard.provider.file
 *     exported  false, grantUriPermissions true
 *     paths     cache-path name="cache" path="."   ← 整个 cache 目录可暴露
 *
 *   于是：把 JPEG 写进 /data/data/com.oplus.keyboard/cache/wtsync/，
 *   URI 形如 content://com.oplus.keyboard.provider.file/cache/wtsync/clip_xxx.jpg。
 *   · 不进 MediaStore → 相册、文件管理器、媒体库全都看不到；
 *   · 我们是同一个 UID，写文件、造 URI、grantUriPermission 都不需要额外权限；
 *   · 只保留最新一张，收到新图就把旧文件删掉（新的替换旧的）。
 *
 *   兜底路径：万一 provider 被小布改掉/找不到，退回 MediaStore 的
 *   Download/WtSyncClip（不进相册，但会在「下载」里留条目）。
 * ═══════════════════════════════════════════════════════════════════════
 */
final class ClipImageStore {

    private ClipImageStore() {}

    /** 小布 FileProvider 的 authority 与 cache 子目录名（与 file_paths.xml 中的 name 对应） */
    private static final String FP_AUTHORITY = "com.oplus.keyboard.provider.file";
    private static final String FP_CACHE_SEG = "cache";
    private static final String PRIVATE_DIR = "wtsync";

    /** 兜底：MediaStore 下载目录（不进相册） */
    private static final String REL_DIR = "Download/WtSyncClip";
    /** 历史版本写进相册的位置，需要一次性清掉 */
    private static final String REL_DIR_LEGACY = "Pictures/WtSyncClip";

    /** 私有缓存只保留最新 1 张 */
    private static final int KEEP_PRIVATE = 1;
    /** 兜底路径保留张数 */
    private static final int KEEP_MEDIA = 2;

    private static final Set<String> SEEN = new LinkedHashSet<>();
    private static final int SEEN_MAX = 64;

    static String hashOf(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format(Locale.US, "%02x", d[i]));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(data.length) + Integer.toHexString(data.hashCode());
        }
    }

    static boolean isDuplicate(String hash) {
        synchronized (SEEN) {
            return SEEN.contains(hash);
        }
    }

    static void markSeen(String hash) {
        synchronized (SEEN) {
            SEEN.add(hash);
            java.util.Iterator<String> it = SEEN.iterator();
            while (SEEN.size() > SEEN_MAX && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    // ───────────────────────── 对外入口 ─────────────────────────

    /** 主路径优先（私有缓存），失败退回 MediaStore。 */
    static String publish(Context ctx, byte[] jpeg) {
        if (ctx == null || jpeg == null || jpeg.length == 0) return null;
        String uri = publishPrivate(ctx, jpeg);
        if (uri != null) return uri;
        MainHookLogger.log("event=clipimg_private_fail fallback=media");
        return publishMedia(ctx, jpeg);
    }

    // ───────────────────────── 主路径：私有缓存 + FileProvider ─────────────────────────

    private static String publishPrivate(Context ctx, byte[] jpeg) {
        try {
            File dir = new File(ctx.getCacheDir(), PRIVATE_DIR);
            if (!dir.exists() && !dir.mkdirs()) return null;
            String name = "clip_" + hashOf(jpeg) + ".jpg";
            File f = new File(dir, name);

            FileOutputStream fo = null;
            try {
                fo = new FileOutputStream(f);
                fo.write(jpeg);
                fo.flush();
                try { fo.getFD().sync(); } catch (Throwable ignore) {}
            } finally {
                if (fo != null) try { fo.close(); } catch (Throwable ignore) {}
            }

            Uri uri = Uri.parse("content://" + FP_AUTHORITY + "/" + FP_CACHE_SEG + "/"
                    + PRIVATE_DIR + "/" + name);

            // 自检：能取到 image/* 且真的能读出字节，才敢放进剪贴板
            String type = ctx.getContentResolver().getType(uri);
            if (type == null || !type.startsWith("image/")) {
                MainHookLogger.log("event=clipimg_private_badtype type=" + type + " uri=" + uri);
                try { f.delete(); } catch (Throwable ignore) {}
                return null;
            }
            InputStream in = null;
            try {
                in = ctx.getContentResolver().openInputStream(uri);
                if (in == null || in.read() < 0) {
                    MainHookLogger.log("event=clipimg_private_unreadable uri=" + uri);
                    try { f.delete(); } catch (Throwable ignore) {}
                    return null;
                }
            } finally {
                if (in != null) try { in.close(); } catch (Throwable ignore) {}
            }

            MainHookLogger.log("event=clipimg_private_ok uri=" + uri + " bytes=" + jpeg.length);
            trimPrivate(dir, name);
            return uri.toString();
        } catch (Throwable t) {
            MainHookLogger.log("event=clipimg_private_err " + t);
            return null;
        }
    }

    /** 私有一张流：删掉除最新 KEEP_PRIVATE 张之外的文件（新的替换旧的）。 */
    private static void trimPrivate(File dir, String keepName) {
        try {
            File[] fs = dir.listFiles();
            if (fs == null) return;
            List<File> others = new ArrayList<>();
            for (File f : fs) {
                if (f.getName().equals(keepName)) continue;
                others.add(f);
            }
            others.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            int allow = Math.max(0, KEEP_PRIVATE - 1);
            int removed = 0;
            for (int i = 0; i < others.size(); i++) {
                if (i < allow) continue;
                if (others.get(i).delete()) removed++;
            }
            if (removed > 0) {
                MainHookLogger.log("event=clipimg_private_trim removed=" + removed);
            }
        } catch (Throwable ignore) {}
    }

    /** 进程启动时清空私有一张（上次进程的剪贴板内容已失效）。 */
    static void purgePrivate(Context ctx) {
        if (ctx == null) return;
        try {
            File dir = new File(ctx.getCacheDir(), PRIVATE_DIR);
            File[] fs = dir.listFiles();
            if (fs == null) return;
            int removed = 0;
            for (File f : fs) if (f.delete()) removed++;
            if (removed > 0) MainHookLogger.log("event=clipimg_private_purge removed=" + removed);
        } catch (Throwable ignore) {}
    }

    // ───────────────────────── 兜底：MediaStore（不进相册） ─────────────────────────

    private static boolean useDownloads() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    private static Uri collection() {
        return useDownloads()
                ? MediaStore.Downloads.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }

    private static String publishMedia(Context ctx, byte[] jpeg) {
        String name = "clip_" + hashOf(jpeg) + ".jpg";
        Uri coll = collection();
        try {
            Uri existing = findExisting(ctx, coll, name);
            if (existing != null) {
                MainHookLogger.log("event=clipimg_reuse uri=" + existing);
                return existing.toString();
            }

            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, REL_DIR);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri uri = ctx.getContentResolver().insert(coll, v);
            if (uri == null) {
                MainHookLogger.log("event=clipimg_media_null");
                return null;
            }
            OutputStream os = null;
            try {
                os = ctx.getContentResolver().openOutputStream(uri);
                if (os == null) {
                    MainHookLogger.log("event=clipimg_media_osnull");
                    ctx.getContentResolver().delete(uri, null, null);
                    return null;
                }
                os.write(jpeg);
                os.flush();
            } finally {
                if (os != null) try { os.close(); } catch (Throwable ignore) {}
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, done, null, null);

            MainHookLogger.log("event=clipimg_publish_ok uri=" + uri + " dir=" + REL_DIR);
            cleanupOld(ctx, KEEP_MEDIA);
            return uri.toString();
        } catch (Throwable t) {
            MainHookLogger.log("event=clipimg_publish_fail " + t);
            return null;
        }
    }

    static void cleanupOld(Context ctx, int keep) {
        if (ctx == null) return;
        Cursor c = null;
        try {
            ContentResolver r = ctx.getContentResolver();
            c = r.query(collection(),
                    new String[]{MediaStore.MediaColumns._ID},
                    // MediaStore 的 relative_path 带结尾斜杠（Download/WtSyncClip/），必须用 LIKE
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                    new String[]{REL_DIR + "/%"},
                    MediaStore.MediaColumns.DATE_ADDED + " DESC");
            if (c == null) return;
            List<Long> ids = new ArrayList<>();
            while (c.moveToNext()) ids.add(c.getLong(0));
            if (ids.size() <= keep) return;
            int removed = 0;
            for (int i = keep; i < ids.size(); i++) {
                Uri u = ContentUris.withAppendedId(collection(), ids.get(i));
                try {
                    if (r.delete(u, null, null) > 0) removed++;
                } catch (Throwable ignore) {}
            }
            if (removed > 0) {
                MainHookLogger.log("event=clipimg_cleanup removed=" + removed
                        + " kept=" + Math.min(keep, ids.size()));
            }
        } catch (Throwable t) {
            MainHookLogger.log("event=clipimg_cleanup_fail " + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignore) {}
        }
    }

    /**
     * 一次性清理历史版本留在媒体库里的图（Pictures/WtSyncClip，会进相册）。
     * 只在进程启动时调用一次。
     */
    static void purgeLegacyGallery(Context ctx) {
        if (ctx == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        purgeImagesDir(ctx, REL_DIR_LEGACY);
    }

    private static void purgeImagesDir(Context ctx, String relDir) {
        Cursor c = null;
        try {
            ContentResolver r = ctx.getContentResolver();
            Uri images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            c = r.query(images, new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                    new String[]{relDir + "/%"}, null);
            if (c == null) return;
            List<Long> ids = new ArrayList<>();
            while (c.moveToNext()) ids.add(c.getLong(0));
            c.close();
            c = null;
            int removed = 0;
            for (long id : ids) {
                try {
                    if (r.delete(ContentUris.withAppendedId(images, id), null, null) > 0) removed++;
                } catch (Throwable ignore) {}
            }
            if (removed > 0) {
                MainHookLogger.log("event=clipimg_legacy_purged dir=" + relDir + " removed=" + removed);
            }
        } catch (Throwable t) {
            MainHookLogger.log("event=clipimg_legacy_purge_fail " + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignore) {}
        }
    }

    private static Uri findExisting(Context ctx, Uri coll, String name) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(coll,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{name},
                    null);
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(coll, c.getLong(0));
            }
        } catch (Throwable t) {
            MainHookLogger.log("event=clipimg_find_fail " + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignore) {}
        }
        return null;
    }
}
