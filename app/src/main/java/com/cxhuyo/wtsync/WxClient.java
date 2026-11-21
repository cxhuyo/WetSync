package com.cxhuyo.wtsync;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 微信输入法设备组协议（规格来源：mkdir700/wx-ime-sdk 的 wetype.proto + packet.rs + session.rs，
 * 与 zhl-HFUT/vtype-linux 实测一致）。
 *
 * 端点：/timestamp(task0 不加签) → /oauth_pubkey_v2(task 2147483646) → /api_v2(task 递增)
 * 命令：30 申请关联码 / 31 join / 32 confirm / 33 release / 34 组&剪贴板查询 / 36 发布剪贴板
 *      / 38 fetch bind / 40 poll code
 * 信封：BaseRequest{f2=uin, f3=subcommand, f4=Header, f5=body}；响应业务体在 f6。
 */
public final class WxClient {

    static final String HOST = "wetype.weixin.qq.com";
    static final String VER = "3.5.4.57423";
    static final String SALT = "aI0wC6qT1nB3nN5r";
    static final byte[] BOOT = "G7E1C8B0D7D7E4L3J2U9D6W0B2M8Q6O5".getBytes();

    String uin = "";
    /**
     * 设备名：与微信输入法一致，取系统设置里用户命名的设备名（Settings.Global device_name，
     * 如 "OnePlus 13"），取不到再用 Build.MODEL。该名字会随握手 sf(9) 上报，
     * 服务端以此作为本机在关联设备列表里的显示名。
     */
    String deviceName = computeDeviceName();
    String deviceCode = "";
    BigInteger privD;
    String pubHex = "";
    String serverKey = "";
    byte[] key;          // 32 ASCII bytes（AES-256）
    String suffix;       // key[28..32]
    /** 首次注册时本地生成的 UUIDv4（UinRequest.f4），与 device_code 一起构成设备标识 */
    String uuid = "";
    int task = 1;
    /** 业务通道：微信输入法全部组同步/词库消息都走这个 CmdId */
    static final String CMD_ID = "4814";
    /** 注册阶段临时覆盖 Kb-Uin 写法（"0" / "" / null=用真实 uin），用于兼容不同账号状态 */
    String uinHeaderOverride = null;
    /** 服务端时间 - 本机时间（由 /timestamp 校准，官方实现同样先做这一步） */
    long clockOffset = 0;

    /** 官方实现：所有请求的 Kb-TimeStamp 用服务端校准后的时间 */
    long now() { return System.currentTimeMillis() + clockOffset; }

    /** POST /timestamp（不加密、不加签、task 0），返回服务端毫秒时间戳；失败返回 -1 */
    public long syncClock() {
        try {
            Req r = build("/timestamp", new byte[0], null, 0, null, null);
            byte[] rsp = post("/timestamp", r);
            Pb pb = parse(rsp);
            Long ms = pb.l(1);
            if (ms == null || ms <= 0) {
                MainHookLogger.log("event=ts_bad len=" + rsp.length);
                return -1;
            }
            clockOffset = ms - System.currentTimeMillis();
            MainHookLogger.log("event=ts_ok server=" + ms + " offset=" + clockOffset);
            return ms;
        } catch (Throwable t) {
            MainHookLogger.log("event=ts_err " + t);
            return -1;
        }
    }

    // ---------- protobuf 编解码 ----------
    static byte[] vi(long v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        while (true) {
            int b = (int) (v & 0x7f);
            v >>>= 7;
            if (v != 0) o.write(b | 0x80); else { o.write(b); break; }
        }
        return o.toByteArray();
    }

    static byte[] tag(int field, int wt) { return vi(((long) field << 3) | wt); }

    static byte[] sf(int field, byte[] data) {
        return cat(tag(field, 2), vi(data.length), data);
    }

    static byte[] sf(int field, String s) {
        byte[] b;
        try { b = s.getBytes("UTF-8"); } catch (Exception e) { b = s.getBytes(); }
        return sf(field, b);
    }

    static byte[] vf(int field, long v) {
        return cat(tag(field, 0), vi(v));
    }

