# 架构

> 面向二次开发与排障。协议细节见 [PROTOCOL.md](PROTOCOL.md)，现象排查见 [TROUBLESHOOTING.md](TROUBLESHOOTING.md)。

## 1. 设计边界

| 决策 | 原因 |
|---|---|
| 作用域只挂 `com.oplus.keyboard` | 宿主是唯一需要注入的进程；不进微信进程、不读微信文件 |
| 协议自己实现，不复用微信客户端 | 微信没有对外 SDK，`compileOnly` 依赖 libxposed，运行期零第三方库 |
| UI 复用宿主组件 | 设置页与同步页必须与宿主风格一致，且要跟随深浅色主题 |
| 数据不落独立数据库 | 剪贴板历史用宿主自己的 `tab_clipboard_data`，词库走宿主 API |

模块 APK 里有两条执行路径：

- **注入部分**（`MainHook` 起）只在宿主进程内运行，常驻；所有网络与剪贴板逻辑都在这条线上。
- **配置部分**（`SettingsActivity`）是普通 Activity，通过**显式广播**（`setPackage("com.oplus.keyboard")`）
  把配置下发给注入侧，自己不做任何网络请求。

## 2. Hook 点

| 目标 | 时机 | 作用 |
|---|---|---|
| `ClipboardManager.setPrimaryClip(ClipData)` | intercept，`chain.proceed()` 之后不改行为 | 捕获「本地复制」，文本立即推送、图片交给后台线程编码上传 |
| `InputMethodService.onWindowShown()` | intercept | 键盘亮起 = 系统放开 `WRITE_CLIPBOARD`：补写待写队列、恢复最近接收、评估是否补发 |
| 宿主设置页 Fragment 的 `onCreateView` / `onResume` | 反射查找 | 注入「跨设备同步」「个人词库」两个原生卡片入口 |

`setPrimaryClip` 的 hook 里做了三层防御：`getItemCount() > 0` 判空（空 `ClipData` 取 0 号项会抛异常，
异常逃出 hook 会打断输入法自身的写剪贴板流程）、`isSynced(description)` 过滤自己写的、
`try/catch` 兜住所有异常。

## 3. 数据流

### 3.1 轮询主循环

```
loop:
  groupOf()            # cmd34：组信息 + 设备列表 + 最新剪贴板
  ├─ 剪贴板有新版本？ → 按 type 分发（1 文本 / 3 内联图 / 6 COS JSON / 2、4 COS 直链）
  ├─ flushPendingText  # 待写队列重试（内部 1s 节流 + 三道判据）
  └─ 发布本地剪贴板     # 内容变 / 时间戳变 / 启动补发
sleep(2s) 或 flushLock.wait(2s)   # onImeShown 会 notifyAll 打断等待，立刻跑一轮
```

`POLL_MS = 2000`。心跳频率与微信客户端接近，既能保证实时性，也不会触发服务端限流。

### 3.2 发送（手机 → PC）

触发条件三者任一：

1. `contentChanged` —— 内容与 `lastLocalText` 不同（普通复制）；
2. `stampChanged` —— `ClipDescription.getTimestamp()` 变化（用户又复制了同一段文字）；
3. `startupPending` —— 进程刚起，且剪贴板里是 30 分钟内、尚未同步过的内容。

命中后依次过闸：

```
rx_echo（这段是刚收进来的）→ startup_stale（太旧的不补发）
→ just_applied（3s 内刚写完对端内容）→ just_sent（2s 内同内容只发一次）
→ 长度 ≤ 256 KiB → cmd36 发布
```

发布失败（返回 `v <= 0` 或抛异常）会 `rollbackTx()` 把 `lastLocalText` / `lastSeenClipTs` 退回上一轮，
下一轮重试，避免"发失败还当已发"导致永久丢内容。

### 3.3 接收（PC → 手机）

```
ClipboardMsg{f1=version, f2=type, f6=data, f7=from_uin}
```

| type | 含义 | 处理 |
|---|---|---|
| 1 | 文本 | UTF-8 解码 → 写系统剪贴板 → 失败则入待写队列 + 补录面板历史 |
| 3 | 内联图片（jpeg 字节） | 直接落 MediaStore → 写 `content://` URI |
| 6 | COS 引用（JSON：`path` / `md5` / `key`） | 下载 → AES-128-CTR 解密 → `toJpeg` → 写 URI |
| 2、4 | COS 直链（纯文本 URL，可能带 md5/key） | `cosRefToJson()` 转成 type=6 的形态后复用同一条链路 |

