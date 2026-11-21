# 排障手册

## 0. 先拿到日志

```bash
adb shell su -c 'cp /data/data/com.oplus.keyboard/files/wtsync/module.log /sdcard/m.log'
adb pull /sdcard/m.log
```

日志是按行追加的文本，`event=` 之后是事件名。排查时先按事件名过滤，比从头翻快得多：

```bash
grep -E "event=(package_ready|loop_start|identity_ok|clip_|clipdb_|clipimg_)" m.log | tail -60
```

---

## 1. 模块没生效

| 检查 | 方法 |
|---|---|
| LSPosed 是否启用 | 管理器里模块状态为「已启用」 |
| 作用域是否正确 | **只勾 `com.oplus.keyboard`**，勾了微信反而不会生效 |
| 宿主是否重启 | `am force-stop com.oplus.keyboard` 后重新弹键盘 |
| 版本是否匹配 | `dumpsys package com.cxhuyo.wetsync \| grep versionName` |

日志里没有 `event=module_loaded` ⇒ 注入没发生；有 `module_loaded` 但没有 `package_ready`
⇒ 作用域不对或包名不匹配。

## 2. PC 复制文本，手机没反应

按顺序看四件事：

1. **服务端有没有新版本**
   `event=clip_hdr ver=<n> type=1 from=<PC_UIN>` —— 有这行说明数据到了，问题在本地写入。
2. **是不是被当成回声吃掉了**
   `event=clip_tx_skip reason=rx_echo` 属正常（自己刚发出去的内容回传）；
   若 `from` 是 PC 的 uin 却出现这条，说明 `rxEchoHash` 误判，需要清 `clip_state.txt`。
3. **写剪贴板是否被拒**
   `event=clip_write_text applied=false` 表示写后读回失败。这**不一定**是写失败 —— ColorOS 在
   键盘收起时不放开 `WRITE_CLIPBOARD`，读回同样会被拒。
4. **有没有进待写队列**
   `event=clip_rx_pending` → `event=clip_pending_write` → `event=clip_pending_done why=ok|already`

处理：弹出一次键盘即可触发补写（`onWindowShown` 会立刻跑一轮）。若长期停在 pending，
检查 `files/wtsync/clip_pending.txt` 是否还在、时间戳是否已超过 12 小时 TTL。

## 3. 息屏 → PC 复制 → 亮屏，内容没同步

这是最典型的场景，链路最长：

```
PC 复制 → 服务端 ver+1 → 手机下一轮 cmd34 收到
       → 写剪贴板（可能被冻结拒绝）→ 入待写队列
       → 亮屏弹键盘 → onWindowShown → 补写 → 宿主记历史
```

判据与处理：

| 日志 | 含义 | 处理 |
|---|---|---|
| 有 `clip_rx`，随后 `clip_pending_done why=ok` | 正常补写 | — |
| 有 `clip_rx`，但一直没有 `clip_pending_done` | 键盘没亮过，或进程一直被冻结 | 点亮屏幕并弹出一次键盘 |
| 连 `clip_hdr` 都没有 | 网络/心跳停了 | 看 `http_connect` / `api_status`，确认未被限流 |
| 有 `clip_pending_done why=superseded` | 期间用户又复制了别的内容 | 属预期行为，不做覆盖 |

## 4. 面板里有内容，但"粘贴"拿不到 / 面板缺行

小布自己的 `OnPrimaryClipChangedListener` 需要**读**剪贴板才能入库。键盘收起时它读不到，
于是会出现「系统剪贴板有内容、面板列表里却没有」的假不同步。

模块为此做了两件事：

- 写剪贴板失败时，用 `XiaobuClipDb.recordText()` 直接按宿主表结构补录一行；
- 剪贴板被系统/linker 清空后，键盘亮起时用 `clip_lastrx.txt` 把最近一条补回。

检查表的状态（需要临时开启诊断）：

```bash
adb shell su -c 'touch /data/data/com.oplus.keyboard/files/wtsync/diag.on'
# 然后在设置页触发一次「立即同步」，或等一轮心跳
adb shell su -c 'grep -a "event=clipdb" /data/data/com.oplus.keyboard/files/wtsync/module.log | tail'
```

可用命令：`clipdb|stat`、`clipdb|trim|<n>`、`clipdb|dedup`、`clipdb|clear`。
执行前后都会打印 `clip=<before>-><after> vocab=<before>-><after>`。

