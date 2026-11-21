package com.cxhuyo.wetsync;

/**
 * 微信输入法「个人词库」的词条解析。
 *
 * 模块已完全独立：词条只来自服务端增量（{@link WxClient#fetchDictDelta}），
 * 不再读取微信客户端的任何文件，也不再注入 com.tencent.wetype。
 *
 * value 布局（用「傻福 / 傻福吧 / 沙盒 / 沙盒展」四个样本反推并互相验证）：
 *   [0:2]  词条 UTF-8 字节长度 N（大端）
 *   [2]    类型
 *   [3:5]  commits（小端 u16）—— 「阿」这类高频字达 8298，单字节装不下
 *   [5:7]  填充
 *   [7:11] 时间戳（小端 u32，秒）
 *   [11:15] 另一组计数（与 commits 常同值）
 *   末尾 N 字节 = 词条正文
 * 实测：服务端增量报文里的 value 与微信本地 LevelDB 的 value 完全同构，可共用这段解析。
 */
final class WxDictReader {

    static final class Entry {
        String pinyin = "";   // "sha fu"（空格分隔）
        String word = "";     // "傻福"
        int commits = 1;
        long ts = 0;
        byte[] raw;           // 原始 value（构造上传报文时当模板用）
    }

    private WxDictReader() {
    }

    /** 由「拼音 + value 字节」还原一条词条；格式不合法返回 null */
    static Entry parseValue(String pinyin, byte[] val) {
        if (pinyin == null || pinyin.isEmpty() || val == null || val.length < 16) return null;
        int wlen = ((val[0] & 0xff) << 8) | (val[1] & 0xff);
        if (wlen <= 0 || wlen > val.length - 14) return null;
        String word;
        try {
            word = new String(val, val.length - wlen, wlen, "UTF-8");
        } catch (Exception e) {
            return null;
        }
        // 含控制字符的丢弃（说明长度字段对不上，不是正常词条）
        for (int i = 0; i < word.length(); i++) {
            if (word.charAt(i) < 0x20) return null;
        }
        int commits = (val[3] & 0xff) | ((val[4] & 0xff) << 8);
        long ts = (val[7] & 0xffL) | ((val[8] & 0xffL) << 8)
                | ((val[9] & 0xffL) << 16) | ((val[10] & 0xffL) << 24);
        Entry e = new Entry();
        e.word = word;
        e.pinyin = pinyin.replace(',', ' ').trim();
        e.commits = commits <= 0 ? 1 : commits;
        e.ts = ts;
        e.raw = val;
        return e;
    }

    /**
     * 以已有 value 为模板构造新词条的 value：只改「词长」和末尾正文，
     * 中间的权重/计数原样保留（那些字段语义没完全逆向清楚，照抄最安全）。
     */
    static byte[] rebuildValue(Entry tpl, String newWord) {
        if (tpl == null || tpl.raw == null) return null;
        int oldLen = ((tpl.raw[0] & 0xff) << 8) | (tpl.raw[1] & 0xff);
        int head = tpl.raw.length - oldLen;
        if (head <= 2) return null;
        byte[] w;
        try {
            w = newWord.getBytes("UTF-8");
        } catch (Exception e) {
            return null;
        }
        byte[] out = new byte[head + w.length];
        System.arraycopy(tpl.raw, 0, out, 0, head);
        out[0] = (byte) ((w.length >> 8) & 0xff);
        out[1] = (byte) (w.length & 0xff);
        System.arraycopy(w, 0, out, head, w.length);
        return out;
    }
}
