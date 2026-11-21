package com.cxhuyo.wetsync;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/** 极简 HTTPS POST 通道（IP 直连 + SNI，绕开微信私有 HTTPDNS） */
public final class HttpChannel {
    /** 最近一次响应的 Kb-CompressionType（"1"=无压缩 "2"=snappy），供业务层按需解压 */
    public static volatile String lastCompression = "1";
    private static final String[] FALLBACK_IPS = {"101.226.95.60", "101.91.29.17", "101.226.95.61", "101.226.95.62"};

    public static byte[] post(String host, String path, byte[] body) throws Exception {
        return post(host, path, body, null);
    }

    public static byte[] get(String host, String path) throws Exception {
        return request("GET", host, path, new byte[0], null);
    }

    public static byte[] post(String host, String path, byte[] body, java.util.Map<String,String> extra) throws Exception {
        return request("POST", host, path, body, extra);
    }

    private static byte[] request(String method, String host, String path, byte[] body,
                                  java.util.Map<String, String> extra) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, null, null);
        // 候选 IP：先系统 DNS，再静态池（微信 HTTPDNS 会漂移，单个 IP 可能被限流/不可达）
        java.util.List<String> cands = new java.util.ArrayList<>();
        try {
            java.net.InetAddress[] all = java.net.InetAddress.getAllByName(host);
            if (all != null) for (java.net.InetAddress a : all) {
                if (a != null && !cands.contains(a.getHostAddress())) cands.add(a.getHostAddress());
            }
        } catch (Throwable ignore) {}
        for (String ip : FALLBACK_IPS) if (!cands.contains(ip)) cands.add(ip);

        Throwable last = null;
        for (String ip : cands) {
            Socket raw = null;
            SSLSocket s = null;
            try {
                MainHookLogger.log("event=http_connect ip=" + ip);
                raw = new Socket();
                raw.connect(new InetSocketAddress(ip, 443), 12000);
                s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, 443, true);
                // 坑：setSoTimeout 必须在 startHandshake 之前设。否则握手阶段（服务器不回包、
                // 运营商中间盒丢 SYN-ACK 后的 TLS 数据）没有读超时，startHandshake() 会永久阻塞，
                // 整个 engine 线程就此卡死——日志停在 http_connect 之后就再无任何输出。
                s.setSoTimeout(20000);
                s.startHandshake();
                return exchange(s, host, method, path, body, extra);
            } catch (Throwable t) {
                last = t;
                MainHookLogger.log("event=http_ip_fail " + ip + " " + t);
                try { if (s != null) s.close(); } catch (Throwable ignore) {}
                try { if (raw != null) raw.close(); } catch (Throwable ignore) {}
            }
        }
        throw (last instanceof Exception) ? (Exception) last : new java.io.IOException(String.valueOf(last));
    }

    /**
     * 直接把 GET 发到指定 IP，但 TLS SNI 与 Host 头仍然用真实域名。
     * 用于「进程内 DNS 被系统/输入法旁路、解析不了域名」的场景（COS 下载）。
     */
    public static byte[] getViaIp(String host, String path, String ip) throws Exception {
        return getViaIp(host, path, ip, 12000, 20000);
    }

    public static byte[] getViaIp(String host, String path, String ip, int connMs, int soMs)
            throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, null, null);
        Socket raw = null;
        SSLSocket s = null;
        try {
            raw = new Socket();
            raw.connect(new InetSocketAddress(ip, 443), connMs);
            s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, 443, true);
            s.setSoTimeout(soMs);
            s.startHandshake();
            return exchange(s, host, "GET", path, new byte[0], null);
        } finally {
            try { if (s != null) s.close(); } catch (Throwable ignore) {}
            try { if (raw != null) raw.close(); } catch (Throwable ignore) {}
        }
    }

    private static byte[] exchange(SSLSocket s, String host, String method, String path, byte[] body,
                                   java.util.Map<String, String> extra) throws Exception {
        BufferedOutputStream out = new BufferedOutputStream(s.getOutputStream());
        StringBuilder hb = new StringBuilder();
        hb.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");
        hb.append("Host: ").append(host).append("\r\n");
        if (extra != null) for (java.util.Map.Entry<String,String> e : extra.entrySet()) {
            String k = e.getKey();
            if (k.equalsIgnoreCase("Host") || k.equalsIgnoreCase("Content-Length")
                    || k.equalsIgnoreCase("Content-Type") || k.equalsIgnoreCase("Connection")) continue;
            String v = e.getValue();
            if (v == null || v.isEmpty()) continue;
            hb.append(e.getKey()).append(": ").append(v).append("\r\n");
        }
        hb.append("Content-Type: application/octet-stream\r\n");
        hb.append("Content-Length: ").append(body.length).append("\r\n");
        hb.append("Connection: close\r\n\r\n");
        String head = hb.toString();
        MainHookLogger.log("event=http_req " + head.replace("\r\n", " | "));
        out.write(head.getBytes("UTF-8"));
        out.write(body);
        out.flush();

        BufferedInputStream in = new BufferedInputStream(s.getInputStream());
        String status = readLine(in);
        int cl = -1;
        boolean chunked = false;
        String comp = "1";
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            String l = line.toLowerCase();
            if (l.startsWith("content-length:")) cl = Integer.parseInt(line.substring(15).trim());
            if (l.startsWith("kb-compressiontype:")) comp = line.substring(20).trim();
            if (l.startsWith("transfer-encoding:") && l.contains("chunked")) chunked = true;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (chunked) {
            while (true) {
                String sz = readLine(in);
                int n = Integer.parseInt(sz.trim(), 16);
                if (n == 0) break;
                byte[] buf = new byte[n];
                int off = 0;
                while (off < n) { int r = in.read(buf, off, n - off); if (r < 0) break; off += r; }
                bos.write(buf, 0, off);
                readLine(in);
            }
        } else if (cl >= 0) {
            byte[] buf = new byte[cl];
            int off = 0;
            while (off < cl) { int r = in.read(buf, off, cl - off); if (r < 0) break; off += r; }
            bos.write(buf, 0, off);
        } else {
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        }
        try { s.close(); } catch (Throwable ignore) {}
        lastCompression = comp;
        MainHookLogger.log("event=http_rsp status=" + status + " len=" + bos.size()
                + " comp=" + comp + (chunked ? " chunked" : ""));
        return bos.toByteArray();
    }

    private static String readLine(BufferedInputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') if (c != '\r') sb.append((char) c);
        return sb.toString();
    }

    private HttpChannel() {}
}