    static byte[] cat(byte[]... cs) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] c : cs) { try { o.write(c); } catch (Exception ignore) {} }
        return o.toByteArray();
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        int n = s.length() / 2;
        byte[] o = new byte[n];
        for (int i = 0; i < n; i++) o[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return o;
    }

    static String md5Upper(byte[] b) {
        try { return hex(MessageDigest.getInstance("MD5").digest(b)); } catch (Exception e) { return ""; }
    }

    static String sha256Upper(String s) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"))); }
        catch (Exception e) { return ""; }
    }

    static String randomHex(int n) {
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("0123456789abcdef".charAt(r.nextInt(16)));
        return sb.toString();
    }

    String deviceInfo() {
        return "UniClipboard;research;standalone;" + deviceName + ";sdk 34;PHONE";
    }

    /** 本机设备名：系统设置的设备名优先，回退机型 */
    static String computeDeviceName() {
        try {
            android.content.Context c = XiaobuInject.ctx;
            if (c != null) {
                String n = android.provider.Settings.Global.getString(
                        c.getContentResolver(), "device_name");
                if (n != null && !n.trim().isEmpty()) return n.trim();
            }
        } catch (Throwable ignore) {}
        return android.os.Build.MODEL;
    }

    // ---------- AES ----------
    /**
     * AES-256-ECB + PKCS7。WtCrypto.aes 用 "AES/ECB/PKCS5Padding" 已自动补位，
     * 切勿再手工补一次（否则明文被多 pad 一个整块，服务端 protobuf 解析失败）。
     */
    static byte[] aesEnc(byte[] key, byte[] data) throws Exception {
        return WtCrypto.aes(data, key);
    }

    static byte[] aesDec(byte[] key, byte[] data) throws Exception {
        return WtCrypto.aesDec(data, key);
    }

    // ---------- 请求 ----------
    static final class Req {
        Map<String, String> headers = new LinkedHashMap<>();
        byte[] body;
    }

    Req build(String path, byte[] plain, byte[] aesKey, int taskId, String extraHeader, String extraValue)
            throws Exception {
        Req r = new Req();
        r.body = aesKey == null ? plain : aesEnc(aesKey, plain);
        long ts = now();
        String trace = randomHex(16);
        r.headers.put("Kb-OsType", "1");
        r.headers.put("Kb-Version", VER);
        r.headers.put("Kb-SystemVersion", "34");
        r.headers.put("Kb-PackageType", "2");
        r.headers.put("Kb-TimeStamp", String.valueOf(ts));
        r.headers.put("Kb-BodyMd5", md5Upper(r.body));
        r.headers.put("Kb-TraceId", trace);
        r.headers.put("Kb-TaskId", String.valueOf(taskId));
        r.headers.put("Kb-Scene", "1");
        r.headers.put("Kb-CompressionType", "1");
        r.headers.put("Content-Type", "application/octet-stream");
        r.headers.put("Use_DebugNet", "0");
        StringBuilder order = new StringBuilder("1").append(VER).append("1");
        if (path.contains("oauth_pubkey")) {
            // 全新设备（还没 uin）不能下发空的 Kb-Uin，否则服务端 400 invalid request parameter。
            // uinHeaderOverride 非 null 时用于探测（"0" / "" 等不同写法）。
            // 官方实现：Ecdh 端点始终带 Kb-Uin，首次注册时值为 "0"
            String uh = uinHeaderOverride != null ? uinHeaderOverride : uin;
            if (uh == null) uh = "0";           // 注册探测：null 也按 "0" 发
            if (!uh.isEmpty()) r.headers.put("Kb-Uin", uh);   // "" = 故意省略，用于探测
            r.headers.put("Kb-DeviceCodeRestrictionV2", "1");
            order.append(ts).append(md5Upper(r.body)).append(trace).append(taskId);
        } else if (path.contains("gen_uin")) {
            r.headers.put("Kb-GenUinToken", extraValue == null ? "" : extraValue);
            r.headers.put("Kb-DeviceCodeRestrictionV2", "1");
            order.append(ts).append(md5Upper(r.body)).append(extraValue).append(trace).append(taskId);
        } else {
            r.headers.put("Kb-Uin", uin);
            r.headers.put("Kb-CmdId", CMD_ID);
            r.headers.put("Kb-SubCmdId", extraHeader);
            r.headers.put("Kb-SharedKeySuffix", suffix == null ? "" : suffix);
            order.append(CMD_ID).append(extraHeader).append(ts).append(md5Upper(r.body))
                    .append(uin).append(trace).append(taskId);
        }
        if (!path.contains("timestamp")) r.headers.put("Kb-Sign", sha256Upper(order + SALT));
        return r;
    }

    byte[] post(String path, Req req) throws Exception {
        return HttpChannel.post(HOST, path, req.body, req.headers);
    }

    // ---------- protobuf 解析 ----------
    /** 通用 pb 解析：field -> values（varint 给 Long，length-delimited 给 byte[]） */
    static final class Pb {
        final Map<Integer, List<Object>> m = new LinkedHashMap<>();


        Long l(int f) {
            List<Object> v = m.get(f);
            if (v == null || v.isEmpty()) return null;
            Object o = v.get(0);
            return o instanceof Long ? (Long) o : null;
        }

        byte[] b(int f) {
            List<Object> v = m.get(f);
            if (v == null || v.isEmpty()) return null;
            Object o = v.get(0);
            return o instanceof byte[] ? (byte[]) o : null;
        }

        String s(int f) {
            byte[] x = b(f);
            if (x == null) return null;
            try { return new String(x, "UTF-8"); } catch (Exception e) { return null; }
        }

        Pb sub(int f) {
            byte[] x = b(f);
            if (x == null) return null;
            try { return parse(x); } catch (Exception e) { return null; }
        }
    }

    static Pb parse(byte[] pb) {
        Pb out = new Pb();
        int i = 0;
        while (i < pb.length) {
            long tag = 0; int sh = 0;
            while (true) {
                if (i >= pb.length) return out;
                int x = pb[i++] & 0xff; tag |= (long) (x & 0x7f) << sh; sh += 7;
                if (x < 0x80) break;
            }
            int f = (int) (tag >>> 3), wt = (int) (tag & 7);
            if (wt == 0) {
                long v = 0; sh = 0;
                while (true) {
                    if (i >= pb.length) return out;
                    int x = pb[i++] & 0xff; v |= (long) (x & 0x7f) << sh; sh += 7;
                    if (x < 0x80) break;
                }
                out.m.computeIfAbsent(f, k -> new ArrayList<>()).add(v);
            } else if (wt == 2) {
                long len = 0; sh = 0;
                while (true) {
                    if (i >= pb.length) return out;
                    int x = pb[i++] & 0xff; len |= (long) (x & 0x7f) << sh; sh += 7;
                    if (x < 0x80) break;
                }
                if (len < 0 || i + len > pb.length) return out;
                byte[] d = new byte[(int) len];
                System.arraycopy(pb, i, d, 0, (int) len);
                i += (int) len;
                out.m.computeIfAbsent(f, k -> new ArrayList<>()).add(d);
            } else if (wt == 5) { i += 4; }
            else if (wt == 1) { i += 8; }
            else break;
        }
        return out;
    }

    // ---------- 握手 ----------
    public void initialize() throws Exception {
        MainHookLogger.log("event=init_enter uin=" + uin);
        byte[] pub = WtCrypto.pubFromD(privD);
        pubHex = hex(pub);
        MainHookLogger.log("event=init_pubhex " + pubHex);
        byte[] req = cat(
                sf(1, pubHex),
                sf(2, serverKey == null ? "" : serverKey),
                vf(3, 0),
                sf(4, deviceCode == null ? "" : deviceCode),
                vf(5, Long.parseLong(uin)),
                sf(6, ""), sf(7, ""),
                sf(8, deviceInfo()), sf(9, deviceName));
        Req r = build("/oauth_pubkey_v2", req, BOOT, 2147483646, null, null);
        byte[] rsp = post("/oauth_pubkey_v2", r);
        MainHookLogger.log("event=hs_status len=" + rsp.length);
        if (rsp.length % 16 != 0) {
            MainHookLogger.log("event=hs_raw " + hex(rsp));
            throw new IllegalStateException("handshake not encrypted, len=" + rsp.length);
        }
        byte[] hsPlain = aesDec(BOOT, rsp);
        if ("2".equals(HttpChannel.lastCompression)) hsPlain = Snappy.uncompress(hsPlain);
        Pb resp = parse(hsPlain);
        Long st = resp.l(1);
        if (st == null || st != 0) throw new IllegalStateException("handshake status=" + st);
        String srv = resp.s(2);
        if (srv == null || srv.length() != 66) throw new IllegalStateException("bad server key");
        serverKey = srv;
        String shared = WtCrypto.sharedKeyHex(privD, unhex(srv));
        key = shared.toUpperCase().getBytes("UTF-8");
        suffix = new String(key, 28, 4, "UTF-8");
        MainHookLogger.log("event=hs_key " + shared.substring(0, 8) + " suffix=" + suffix + " len=" + key.length);
    }

    // ---------- 首次启动：匿名注册一个新设备身份（不需要微信账号 / 官方输入法） ----------
    /**
     * 逆向自 github.com/mkdir700/wx-ime-sdk（Apache-2.0）的 session.rs::initialize()：
     *
     *   ① POST /oauth_pubkey_v2 (task 2147483646, AES=BOOT)
     *      EcdhRequest { public_key=1, previous_server_key=2, need_uin=3, device_code=4,
     *                    uin=5, uin_token=6, device_token=7, device_info=8, device_name=9 }
     *      首次：need_uin=true、**uin 字段整个不编码**、uin_token=""、device_token=""，
     *            请求头 Kb-Uin 仍要发 "0"。
     *      EcdhResponse { status=1, public_key=2, key_expiry=3, gen_uin_token=4 }
     *      → gen_uin_token 就在 f4。
     *
     *   ② 用自己私钥 × 服务端 f2 公钥算出会话 key（X 坐标 32 位大写 HEX 当 AES-256 ASCII key）
     *
     *   ③ POST /gen_uin_v2 (task 2147483645, **用会话 key 加密**, 头 Kb-GenUinToken=f4)
     *      UinRequest  { device_code=1, server_key=2, uin=3, uuid=4, device_token=5 }
     *      UinResponse { status=1, uin=2 }
     *
     * device_code / uuid / ECDH 私钥全部本地生成，不依赖任何官方客户端。
     * 成功后本对象直接持有可用会话（uin/key/suffix/serverKey），无需再握手一遍。
     */
    public void registerNewIdentity() throws Exception {
        BigInteger d = new BigInteger(126, new java.security.SecureRandom()).add(BigInteger.ONE);
        String info = deviceInfo();
        // device_code 有严格格式（官方 storage.rs）：
        //   prefix = "AND" + "UniClipboard;research"[..17] + 12 位随机 HEX  （共 32 字符）
        //   code   = prefix + MD5(prefix + SALT) 大写                        （共 64 字符）
        // 长度/校验位不对，网关直接 400 invalid request parameter。
        String prefix = "AND" + "UniClipboard;research".substring(0, 17)
                + randomHex(12).toUpperCase();
        String code;
        try {
            code = prefix + md5Upper((prefix + SALT).getBytes("UTF-8"));
        } catch (Exception e) {
            throw new IllegalStateException("device code md5", e);
        }
        String uid = java.util.UUID.randomUUID().toString();
        String pub = hex(WtCrypto.pubFromD(d));
        MainHookLogger.log("event=reg_start name=" + deviceName + " devcode=" + code + " uuid=" + uid);
        // 官方实现第一步：用 /timestamp 校准时钟（网关可能据此判定请求新鲜度）
        syncClock();

        // ① ECDH（首次：uin 字段不编码，uin_token 编空串）
        // 服务端字段/请求头的接受情况用矩阵探测：{Kb-Uin: "0" | 省略} × {编码 f5 | 省略}
        Pb p1 = null;
        String srv = null, token = null;
        String[] uinHeaders = {"0", ""};
        for (String uh : uinHeaders) {
            for (int withF5 = 0; withF5 <= 1 && p1 == null; withF5++) {
                byte[] ecdh = cat(
                        sf(1, pub),
                        sf(2, ""),          // previous_server_key
                        vf(3, 1),           // need_uin = true
                        sf(4, code));
                if (withF5 == 1) ecdh = cat(ecdh, vf(5, 0));
                ecdh = cat(ecdh,
                        sf(6, ""),          // uin_token（仅首次编码空串）
                        sf(7, ""),          // device_token
                        sf(8, info),
                        sf(9, deviceName));
                String savedUin = uin;
                uin = "";
                uinHeaderOverride = uh;
                Req rr;
                try {
                    rr = build("/oauth_pubkey_v2", ecdh, BOOT, 2147483646, null, null);
                } finally {
                    uin = savedUin;
                    uinHeaderOverride = null;
                }
                byte[] rsp = post("/oauth_pubkey_v2", rr);
                String tag = "uh=[" + uh + "] f5=" + withF5;
                if (rsp.length == 0) {
                    MainHookLogger.log("event=reg_try " + tag + " empty");
                    continue;
                }
                if (rsp.length % 16 != 0) {
                    MainHookLogger.log("event=reg_try " + tag + " rej="
                            + new String(rsp, "UTF-8").trim());
                    continue;
                }
                Pb pb = parse(aesDec(BOOT, rsp));
                Long st = pb.l(1);
                String sk = pb.s(2);
                String tk = pb.s(4);
                MainHookLogger.log("event=reg_try " + tag + " status=" + st
                        + " srvLen=" + (sk == null ? -1 : sk.length())
                        + " tokenLen=" + (tk == null ? -1 : tk.length()));
                if (st != null && st == 0 && sk != null && sk.length() == 66) {
                    p1 = pb; srv = sk; token = tk;
                    MainHookLogger.log("event=reg_ecdh_ok " + tag);
                    break;
                }
            }
            if (p1 != null) break;
        }
        if (p1 == null) throw new IllegalStateException("ecdh rejected in all variants");
        if (token == null || token.isEmpty()) throw new IllegalStateException("no gen_uin_token in response");

        // ② 会话密钥
        String shared = WtCrypto.sharedKeyHex(d, unhex(srv));
        byte[] sessKey = shared.toUpperCase().getBytes("UTF-8");

        // ③ /gen_uin_v2
        byte[] ureq = cat(sf(1, code), sf(2, srv), sf(4, uid), sf(5, ""));
        Req r2 = build("/gen_uin_v2", ureq, sessKey, 2147483645, null, token);
        byte[] rsp2 = post("/gen_uin_v2", r2);
        if (rsp2.length == 0) throw new IllegalStateException("genuin empty response");
        if (rsp2.length % 16 != 0) {
            throw new IllegalStateException("genuin rejected: " + new String(rsp2, "UTF-8").trim());
        }
        Pb p2 = parse(aesDec(sessKey, rsp2));
        Long st2 = p2.l(1);
        Long newUin = p2.l(2);
        MainHookLogger.log("event=reg_genuin status=" + st2 + " uin=" + newUin);
        if (st2 == null || st2 != 0) throw new IllegalStateException("genuin status=" + st2);
        if (newUin == null || newUin <= 0) throw new IllegalStateException("genuin returned no uin");

        // ④ 落定会话与身份
        this.privD = d;
        this.pubHex = pub;
        this.deviceCode = code;
        this.uuid = uid;
        this.serverKey = srv;
        this.key = sessKey;
        this.suffix = new String(sessKey, 28, 4, "UTF-8");
        this.uin = String.valueOf(newUin);
        MainHookLogger.log("event=reg_ok uin=" + this.uin);
    }

    /** 发请求并取出响应里的业务 payload（内层 RspBody.f2） */
    byte[] fetchPayload(int sub, byte[] body) {
        try {
            Pb r = api(sub, body);
            byte[] rb = r.b(6);
            if (rb == null) return null;
            Pb ip = parse(rb);
            Long ec = ip.l(1);
            if (ec != null && ec != 0) {
                MainHookLogger.log("event=fetch_payload err=" + ec);
                return null;
            }
            return ip.b(2);
        } catch (Throwable t) {
            MainHookLogger.log("event=fetch_payload_ex " + t);
            return null;
        }
    }

    /** 一次增量同步的结果 */
    static final class DictDelta {
        long msgId;      // 响应 f1：服务端推进后的游标，下次请求原样带回
        long publisher;  // 响应 f2
        final java.util.List<WxDictReader.Entry> entries = new java.util.ArrayList<>();
    }





    DictDelta fetchDictDelta(long gid, long since) throws Exception {
        byte[] body = since > 0 ? cat(vf(2, since), vf(3, gid)) : vf(3, gid);
        byte[] pl = fetchPayload(42, body);
        if (pl == null) return null;
        Pb top = parse(pl);
        DictDelta d = new DictDelta();
        Long m = top.l(1);
        d.msgId = m == null ? 0 : m;
        Long pub = top.l(2);
        d.publisher = pub == null ? 0 : pub;
        byte[] body3 = top.b(3);
        if (body3 != null) {
            Pb inner = parse(body3);
            java.util.List<Object> list = inner.m.get(1);
            if (list != null) {
                for (Object o : list) {
                    if (!(o instanceof byte[])) continue;
                    try {
                        Pb ep = parse((byte[]) o);
                        String py = ep.s(3);
                        byte[] val = ep.b(4);
                        if (py == null || val == null) continue;
                        WxDictReader.Entry e = WxDictReader.parseValue(py, val);
                        if (e != null) d.entries.add(e);
                    } catch (Throwable ignore) {}
                }
            }
        }
        MainHookLogger.log("event=wxdict_delta since=" + since + " msgId=" + d.msgId
                + " n=" + d.entries.size());
        return d;
    }
    synchronized Pb api(int sub, byte[] body) throws Exception {
        byte[] hdr = cat(
                sf(1, cat(sf(1, "vid"), sf(2, uin))),
                sf(1, cat(sf(1, "app_ver"), sf(2, VER))),
                sf(1, cat(sf(1, "platform"), sf(2, "1"))),
                sf(1, cat(sf(1, "device"), sf(2, deviceInfo()))),
                sf(1, cat(sf(1, "channelid"), sf(2, ""))),
                sf(1, cat(sf(1, "client_timestamp"), sf(2, String.valueOf(System.currentTimeMillis())))));
        byte[] plain = cat(
                vf(2, Long.parseLong(uin)),
                vf(3, sub),
                sf(4, hdr),
                sf(5, body));
        Req r = build("/api_v2", plain, key, task++, String.valueOf(sub), null);
        byte[] rsp = post("/api_v2", r);
        MainHookLogger.log("event=api_status sub=" + sub + " len=" + rsp.length
                + (rsp.length % 16 == 0 ? "" : " raw=" + hex(rsp)));
        if (rsp.length == 0 || rsp.length % 16 != 0) {
            MainHookLogger.log("event=api_raw sub=" + sub + " " + hex(rsp));
            return new Pb();
        }
        byte[] respPlain = aesDec(key, rsp);
        // 服务端在 Kb-CompressionType=2 时对响应体做 snappy 压缩
        if ("2".equals(HttpChannel.lastCompression)) {
            byte[] raw = Snappy.uncompress(respPlain);
            MainHookLogger.log("event=snappy " + respPlain.length + "->" + raw.length);
            respPlain = raw;
        }
        return parse(respPlain);
    }

    /** 业务体（BaseResponse.f6） */
    static Pb biz(Pb base) {
        byte[] x = base.b(6);
        return x == null ? new Pb() : parse(x);
    }

    // ---------- cmd34 组 / 剪贴板查询 ----------
    /**
     * @param filter 位掩码：1=设备元数据，2=剪贴板，3=两者
     * @return GroupResponse；status==10020 表示尚未入组
     */
    public Pb group(int filter) throws Exception {
        byte[] body = cat(vf(1, Long.parseLong(uin)), vf(2, 1), vf(3, filter));
        if ((filter & 2) != 0) body = cat(body, vf(4, 0));   // clipboard_version
        Pb rsp = biz(api(34, body));
        MainHookLogger.log("event=group_rsp status=" + rsp.l(2));
        return rsp;
    }

    // ---------- cmd30 申请关联码（本机出码，对端来加入） ----------
    /** @return {code, expire_at, expiration}，失败返回 null */
    public String[] requestCode() throws Exception {
        byte[] dev = cat(sf(1, deviceName), vf(2, 1));
        byte[] body = cat(vf(1, Long.parseLong(uin)), vf(2, 1), sf(3, dev));
        Pb rsp = biz(api(30, body));
        Long st = rsp.l(1);
        String code = rsp.s(2);
        MainHookLogger.log("event=code_rsp status=" + st + " code=" + code);
        if (st == null || st != 0 || code == null || code.length() != 6) return null;
        return new String[]{code, String.valueOf(rsp.l(3)), String.valueOf(rsp.l(4))};
    }

    // ---------- cmd40 轮询本机发出的码 ----------
    /** @return PollCodeResponse{state=1, bind_id=2, device=4}；state: 0=无人加入 1=待确认 2=失效 */
    public Pb pollCode(String code) throws Exception {
        byte[] body = cat(vf(1, Long.parseLong(uin)), vf(2, 1), sf(3, code));
        Pb rsp = biz(api(40, body));
        MainHookLogger.log("event=poll_rsp state=" + rsp.l(1) + " bind=" + rsp.s(2));
        return rsp;
    }

    // ---------- cmd32 确认关联 ----------r
    /** @return group_id，失败返回 -1 */
    public long confirmBind(String bindId) throws Exception {
        byte[] body = cat(vf(1, Long.parseLong(uin)), vf(2, 1), sf(3, bindId));
        Pb rsp = biz(api(32, body));
        Long st = rsp.l(1);
        Long gid = rsp.l(2);
        MainHookLogger.log("event=confirm_rsp status=" + st + " group=" + gid);
        if (st == null || st != 0) return -1;
        return gid == null ? -1 : gid;
    }

    // ---------- cmd31 用别人的码加入 ----------
    /** @return bind_id，失败返回 null */
    public String join(String code) throws Exception {
        byte[] dev = cat(sf(1, deviceName), vf(2, 1));
        byte[] body = cat(vf(1, Long.parseLong(uin)), vf(2, 1), sf(3, code), sf(5, dev));
        Pb rsp = biz(api(31, body));
        Long st = rsp.l(1);
        String id = rsp.s(2);
        String err = rsp.s(5);
        MainHookLogger.log("event=join_rsp status=" + st + " bind=" + id + " err=" + err);
        if (st == null || st != 0) return null;
        return id;
    }

    // ---------- cmd38 查询加入进度 ----------
    /** @return state：0=等待对端确认 1=已完成 2=已取消；-1=出错 */
    public int fetchBind(String bindId) throws Exception {
        byte[] body = cat(vf(1, Long.parseLong(uin)), vf(2, 1), sf(3, bindId));
        Pb rsp = biz(api(38, body));
        Long st = rsp.l(1);
        Long state = rsp.l(2);
        MainHookLogger.log("event=fetch_rsp status=" + st + " state=" + state);
        if (st == null || st != 0) return -1;
        return state == null ? -1 : state.intValue();
    }

    // ---------- cmd79 修改设备备注名（微信输入法「修改设备名称」） ----------
    /**
     * 协议（自 general.hbc 的 protobuf 编码器逐条还原，非猜测）：
     *   UpdateDeviceInfoReq {
     *     deviceToken     = 1 (string)   // 可空
     *     userDeviceName  = 2 (string)   // 可空
     *     deviceRemarkNames = 3 (repeated) {
     *       uin              = 1 (uint64)
     *       deviceRemarkName = 2 (string)
     *     }
     *   }
     * 注意：f1 **不是** uin（之前按 UnbindReq 的形状照搬导致服务端 10010 参数错误）。
     * 自检日志：rename_rsp status=0 才算成功。
     */
    public boolean updateDeviceRemark(long targetUin, String remark) throws Exception {
        byte[] item = cat(vf(1, targetUin), sf(2, remark));
        byte[] body = sf(3, item);
        Pb rsp = biz(api(79, body));
        // 成功响应的 UpdateDeviceInfoRsp 是空的（protobuf 默认值不编码）；
        // 失败时要么走传输层 f1=错误码，要么走业务层 f2=errCode，两者都要判。
        Long st = rsp.l(1);
        Long err = rsp.l(2);
        String name = rsp.s(3);
        boolean ok = (st == null || st == 0) && (err == null || err == 0);
        MainHookLogger.log("event=rename_rsp status=" + st + " errCode=" + err
                + " name=" + name + " uin=" + targetUin + " ok=" + ok);
        return ok;
    }

    // ---------- cmd35 解除关联（微信输入法「解除设备」） ----------
    /** 协议：UnbindReq { uin=1(本机), platform=2, unbindUin=3(目标设备) } */
    public boolean unbindDevice(long targetUin) throws Exception {
        byte[] body = cat(vf(1, Long.parseLong(uin)), vf(2, 1), vf(3, targetUin));
        Pb rsp = biz(api(35, body));
        Long st = rsp.l(1);
        String err = rsp.s(2);
        MainHookLogger.log("event=unbind_rsp status=" + st + " err=" + err + " target=" + targetUin);
        return st != null && st == 0;
    }

    // ---------- cmd37 发布用户热词（SendUserHotWordsReq） ----------
    /**
     * 字段排布全部自 general.hbc 的 protobuf 编/解码器逐条还原（非猜测）：
     *
     *   SendUserHotWordsReq  { uin=1(uint64), platform=2(uint32),
     *                          userHotWords=3(bytes), groupId=4(uint64) }
     *   SendUserHotWordsRsp  { errCode=1(int32), version=2(uint64) }
     *
     * ⚠ 曾经把 Rsp 的 f1/f2 顺序读反（当成 status=1 / errCode=2），
     *   于是把「version=11 + errCode=0 的成功响应」误判为「errCode=11 失败」，
     *   又把 10012(ConflictVersion) 当成未知错误去乱调版本号。已修正。
     */
    static final class HwRsp {
        long errCode = -1;      // 0 = 成功；10012 = ConflictVersion（基准版本与服务器不一致）
        long version = -1;      // 发布后服务器上的最新版本号
        boolean ok() { return errCode == 0; }
    }

    public HwRsp sendUserHotWords(long groupId, byte[] userHotWords) throws Exception {
        byte[] body = cat(vf(1, Long.parseLong(uin)), vf(2, 1),
                sf(3, userHotWords), vf(4, groupId));
        Pb rsp = biz(api(37, body));
        HwRsp r = new HwRsp();
        Long err = rsp.l(1);
        Long ver = rsp.l(2);
        r.errCode = err == null ? 0 : err;       // 空响应 = 默认值 = errCode 0
        r.version = ver == null ? -1 : ver;
        MainHookLogger.log("event=hotwords_rsp gid=" + groupId + " len="
                + (userHotWords == null ? -1 : userHotWords.length)
                + " errCode=" + r.errCode + (r.errCode == 10012 ? "(ConflictVersion)" : "")
                + " ver=" + r.version + " ok=" + r.ok());
        return r;
    }

    // ---------- 常用语数据通道（cmd37 上传 / cmd34 下行） ----------
    /**
     * 真实结构（自线上报文逐字节解出，2026-10-21 抓包确认）：
     *
     *   UserHotWords { version = 1 (uint64), data = 2 (bytes) }
     *     ↑ 只有两个字段！data 是不透明字节串，内部格式由客户端自己约定：
     *
     *   data { f1 = 1 (uint64, 常量)
     *          f2 = repeated Entry {
     *                 f1 = 条目 id（毫秒时间戳，字符串）
     *                 f2 = 输入码 code
     *                 f3 = 内容 text
     *                 f4 = 条目 id (uint64，与 f1 同值)
     *                 f5 = 0, f9 = 0, f10 = 1 }
     *          f3 = 发布者 uin (uint64)
     *          f4 = 发布时间戳 ms (uint64) }
     *
     * 之前误以为 f2 是嵌套的 Body 消息体，其实它就是 data 的 bytes 内容 —— 结构恰好一致，
     * 所以解析没出错，但「version+1」的写法错了：服务端做的是**乐观并发校验**，
     * 客户端必须回传自己看到的基准版本，服务器才会自增；擅自 +1 一定 ConflictVersion。
     */
    static final class HotWords {
        long version;        // 外层 version（基准版本，原样回传）
        byte[] data;         // 外层 data 原始字节
        byte[] dataF1Raw;    // data.f1 的原始编码字节（原样保留，避免丢字段）
        long publisher;      // data.f3
        long ts;             // data.f4
        final java.util.List<byte[]> entries = new java.util.ArrayList<>();
        /** 解析后的可读条目，便于按内容做增量比对 */
        static final class Entry {
            String id; String code; String text; long uid;
            public String toString() { return code + "->" + text; }
        }
        final java.util.List<Entry> parsed = new java.util.ArrayList<>();
    }

    /** 读一个 varint，返回 {值, 新下标} */
    private static long[] rdVarint(byte[] b, int i) {
        long v = 0; int shift = 0;
        while (true) {
            int x = b[i++] & 0xff;
            v |= (long) (x & 0x7f) << shift;
            shift += 7;
            if ((x & 0x80) == 0) return new long[]{v, i};
        }
    }

    static HotWords parseHotWords(byte[] raw) {
        HotWords h = new HotWords();
        if (raw == null) return h;
        int i = 0;
        while (i < raw.length) {
            long[] kv = rdVarint(raw, i);
            int f = (int) (kv[0] >>> 3), wt = (int) (kv[0] & 7);
            i = (int) kv[1];
            if (wt == 0) {
                long[] vv = rdVarint(raw, i);
                i = (int) vv[1];
                if (f == 1) h.version = vv[0];
            } else if (wt == 2) {
                long[] lv = rdVarint(raw, i);
                i = (int) lv[1];
                int ln = (int) lv[0];
                if (f == 2 && h.data == null) h.data = java.util.Arrays.copyOfRange(raw, i, i + ln);
                i += ln;
            } else break;
        }
        if (h.data == null) return h;
        byte[] d = h.data;
        i = 0;
        while (i < d.length) {
            int start = i;
            long[] kv = rdVarint(d, i);
            int f = (int) (kv[0] >>> 3), wt = (int) (kv[0] & 7);
            i = (int) kv[1];
            if (wt == 0) {
                long[] vv = rdVarint(d, i);
                i = (int) vv[1];
                if (f == 1 && h.dataF1Raw == null) h.dataF1Raw = java.util.Arrays.copyOfRange(d, start, i);
                else if (f == 3) h.publisher = vv[0];
                else if (f == 4) h.ts = vv[0];
            } else if (wt == 2) {
                long[] lv = rdVarint(d, i);
                i = (int) lv[1];
                int ln = (int) lv[0];
                if (f == 2) {
                    byte[] e = java.util.Arrays.copyOfRange(d, i, i + ln);
                    h.entries.add(e);
                    h.parsed.add(parseEntry(e));
                }
                i += ln;
            } else break;
        }
        return h;
    }

    /** Entry { f1=id(str), f2=code, f3=text, f4=id(u64), f5, f9, f10 } */
    static HotWords.Entry parseEntry(byte[] e) {
        HotWords.Entry r = new HotWords.Entry();
        int i = 0;
        while (i < e.length) {
            long[] kv = rdVarint(e, i);
            int f = (int) (kv[0] >>> 3), wt = (int) (kv[0] & 7);
            i = (int) kv[1];
            if (wt == 0) {
                long[] vv = rdVarint(e, i); i = (int) vv[1];
                if (f == 4) r.uid = vv[0];
            } else if (wt == 2) {
                long[] lv = rdVarint(e, i); i = (int) lv[1];
                int ln = (int) lv[0];
                if (i + ln > e.length) break;
                String s = utf8(e, i, ln);
                if (f == 1) r.id = s; else if (f == 2) r.code = s; else if (f == 3) r.text = s;
                i += ln;
            } else break;
        }
        return r;
    }

    static String utf8(byte[] b, int off, int len) {
        try { return new String(b, off, len, "UTF-8"); } catch (Exception ex) { return ""; }
    }

    /**
     * 重建：**version 原样回传**（服务端做基准版本校验），data 里保留原 f1 与原条目，
     * 追加新条目后再写回 publisher / ts。原字节原样保留 → 不会丢未知字段、也不会污染已有条目。
     */
    static byte[] buildHotWords(HotWords h, long publisher, long ts, java.util.List<byte[]> extra) {
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        try {
            d.write(h.dataF1Raw != null ? h.dataF1Raw : vf(1, 1));
            for (byte[] e : h.entries) d.write(sf(2, e));
            if (extra != null) for (byte[] e : extra) d.write(sf(2, e));
            d.write(vf(3, publisher == 0 ? h.publisher : publisher));
            d.write(vf(4, ts == 0 ? h.ts : ts));
        } catch (Exception ignore) {}
        byte[] data = d.toByteArray();
        return cat(vf(1, h.version), sf(2, data));
    }

    /**
     * 一条常用语：{ f1=条目id(毫秒时间戳,字符串), f2=输入码, f3=内容, f4=同 f1(uint64), f5=0, f9=0, f10=1 }
     * 线上报文里 f4 恒等于 f1、body.f4 恒为「条目 id + 1」，即服务端以「发布时刻」为准。
     */
    static byte[] buildHotWordEntry(long id, String code, String text) {
        return cat(sf(1, String.valueOf(id)), sf(2, code == null ? "" : code),
                sf(3, text == null ? "" : text), vf(4, id), vf(5, 0), vf(9, 0), vf(10, 1));
    }

    // ---------- cmd39 设置组功能开关（剪贴板 / 常用语 / 个人词库 / 语音速记） ----------
    /**
     * FuncSwitch 位（general.hbc 的 FuncSwitch 枚举）：
     *   1 = FuncSwitchClipboard    跨设备复制粘贴
     *   2 = FuncSwitchUserHotWords 同步常用语
     *   4 = FuncSwitchUserDict     同步个人词库  ← **仅为协议常量，UI 不提供入口**
     *   8 = FuncSwitchVoiceRecap   语音速记
     * 开关状态保存在服务端（GroupInfo.f5 回读），所有关联设备可见。
     *
     * 关于 4：整包里只有这个能力位，没有任何词库数据字段/命令
     * （cmd34 用 filter=8/15 拉回的 Group 只有 groupId/devices/clipboard/hotwords/funcSwitch），
     * 且小布本地也没有「个人词库」（tab_vocabulary 是可下载的行业/方言词库包）。
     * 所以 UI 上已撤掉该开关，但常量保留：写掩码时按位或/与非，不能误伤这一位，
     * 否则会顺带改掉微信输入法各端原本的行为。
     */
    public static final int FUNC_CLIPBOARD = 1;
    public static final int FUNC_HOT_WORDS = 2;
    public static final int FUNC_USER_DICT = 4;
    public static final int FUNC_VOICE = 8;

    public boolean setGroupFuncSwitch(long groupId, long mask) throws Exception {
        // SetGroupFuncSwitchReq { uin=1(uint64), platform=2(uint32), groupId=3(uint64), funcSwitch=4(uint64) }
        byte[] body = cat(vf(1, Long.parseLong(uin)), vf(2, 1), vf(3, groupId), vf(4, mask));
        Pb rsp = biz(api(39, body));
        Long st = rsp.l(1);
        Long err = rsp.l(2);
        boolean ok = (st == null || st == 0) && (err == null || err == 0);
        MainHookLogger.log("event=funcswitch_rsp gid=" + groupId + " mask=" + mask
                + " status=" + st + " errCode=" + err + " ok=" + ok);
        return ok;
    }

    // ---------- cmd36 发布剪贴板 ----------    /** @return 新版本号（>0 表示成功），失败 -1 */
    /**
     * 剪贴板类型（对应微信 com.tencent.wetype.pb.GetGroupInfo$ClipboardType 的枚举值，
     * 已从 4.0.0 的 <clinit> 反编译确认：
     *   TypeNone=0, TypeTextOri=1, TypeTextCos=2, TypeImageOri=3, TypeImageCos=4）
     */
    static final int CLIP_TYPE_TEXT_ORI = 1;
    static final int CLIP_TYPE_IMAGE_ORI = 3;
    /**
     * 4.x 新增的「内容放在 COS」类型。真机抓包（2026-10-27，PC 端截屏复制）确认：
     *   type=6 的 binaryData(f6) 是一段 UTF-8 JSON：
     *   {"version":1,"path":"https://wxkb-user-data-1258344707.cos.ap-shanghai.myqcloud.com/clipboard/xxxx"}
     * 即图片字节不内联，只给一个下载地址。收到后必须自己 GET 下来再写剪贴板，
     * 否则这条消息会被当成未知类型整条丢弃——这正是「PC 复制图片手机端毫无反应」的直接原因。
     */
    static final int CLIP_TYPE_COS = 6;

    public long publish(long groupId, String text, String trace) throws Exception {
        // ClipboardMsg{f2=type(1=TextOri), f6=数据, f9=trace_id}
        // f6 是 length-delimited，字符串与二进制共用同一字段，靠 type 区分
        byte[] msg = cat(vf(2, CLIP_TYPE_TEXT_ORI), sf(6, text), sf(9, trace));
        byte[] body = cat(vf(1, Long.parseLong(uin)), vf(2, 1), sf(3, msg), vf(4, groupId));
        Pb rsp = biz(api(36, body));
        Long st = rsp.l(1);
        Long ver = rsp.l(2);
        MainHookLogger.log("event=publish_rsp status=" + st + " version=" + ver);
        if (st == null || st != 0) return -1;
        return ver == null ? -1 : ver;
    }

    /**
     * 发布一张图片（TypeImageOri，内联传输）。
     * jpeg 已经是压缩后的字节（见 ClipImageCodec），这里不再做任何加工。
     */
    public long publishImage(long groupId, byte[] jpeg, String trace) throws Exception {
        if (jpeg == null || jpeg.length == 0) return -1;
        byte[] msg = cat(vf(2, CLIP_TYPE_IMAGE_ORI), sf(6, jpeg), sf(9, trace));
        byte[] body = cat(vf(1, Long.parseLong(uin)), vf(2, 1), sf(3, msg), vf(4, groupId));
        Pb rsp = biz(api(36, body));
        Long st = rsp.l(1);
        Long ver = rsp.l(2);
        MainHookLogger.log("event=publish_img_rsp status=" + st + " version=" + ver
                + " bytes=" + jpeg.length);
        if (st == null || st != 0) return -1;
        return ver == null ? -1 : ver;
    }

    /** 从 GroupResponse 里取 Group{f1=group_id, f2=devices, f3=clipboard} */
    public static Pb groupOf(Pb groupResponse) {
        return groupResponse.sub(1);
    }

    /** 从 Group 里取 ClipboardMsg{f1=version,f2=type,f6=data,f7=from_uin,f9=trace} */
    public static Pb clipboardOf(Pb group) {
        return group == null ? null : group.sub(3);
    }

    /** 本机是否已在组内且组内还有别的设备 */
    public static boolean paired(Pb groupResponse) {
        Pb g = groupOf(groupResponse);
        if (g == null) return false;
        Long gid = g.l(1);
        if (gid == null || gid <= 0) return false;
        return true;
    }
}
