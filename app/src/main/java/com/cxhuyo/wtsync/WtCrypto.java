package com.cxhuyo.wtsync;

import java.math.BigInteger;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 零依赖密码学：secp128r1 ECDH（纯 Java 手写曲线）+ AES-256-ECB + SHA256/MD5。
 * 不引用 BouncyCastle —— 避免在宿主进程里触发 5MB 曲线表的类加载（实测会长时间卡住）。
 */
public final class WtCrypto {

    public static final String CURVE = "secp128r1";

    static final BigInteger P = new BigInteger("FFFFFFFDFFFFFFFFFFFFFFFFFFFFFFFF", 16);
    static final BigInteger A = new BigInteger("FFFFFFFDFFFFFFFFFFFFFFFFFFFFFFFC", 16);
    static final BigInteger B = new BigInteger("E87579C11079F43DD824993C2CEE5ED3", 16);
    static final BigInteger GX = new BigInteger("161FF7528B899B2D0C28607CA52C5B86", 16);
    static final BigInteger GY = new BigInteger("CF5AC8395BAFEB13C02DA292DDED7A83", 16);
    static final BigInteger N = new BigInteger("FFFFFFFE0000000075A30D1B9038A115", 16);

    public static final class EcKeys {
        public BigInteger d;
        public byte[] pubPoint;
    }

    private static final BigInteger[] G = {GX, GY};

    /** 仿射点加（含倍点），返回 null 表示无穷远点。
     *  关键：modInverse 的入参必须先 mod(P) 归一到 [0,P)，否则 Android libcore 的
     *  BigInteger.modInverse 对负数 / 大于模数的入参行为与桌面 JDK 不同（实测卡死）。 */
    private static BigInteger[] add(BigInteger[] p, BigInteger[] q) {
        if (p == null) return q;
        if (q == null) return p;
        BigInteger lam;
        if (p[0].equals(q[0])) {
            BigInteger num = p[0].multiply(p[0]).multiply(BigInteger.valueOf(3)).add(A).mod(P);
            BigInteger den = p[1].shiftLeft(1).mod(P);
            if (den.signum() == 0) return null;
            lam = num.multiply(den.modInverse(P)).mod(P);
        } else {
            BigInteger num = q[1].subtract(p[1]).mod(P);
            BigInteger den = q[0].subtract(p[0]).mod(P);
            if (den.signum() == 0) return null;
            lam = num.multiply(den.modInverse(P)).mod(P);
        }
        BigInteger x = lam.multiply(lam).subtract(p[0]).subtract(q[0]).mod(P);
        BigInteger y = lam.multiply(p[0].subtract(x)).subtract(p[1]).mod(P);
        return new BigInteger[]{x, y};
    }

    /** k * P （double-and-add，128 位，毫秒级） */
    public static BigInteger[] mul(BigInteger k, BigInteger[] p) {
        BigInteger kk = k.mod(N);
        BigInteger[] r = null;
        BigInteger[] base = p;
        int bits = kk.bitLength();
        for (int i = 0; i < bits; i++) {
            if (kk.testBit(i)) r = add(r, base);
            base = add(base, base);
        }
        MainHookLogger.log("event=mul_done bits=" + bits);
        return r;
    }

    /** 点 → 33 字节未压缩（0x04||X||Y） */
    public static byte[] encodePoint(BigInteger[] pt) {
        if (pt == null) throw new IllegalArgumentException("infinity");
        byte[] out = new byte[33];
        out[0] = 0x04;
        System.arraycopy(toFixed(pt[0], 16), 0, out, 1, 16);
        System.arraycopy(toFixed(pt[1], 16), 0, out, 17, 16);
        return out;
    }

    /** 由固定私钥 d 求公钥点（33 字节） */
    public static byte[] pubFromD(BigInteger d) {
        return encodePoint(mul(d, G));
    }

    /** ECDH：私钥 d × 对端点 → 共享点 X 坐标的 32 位大写 HEX（直接作 AES-256 的 ASCII 密钥） */
    public static String sharedKeyHex(BigInteger privD, byte[] peerPoint) {
        if (peerPoint == null || peerPoint.length != 33 || peerPoint[0] != 4)
            throw new IllegalArgumentException("bad peer point len="
                    + (peerPoint == null ? -1 : peerPoint.length));
        BigInteger px = new BigInteger(1, java.util.Arrays.copyOfRange(peerPoint, 1, 17));
        BigInteger py = new BigInteger(1, java.util.Arrays.copyOfRange(peerPoint, 17, 33));
        BigInteger[] r = mul(privD, new BigInteger[]{px, py});
        if (r == null) throw new IllegalStateException("bad shared point");
        return toHex(toFixed(r[0], 16));
    }

    // ---------- 对称 / 摘要 ----------
    public static byte[] aes(byte[] data, byte[] key) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return c.doFinal(data);
    }

    public static byte[] aesDec(byte[] data, byte[] key) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        return c.doFinal(data);
    }

    public static String sign(String... parts) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(p);
        return toHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes("UTF-8")));
    }

    public static byte[] toFixed(BigInteger v, int len) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[len];
        int srcOff = raw.length > len ? raw.length - len : 0;
        int copy = Math.min(raw.length, len);
        System.arraycopy(raw, srcOff, out, len - copy, copy);
        return out;
    }

    public static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    public static byte[] fromHex(String s) {
        int n = s.length() / 2;
        byte[] o = new byte[n];
        for (int i = 0; i < n; i++) o[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return o;
    }

    private WtCrypto() {}
}
