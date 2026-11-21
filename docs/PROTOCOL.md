# 协议

> 对象：微信输入法（WeType）4.0.0 · 服务端 `wetype.weixin.qq.com`
> 说明：以下字段均来自对 Android 客户端的静态分析与真机抓包对照。本项目只使用其中一部分，
> 列出的作用是让后来者不必重复逆向。

## 1. 传输层

```
POST https://wetype.weixin.qq.com/api_v2      HTTP/1.1
Host: wetype.weixin.qq.com
Kb-OsType: 1                 # 1=Android
Kb-Version: 3.5.4.57423
Kb-SystemVersion: 34
Kb-PackageType: 2
Kb-TimeStamp: <毫秒>
Kb-BodyMd5: <body 的 MD5 大写>
Kb-TraceId: <32 hex>
Kb-TaskId: <自增>
Kb-Scene: 1
Kb-CompressionType: 1        # 请求体压缩方式
Kb-Uin: <uin>
Kb-CmdId: 4814
Kb-SubCmdId: <子命令>
Kb-SharedKeySuffix: <key[28..32]>
Kb-Sign: <见下>
Content-Type: application/octet-stream
```

响应固定带 `Kb-CompressionType: 2`，body 是 **snappy** 压缩；解压后是 `BaseResponse`，
业务体在 **f6**，`status != 0` 即失败。

## 2. 握手

```
① /timestamp             task=0，不加签，用于校准时钟
② /oauth_pubkey_v2       task=2147483646，请求体用 AES-256-ECB + BootstrapKey 加密
     EcdhRequest { f1=pubkey_hex66, f2=previous_server_key, f3=need_uin,
                   f4=device_code, f5=uin, f6=uin_token, f7=device_token,
                   f8=device_info, f9=device_name }
③ EcdhResponse { f1=status, f2=server_pubkey, f3=key_expiry, f4=gen_uin_token }
```

- 曲线：**secp128r1**（非 secp256r1，容易踩坑）
- 共享点取 **X 坐标 16 字节** → HEX 大写 32 字符 → 作为 AES-256 的 key
- `Kb-SharedKeySuffix = key[28..32]`
- 首次入组时 `uin` 字段**整个不编码**，`uin_token` / `device_token` 编空串
- 拿到 `gen_uin_token` 后，用它调 `/gen_uin_v2` 完成绑定

`SALT`、`BOOT`（BootstrapKey）等常量定义在
[`WxClient.java`](../app/src/main/java/com/cxhuyo/wtsync/WxClient.java) 顶部。

## 3. 签名

```
Kb-Sign = SHA256( 按端点固定顺序拼接的头字段 + SALT )   // 大写 HEX
```

`/timestamp` 是唯一不签名、不校验的端点。每个子命令参与拼接的字段集合不同，实现见
`WxClient.build()` / `sign()`，改动端点时必须同步改顺序，否则一律 `status=1002`。

## 4. 子命令

| 子命令 | 用途 | 请求体 | 关键响应 |
|---|---|---|---|
| 30 | 申请关联码 | `CodeRequest{uin=1, platform=2, device=3}` | 6 位码 |
| 31 | 输入对端码加入 | `BindRequest{uin=1, platform=2, code=3, device=5}` | bind_id |
| 32 | 确认关联 | `FetchBindRequest{uin=1, platform=2, bind_id=3}` | state |
| 34 | 组信息 + 剪贴板查询 | `GroupRequest{uin=1, platform=2, filter=3, clipboard_version=4}` | `Group{f1=gid, f2=设备列表, f5=func_mask}` + `ClipboardMsg` |
| 36 | 发布剪贴板 | `SendClipboardRequest{uin=1, platform=2, msg=3, group_id=4}` | 新 version |
| 37 | 发布常用语 | `PushUserHotWordsRequest{group_id, user_hot_words}` | status |
| 38 | 查询加入进度 | `{bind_id}` | 0=等待 1=完成 2=取消 |
| 40 | 轮询本机码 | `{code}` | 0=无人加入 1=待确认 2=失效 |
| 42 | 个人词库增量 | `{f2=since_msg_id, f3=group_id}` | `{f1=new_msg_id, f2=uin, f3=entries}` |

`func_mask`（cmd34 的 f5）是服务端的**组级功能位**：剪贴板、常用语等开关最终以它为准，
本地开关只决定"要不要处理"，不决定"服务端给不给"。

## 5. 结构体

```protobuf
ClipboardMsg {
  f1: version      // 单调递增
  f2: type         // 1=文本 3=内联图片 6=COS JSON 2/4=COS 直链
  f3: text?        // 部分端会带一份明文文本副本
  f6: data         // 文本字节 / jpeg 字节 / COS 引用
  f7: from_uin     // 发布者
  f9: trace_id
}

Group {
  f1: group_id
  f2: repeated BoundInfo { f1: uin, f2: GroupDeviceInfo { f1: name } }
  f5: func_mask
}

Entry {                  // 个人词库
  f3: pinyin             // 逗号分隔
  f4: value              // 见下
}

value = [0:2] 词长(BE) | [2] type | [3:5] commits(LE u16)
        | [7:11] 时间戳(LE u32) | 末尾 N 字节正文(UTF-8)
```

## 6. COS 图片

两条路径，最终都要拿到一个 `https://wxkb-user-data-*.cos.*.myqcloud.com/clipboard/<group>/<id>`：

| type | f6 形态 | 处理 |
|---|---|---|
| 6 | `{"version":1,"path":"https://…","md5":"…","key":"<base64 16B>"}` | 下载 → `AES-128-CTR`（key 同时作 IV）→ 校验 md5 |
| 2 / 4 | 纯文本：URL（可能换行跟 md5、key） | 解析成上面的 JSON 形态后复用同一条链路 |

上传方向（手机 → PC）走 `cmd36` + `type=3` 内联 jpeg；超过 256 KiB 的文本目前不做 COS 上传。

## 7. 尚未定位

`ApplyCosTokenReqV2`：个人词库的**全量快照**入口，返回 COS 预签名 URL。APK 只有 protobuf-lite
（无字段表），请求体 AES 加密且符号被 strip，静态分析到此为止。

实际影响有限：模块跑增量，只要设备持续在线，新词都能同步进来；只有"首次接入一个已经用了很久的
账号"时拿不到历史词。

## 8. 参考时序

```
client                          server
  │── /timestamp ────────────────►│
  │◄──────────── server_time ─────│
  │── /oauth_pubkey_v2 (ECB) ────►│
  │◄──────── EcdhResponse ────────│
  │── /gen_uin_v2 (uin_token) ───►│
  │◄──────── ok ──────────────────│
  │── cmd34 (group + clipboard) ─►│   ← 之后每 2s 一次
  │◄──────── Group + ClipboardMsg │
  │── cmd36 (发布本地剪贴板) ─────►│
  │◄──────── new_version ─────────│
```
