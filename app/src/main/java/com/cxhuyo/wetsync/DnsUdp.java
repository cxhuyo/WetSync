package com.cxhuyo.wetsync;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * 极简 DNS 客户端（只查 A 记录，UDP，直连指定服务器）。
 *
 * 为什么需要它：ColorOS 上 `persist.sys.oplus.dns_proxyd=1` + 微信输入法自己的私有 HTTPDNS，
 * 会让**输入法进程内**的 `InetAddress.getAllByName()` 直接抛
 * `UnknownHostException: No address associated with hostname`——
 * 同一个域名在 `adb shell ping` 里却能正常解析（shell 走 netd，进程内被旁路）。
 * 微信本体所有请求都是 IP 直连 + SNI，所以它自己不受影响；我们的 COS 下载是唯一
 * 走系统 DNS 的地方，于是每次都 27ms 内秒失败。
 *
 * 这里绕开系统 resolver：自己构造 DNS 报文，直接发给 223.5.5.5 这类公共 DNS。
 */
public final class DnsUdp {

    /** 查不到返回 null，绝不抛异常。 */
    public static String queryA(String host, String server, int timeoutMs) {
        DatagramSocket sock = null;
        try {
            byte[] query = buildQuery(host);
            if (query == null) return null;
            sock = new DatagramSocket();
            sock.setSoTimeout(timeoutMs);
            // server 是点分十进制字面量，getByName 不会触发 DNS 查询
            InetAddress dst = InetAddress.getByName(server);
            sock.send(new DatagramPacket(query, query.length, dst, 53));
            byte[] buf = new byte[2048];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            sock.receive(resp);
            return parseFirstA(buf, resp.getLength());
        } catch (Throwable t) {
            return null;
        } finally {
            if (sock != null) try { sock.close(); } catch (Throwable ignore) {}
        }
    }

    /** 依次问一圈公共 DNS，谁先给出答案就用谁。 */
    public static String resolveA(String host, String[] servers, int timeoutMs) {
        if (host == null || host.isEmpty()) return null;
        for (String sv : servers) {
            String ip = queryA(host, sv, timeoutMs);
            if (ip != null) return ip;
        }
        return null;
    }

    private static byte[] buildQuery(String host) {
        try {
            String[] labels = host.split("\\.");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            // header: id, flags=0x0100(标准查询,期望递归), qd=1, an=0, ns=0, ar=0
            bo.write(0x12);
            bo.write(0x34);
            bo.write(0x01);
            bo.write(0x00);
            bo.write(0x00);
            bo.write(0x01);
            for (int i = 0; i < 4; i++) bo.write(0x00);
            for (String lb : labels) {
                byte[] b = lb.getBytes("ISO-8859-1");
                if (b.length == 0 || b.length > 63) return null;
                bo.write(b.length);
                bo.write(b, 0, b.length);
            }
            bo.write(0x00);          // QNAME 结束
            bo.write(0x00);          // QTYPE  = A (1)
            bo.write(0x01);
            bo.write(0x00);          // QCLASS = IN (1)
            bo.write(0x01);
            return bo.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String parseFirstA(byte[] b, int len) {
        try {
            if (len < 12) return null;
            int qd = ((b[4] & 0xFF) << 8) | (b[5] & 0xFF);
            int an = ((b[6] & 0xFF) << 8) | (b[7] & 0xFF);
            if (an <= 0) return null;
            int p = 12;
            for (int i = 0; i < qd; i++) {
                p = skipName(b, p, len);
                if (p < 0) return null;
                p += 4;                   // QTYPE + QCLASS
            }
            for (int i = 0; i < an && p >= 0 && p + 10 < len; i++) {
                p = skipName(b, p, len);
                if (p < 0 || p + 10 > len) return null;
                int type = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
                int rdlen = ((b[p + 8] & 0xFF) << 8) | (b[p + 9] & 0xFF);
                int rdata = p + 10;
                if (type == 1 && rdlen == 4 && rdata + 4 <= len) {
                    return (b[rdata] & 0xFF) + "." + (b[rdata + 1] & 0xFF) + "."
                            + (b[rdata + 2] & 0xFF) + "." + (b[rdata + 3] & 0xFF);
                }
                p = rdata + rdlen;
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /** 跳过域名（支持 0xC0 压缩指针）。返回下一个字节位置。 */
    private static int skipName(byte[] b, int p, int len) {
        while (p >= 0 && p < len) {
            int c = b[p] & 0xFF;
            if (c == 0) return p + 1;
            if ((c & 0xC0) == 0xC0) return p + 2;   // 指针，名字就到此为止
            p += c + 1;
        }
        return -1;
    }

    private DnsUdp() {}
}
