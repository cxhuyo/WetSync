package com.cxhuyo.wetsync;

/**
 * Snappy 原始块格式（raw block，非 framed）解码器。
 * 服务端在 Kb-CompressionType=2 时对 /api_v2 响应体做 snappy 压缩。
 *
 * 格式（snappy format.txt）：
 *   前缀 varint = 解压后长度
 *   随后若干 tag：
 *     tag 低 2 位 = 00 字面量：高 6 位为 len-1（<60）；否则后续 1..4 字节存 len-1
 *     tag 低 2 位 = 01 1 字节偏移拷贝：len = 4 + ((tag>>2)&0x7)，offset = ((tag>>5)<<8)|next
 *     tag 低 2 位 = 10 2 字节偏移拷贝：len = (tag>>2)+1，offset = 2 字节小端
 *     tag 低 2 位 = 11 4 字节偏移拷贝：len = (tag>>2)+1，offset = 4 字节小端
 */
public final class Snappy {

    public static byte[] uncompress(byte[] in) {
        int pos = 0;
        long expected = 0;
        int sh = 0;
        while (true) {
            if (pos >= in.length) return in;
            int b = in[pos++] & 0xff;
            expected |= (long) (b & 0x7f) << sh;
            if ((b & 0x80) == 0) break;
            sh += 7;
            if (sh > 35) return in;
        }
        if (expected <= 0 || expected > 32 * 1024 * 1024) return in;

        byte[] out = new byte[(int) expected];
        int o = 0;
        while (pos < in.length) {
            int tag = in[pos++] & 0xff;
            int type = tag & 0x03;
            if (type == 0) {
                int len;
                int hi = tag >>> 2;
                if (hi < 60) {
                    len = hi + 1;
                } else {
                    int nb = hi - 59;
                    len = 0;
                    for (int i = 0; i < nb; i++) len |= (in[pos++] & 0xff) << (8 * i);
                    len += 1;
                }
                if (pos + len > in.length || o + len > out.length) return in;
                System.arraycopy(in, pos, out, o, len);
                pos += len;
                o += len;
            } else {
                int len, offset;
                if (type == 1) {
                    len = 4 + ((tag >>> 2) & 0x7);
                    offset = ((tag >>> 5) << 8) | (in[pos++] & 0xff);
                } else if (type == 2) {
                    len = (tag >>> 2) + 1;
                    offset = (in[pos] & 0xff) | ((in[pos + 1] & 0xff) << 8);
                    pos += 2;
                } else {
                    len = (tag >>> 2) + 1;
                    offset = (in[pos] & 0xff) | ((in[pos + 1] & 0xff) << 8)
                            | ((in[pos + 2] & 0xff) << 16) | ((in[pos + 3] & 0xff) << 24);
                    pos += 4;
                }
                if (offset <= 0 || offset > o || o + len > out.length) return in;
                int start = o - offset;
                for (int i = 0; i < len; i++) out[o++] = out[start + i];
            }
        }
        return o == out.length ? out : in;
    }

    private Snappy() {}
}