`from` 为空或等于自己的 `uin` 一律只推进水位、不动剪贴板 —— 这是防"自己发出去的内容被服务端回传后
又被当成远端新内容"的第一道闸。

### 3.4 词库（微信 → 小布）

```
cmd42 {since=msgId, group} → {newMsgId, entries[]}
  Entry{ f3=pinyin, f4=value }
  value = [0:2]词长(BE) | [2]type | [3:5]commits(LE u16) | [7:11]ts(LE u32) | 正文
→ 按中英文分流 → 合并进宿主导出 JSON → userDictSyncImport → restartForVocabulary()
```

游标存在 `ud_msgid.txt`，按 5 分钟节奏增量拉取；手动「立即同步」走同一条路径。

### 3.5 常用语

`cmd37` 发布本地新增，`cmd34` 响应里带回对端热词；用 `hw_synced2.txt` 记录已合并内容的指纹，
避免每轮重复导入。

## 4. 防回环模型

这是整个模块最容易出问题的地方，一共四道闸：

| 机制 | 作用域 | 说明 |
|---|---|---|
| `lastHash` / `lastTxHash` + `DEDUP_TTL_MS(10min)` | 接收侧 | 收到与本机最近发过/收过的内容相同 → 只推进水位，不写剪贴板 |
| `rxEchoHash` | 发送侧（一次性） | 标记"这段是收来的"，下一轮被判为本地复制时吞掉一次。**必须在写剪贴板之前无条件打标**，否则"剪贴板里已有同一段 → 不写 → 没打标"会形成 A→B→A 循环 |
| `RX_SUPPRESS_MS(3s)` | 发送侧 | 刚写完对端内容后的抑制窗，杜绝"收到即回传" |
| `SELF_ECHO_MS(2s)` | 发送侧 | 同内容 2 秒内只发一次（内容变 + 时间戳变同时命中的去抖） |
| `ClipDescription` extras `cx.synced` | 双向 | 自己写剪贴板时打的标记，`setPrimaryClip` hook 见到就不上报 |

## 5. 状态文件

全部位于 `/data/data/com.oplus.keyboard/files/wtsync/`：

| 文件 | 内容 | 用途 |
|---|---|---|
| `identity.txt` | uin / deviceName / deviceCode / privD / serverKey | 设备身份，重启后保持入组 |
| `token.txt` | user token | 配置页下发 |
| `clip_state.txt` | `ver|lastHash|lastTxHash|lastHashAt|lastTxAt|lastSeenClipTs|rxEchoHash` | 水位与去重状态 |
| `clip_pending.txt` | `ver|since|base64(text)` | 写失败的待写文本（TTL 12h） |
| `clip_lastrx.txt` | `at|base64(text)` | 最近一次收到的文本，剪贴板被清空后补回 |
| `ud_msgid.txt` | 词库增量游标 | 断点续拉 |
| `img_on.txt` / `ud_on.txt` | `1`/`0` | 图片、词库同步开关（缺省开） |
| `hw_synced2.txt` | 已合并热词指纹 | 去重 |
| `module.log` | 运行日志 | 排障 |

## 6. 冻结与"写不进去"

ColorOS 在键盘收起后会把输入法进程塞进 `cgroup.freeze`，线程完全停摆；即使刚解冻，
`getPrimaryClip()` 也可能返回 `null`（`READ_CLIPBOARD` 是 foreground 语义）。

所以"写剪贴板成功"不能只看 `setPrimaryClip` 有没有抛异常：

- 写后立刻读回校验，读回失败记为 `applied=false`；
- `applied=false` **不等于**写失败（可能只是读回被拒），因此不作为唯一判据；
- 真正失败的内容进待写队列，等下一次 `onWindowShown` 立刻补写；
- 同时用 `XiaobuClipDb.recordText()` 替宿主补录面板历史 —— 宿主在 `applied=false` 时同样读不到
  剪贴板，它自己的 `OnPrimaryClipChangedListener` 不会入库，面板就会出现"系统剪贴板有、列表里没有"
  的假不同步。

## 7. 线程模型

| 线程 | 职责 |
|---|---|
| `WtClient` 轮询线程 | 心跳、收发、落盘；所有网络阻塞都在这里 |
| `WtClipImgTx` | 图片解码/压缩/上传（主线程不能碰 `BitmapFactory`，4K 图会卡死输入法） |
| 主线程 | 只做 hook 判定与 UI 注入，任何耗时操作都甩出去 |

`flushLock` 兼作休眠锁：空闲时 `wait(2000)`，键盘亮起时 `notifyAll()` 立刻跑一轮，省掉最多 2 秒延迟。
