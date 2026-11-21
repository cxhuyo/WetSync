package com.cxhuyo.wetsync;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 剪贴板图片 → 可同步的 JPEG 字节。
 *
 * 三条约束决定了这里的写法：
 *  1. 系统剪贴板里的图可能很大（截图 4K 很常见），直接 decode 会 OOM，
 *     所以先用 inJustDecodeBounds 读尺寸，再按 inSampleSize 降采样。
 *  2. 同步走内联（TypeImageOri），消息体不能太大，因此有 MAX_BYTES 上限。
 *  3. 不能改变长宽比，也不做裁剪——只缩不放。
 */
final class ClipImageCodec {

    private ClipImageCodec() {}

    /** 目标上限：压缩后超过这个字节数就放弃同步（而不是无限降质） */
    static final int MAX_BYTES = 256 * 1024;

    /** 降采样前的最大边长，超过先按 2 的幂次降 */
    private static final int MAX_EDGE = 2048;

    /** 质量梯度：从高到低尝试，命中即止 */
    private static final int[] QUALITY_STEPS = {88, 80, 72, 64, 56};

    static final class Result {
        byte[] jpeg;
        int width;
        int height;
        int srcWidth;
        int srcHeight;
        String error;

        boolean ok() {
            return jpeg != null && jpeg.length > 0;
        }
    }

    /**
     * 从 content:// URI 读出图片并压缩。可在任意线程调用（内部只做 CPU 与 IO）。
     * 失败时返回 result.error，不抛异常，避免把宿主剪贴板链路带崩。
     */
    static Result encode(Context ctx, Uri uri, int maxBytes) {
        Result r = new Result();
        if (uri == null) {
            r.error = "null-uri";
            return r;
        }
        try {
            // ---- 第一步：只读尺寸，不解码像素 ----
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, probe);
            }
            if (probe.outWidth <= 0 || probe.outHeight <= 0) {
                r.error = "decode-bounds-failed";
                return r;
            }
            r.srcWidth = probe.outWidth;
            r.srcHeight = probe.outHeight;

            // ---- 第二步：按 2 的幂次降采样，保证最长边不超过 MAX_EDGE ----
            int sample = 1;
            int longEdge = Math.max(probe.outWidth, probe.outHeight);
            while (longEdge / (sample * 2) >= MAX_EDGE) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp == null) {
                r.error = "decode-failed";
                return r;
            }

            // ---- 第三步：按质量梯度压，命中上限即止 ----
            byte[] out = null;
            for (int q : QUALITY_STEPS) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream(64 * 1024);
                bmp.compress(Bitmap.CompressFormat.JPEG, q, bo);
                byte[] cand = bo.toByteArray();
                if (out == null || cand.length < out.length) out = cand;
                if (cand.length <= maxBytes) {
                    out = cand;
                    break;
                }
            }

            // ---- 第四步：压到最低质量仍超限，就把尺寸减半再试一次 ----
            if (out != null && out.length > maxBytes) {
                int w = Math.max(1, bmp.getWidth() / 2);
                int h = Math.max(1, bmp.getHeight() / 2);
                Bitmap small = Bitmap.createScaledBitmap(bmp, w, h, true);
                ByteArrayOutputStream bo = new ByteArrayOutputStream(64 * 1024);
                small.compress(Bitmap.CompressFormat.JPEG, QUALITY_STEPS[QUALITY_STEPS.length - 1], bo);
                if (bo.size() < out.length) out = bo.toByteArray();
                if (small != bmp) small.recycle();
            }

            r.width = bmp.getWidth();
            r.height = bmp.getHeight();
            bmp.recycle();

            if (out == null || out.length == 0) {
                r.error = "compress-empty";
                return r;
            }
            if (out.length > maxBytes) {
                // 宁可不同步，也不把一张糊图推给对端
                r.error = "too-large-after-compress:" + out.length;
                return r;
            }
            r.jpeg = out;
            return r;

        } catch (OutOfMemoryError oom) {
            r.error = "oom";
            return r;
        } catch (Throwable t) {
            r.error = "err:" + t;
            return r;
        }
    }

    /** 系统剪贴板里是否是图片（只看 MIME，不看 Item 内容） */
    static boolean isImageMime(String[] mimeTypes) {
        if (mimeTypes == null) return false;
        for (String m : mimeTypes) {
            if (m != null && m.startsWith("image/")) return true;
        }
        return false;
    }
}