## 5. 同一段内容反复出现 / 一直在"循环复制"

历史上有两个成因，都已修复，遇到时先确认版本 ≥ 3.18.8：

| 成因 | 特征日志 | 修复版本 |
|---|---|---|
| 补录时"用原文本去重、写截断后文本"，永远匹配不上 | `clipdb_insert len=65536` 反复出现、`clipdb_dup` 从不命中 | 3.18.7 |
| 收到内容时剪贴板里已有同一段 → 不写 → 没打回声标记 → 下轮当成本地复制发回对端 | `clip_tx ver=…` 紧跟 `clip_hdr … from=<自己 uin>` | 3.18.7 |

若仍复现，先清重复行：`clipdb|dedup`（只 DELETE，不动词库，执行前会自动打印影响行数）。

另外 `event=clip_stamp_only … desc=<label>/ext` 表示「内容没变，但剪贴板被别的进程重设了」。
`desc` 结尾的 `self` 表示是模块自己写的，`ext` 表示外部进程（跨屏互联、宿主面板重复制等）。
`ext` 触发重发属于预期行为，不是 bug。

## 6. PC 复制图片，手机没反应

```bash
grep -E "clip_hdr|clip_ref_raw|clipcos_|clipimg_" m.log | tail -30
```

| 日志 | 含义 |
|---|---|
| `clip_hdr type=6` + `clipcos_dec md5ok=true` + `clipimg_rx` | 正常 |
| `clip_hdr type=2` / `type=4` + `clipcos_ref_parse` | COS 直链形态，正常链路 |
| `clipcos_give_up` | 下载三次失败：COS 地址过期或网络被限 |
| `clipcos_dec … md5ok=false` | 密钥不对，图片被丢弃（不会写坏图） |
| 只有 `clip_hdr`，后面什么都没有 | 该 type 未覆盖，请附 `clip_ref_raw` 提 issue |

## 7. 词库不同步

```bash
grep -E "wxdict_delta|userdict_sync|xbd_merge|ud_" m.log | tail -20
```

- `userdict_sync ok=false`：宿主 API 调用失败，通常是版本不匹配；
- `added=0` 但 `got>0`：词条已存在，属正常去重；
- 一直 `since=<同一个值>`：游标没推进，删掉 `ud_msgid.txt` 后重启宿主会重新拉取；
- 首次接入老账号拿不到历史词 —— 已知限制，见 README。

## 8. 输入法卡顿 / 偶发无响应

模块在 `setPrimaryClip` 的 hook 里做过三层防御（判空、过滤自写、全异常兜底），
若仍卡顿，先确认不是图片解码跑到主线程：日志里 `clipimg_tx` 应出现在后台线程 `WtClipImgTx`。

## 9. 彻底重置

```bash
adb shell su -c 'am force-stop com.oplus.keyboard'
adb shell su -c 'rm -rf /data/data/com.oplus.keyboard/files/wtsync'
# 弹出一次键盘，模块会自动匿名注册新身份；再到「跨设备同步」里重新关联设备
```

`wtsync` 目录只存模块状态，删掉不影响宿主本身的输入法设置与词库。

## 10. 日志事件速查

| 事件 | 含义 |
|---|---|
| `module_loaded` / `package_ready` | 注入成功 |
| `identity_ok` | 身份文件读取成功 |
| `hs_status` / `hs_key` | 握手与共享密钥 |
| `clip_hdr` / `clip_bin` | 服务端返回的剪贴板头（诊断用） |
| `clip_rx` / `clip_rx_dup` | 收到远端文本 / 判定为重复 |
| `clip_tx` / `clip_tx_skip` | 发布本地文本 / 跳过原因 |
| `clip_pending_*` | 待写队列：入队、补写、完成、过期、被更新内容顶替 |
| `clip_stamp_only` | 只有时间戳变化（外部进程重设剪贴板） |
| `clipdb_insert` / `clipdb_dup` | 面板历史补录 / 命中重复 |
| `clipimg_rx` / `clipimg_tx` | 图片收发 |
| `clipcos_ref` / `clipcos_dec` / `clipcos_ok` | COS 下载、解密、落盘 |
| `hotwords_sync` / `userdict_sync` | 常用语 / 词库同步 |
