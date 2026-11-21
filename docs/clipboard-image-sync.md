# 剪贴板图片同步 · 逆向分析与落地方案

> 对照对象：微信输入法 4.0.0（`com.tencent.wetype`） / 小布输入法 1.8.35.17-mkt（`com.oplus.keyboard`）
> 分析方式：dex 静态反编译（androguard 4.1.4 + 自写 dex 解析脚本）
> 日期：2026-10-26

---

> **后续进展（2026-11-21 补充）**
>
> 本文写于 2026-10-26，当时判断「COS 上传缺少凭证、只做 Ori 内联」。该结论后来被推翻：
> 服务端在 `cmd34` 的响应里就带着 COS 引用（`type=6` 的 JSON 内有 `md5` 与 16 字节 `key`），
> 解密方式是 **AES-128-CTR 且 key 复用为 IV**，因此接收方向可以直接落地；
> 发送方向则走 `cmd36` + `type=3` 内联 jpeg，超过阈值才需要 COS。
> 另外 PC 端还会发 `type=2/4` 的**纯文本直链**形态，`cosRefToJson()` 已统一收敛到同一条链路。
>
> 阅读时请以代码为准，本文保留的是逆向过程与宿主侧类结构分析。


## 一、结论先行

三个核心判断，直接决定工作量：

**判断 1：小布已经有完整的图片剪贴板能力，不需要新建数据层。**
1.8.35.17 内置 `ClipboardType.TEXT / IMAGE / VIDEO` 三态，数据库表 `tab_clipboard_data`
已含 `clipboard_uri` 与 `clipboard_mime_types` 字段，另有 `tab_clipboard_file` 存文件元信息，
并且拥有自己的 FileProvider `com.oplus.keyboard.provider.clipboard`。

**判断 2：缺的不是"识别"，而是"跨设备"这一跳。**
小布的 `OnPrimaryClipChangedListener` 已能正确识别 `image/*` 并入库、在 `ClipBoardView` 展示。
本模块当前的 `cmd36` 发布逻辑只走文本路径（`ClipboardMsg.type = 1`），图片完全没有进入同步链路。

**判断 3：微信的协议早已为图片留好位置，且分"内联"和"对象存储"两条路。**
`ClipboardType` 有 5 个值：`TypeNone / TypeTextOri / TypeTextCos / TypeImageOri / TypeImageCos`。
`Ori` = 原样内联，`Cos` = 先传 COS 再发引用。**本项目只做 Ori**，Cos 因缺少上传凭证无法实现
（与既有决策 D7 同源）。

**结论**：工作量集中在**协议层扩展 + 传输策略 + 落地写入**三处，UI 层可基本复用小布原生面板。

---

## 二、小布输入法侧分析

### 2.1 类结构（R8 混淆后）

主类被 R8 改名，但内部类名因 lambda 字符串常量得以保留，可据此反查：

| 角色 | 实际类名 | 说明 |
|---|---|---|
| 剪贴板管理器 | `com.oplus.keyboard.input.function.e` | 由内部类 `this$0` 字段反向定位 |
| 数据实体 | `com.oplus.keyboard.db.clipboard.a` | 对应 `tab_clipboard_data` 一行 |
| 转换器 | `com.oplus.keyboard.db.clipboard.a$a` | `a(Context, ClipData) → Entity` |
| 类型枚举 | `com.oplus.keyboard.db.clipboard.ClipboardType` | TEXT / IMAGE / VIDEO |
| UI | `com.oplus.keyboard.input.view.body.ClipBoardView` | 原生剪贴板面板 |
| 辅助 | `com.oplus.keyboard.input.view.ClipboardHelper` | 含 `ClipFromType` 子枚举 |

### 2.2 监听与识别

`e.onPrimaryClipChanged()` 的实现（反编译还原）：

```kotlin
override fun onPrimaryClipChanged() {
    if (!base.data.e.j()) return                       // 总开关
    if (!L.n) return                                    // 次级开关
    val cm = clipboardManager ?: return
    val clip: ClipData? = cm.primaryClip
    // 仅用于日志：取第 0 项的文本长度
    Log.d("[onPrimaryClipChanged] systemClip " + clip?.getItemAt(0)?.text?.length)
    // 关键：把整个 ClipData 交给协程处理
    scope.launch { handleClipChanged(clip, BooleanRef()) }
}
```

协程内部（`ClipboardManager$onPrimaryClipChanged$1$1.invokeSuspend`）的关键分支：

```kotlin
val item = clip.getItemAt(0)
val text = item?.text
if (!text.isNullOrBlank()) {
    // 文本分支：先与上一次比对去重
} else {
    val uri = item?.uri
    // URI 分支：同样做去重比对
}
val entity = com.oplus.keyboard.db.clipboard.a.a(context, clip)   // ← 统一转实体
insertOrUpdateClipboardData(entity)                                // ← Room 入库
```

**要点**：识别动作本身对小布是**透明的**——它不区分"这是从哪来的"，只要系统剪贴板变了就转实体入库。

### 2.3 类型判定逻辑（完整还原）

`a$a.a(Context, ClipData)` 的核心：

```kotlin
val item = data.getItemAt(0)
val uri = item?.uri

val type = when {
    uri != null && data.description.hasMimeType("image/*") -> ClipboardType.IMAGE
    uri != null && data.description.hasMimeType("video/*") -> ClipboardType.VIDEO
    else                                                    -> ClipboardType.TEXT
}

val text = item?.text?.toString()

if (type == IMAGE || type == VIDEO) {
    // 判断 URI 是否已经在小布自己的 provider 下
    if (item.uri.authority == "com.oplus.keyboard.provider.clipboard") {
        // 已在私有目录，直接引用
    } else {
        // 外部 URI，需要复制进私有目录并重新生成 URI
    }
    // 用 OpenableColumns.DISPLAY_NAME 取文件名
}
```

**三条关键信息**：

1. 判定条件为 `hasMimeType("image/*")` **且** `uri != null`——两者缺一不可。
   `ClipData.Item` 只有 `Uri` 或者只有 `Text` 时行为不同，纯 `Text` 走文本分支。
2. 小布**会把外部图片复制到自己的私有目录**，再用 `com.oplus.keyboard.provider.clipboard` 暴露。
   这意味着后续所有图片都是"自家 URI"，权限问题被 internal 消化掉了。
3. 文件名通过 `OpenableColumns.DISPLAY_NAME` 查询获取，对应 `tab_clipboard_file.clipboard_file_name`。

### 2.4 存储结构

```sql
CREATE TABLE tab_clipboard_data (
  clipboard_id     INTEGER PRIMARY KEY AUTOINCREMENT,
  clipboard_type   INTEGER NOT NULL,      -- ClipboardType
  clipboard_text   TEXT,
  clipboard_preview_text TEXT,
  clipboard_uri    TEXT,                  -- 图片/视频走这里
  clipboard_time   INTEGER NOT NULL,
  clipboard_mime_types TEXT NOT NULL
);

CREATE TABLE tab_clipboard_file (
  clipboard_file_id   INTEGER PRIMARY KEY,
  clipboard_file_name TEXT NOT NULL,
  clipboard_file_size INTEGER NOT NULL,
  clipboard_file_types TEXT NOT NULL
);

CREATE TABLE tab_clipboard_extract_data (   -- 从文本里提取的结构化内容
  id, clipboard_extract_id, clipboard_extract_data
);
```

去重键：`clipboard_text` 或 `clipboard_uri`（见源码里的 `CASE ? WHEN 1 THEN (clipboard_text = ?) ...`）。

### 2.5 小布侧的能力边界

| 能力 | 状态 |
|---|---|
| 识别系统剪贴板中的图片 | ✅ 已实现 |
| 复制图片进私有目录 | ✅ 已实现 |
| 在剪贴板面板展示图片缩略图 | ✅ 已实现（`ClipBoardView`） |
| 图片分类/搜索 | 部分（`clipboard_extract_data` 面向文本） |
| **跨设备同步图片** | ❌ 本模块要补的部分 |

---

## 三、微信输入法侧分析

### 3.1 类型枚举（关键）

```
com.tencent.wetype.pb.GetGroupInfo$ClipboardType
  TypeNone
  TypeTextOri     ← 文本，原样内联
  TypeTextCos     ← 文本，走 COS
  TypeImageOri    ← 图片，原样内联
  TypeImageCos    ← 图片，走 COS
```

**这套设计的含义**：同一种内容有两种传输策略。
数据小就用 `Ori` 直接塞进消息体；数据大就先上传 COS，消息里只放引用。
微信在发送时按体积二选一，接收方按 `type` 决定"直接读字节"还是"去 COS 拉"。

### 3.2 ClipboardMsg 字段

反编译 `writeTo` 得到的字段序（与既有模块实现对照）：

| 字段 | 类型 | 既有模块已实现 | 说明 |
|---|---|---|---|
| `version` | int32 | ✅ f1 | 版本 |
| `type` | enum | ✅ f2 | `ClipboardType` |
| `data` | string | ❌（4.0 新增） | 文本正文 / COS 路径 |
| `extraInfo` | string | ❌（4.0 新增） | 附加信息（推断：文件名、mime、尺寸） |
| `expiration` | — | ❌（4.0 新增） | 过期策略 |
| `expireAt` | int64 | ❌（4.0 新增） | 过期时间点 |
| `binaryData` | bytes | ✅ f6 | **图片原始字节** |
| `fromUin` | uint64 | ✅ f7 | 发送方 |
| `—` | — | — | f8 |
| `traceId` | string | ✅ f9 | 追踪 ID |

**推断（需实测确认）**：`TypeImageOri` 时图片字节放 `binaryData`（f6）；
`TypeImageCos` 时 `data`（f3）放 COS 路径。这与"Ori 内联 / Cos 引用"的命名一致。

### 3.3 微信侧的图片流程（推断）

```
监听剪贴板变化
  → 若是 image/*：
      读 URI → 解码 → 按体积分支：
        小图 → type=TypeImageOri，binaryData = 压缩后字节
        大图 → 上传 COS → type=TypeImageCos，data = cos_path
  → SendClipboardMsgReq
对端：
  收到 type=ImageOri → 取 binaryData → 落文件 → 写回剪贴板（content:// URI）
```

---

## 四、两侧对比

| 维度 | 微信输入法 4.0.0 | 小布输入法 1.8.35.17 |
|---|---|---|
| 监听方式 | `OnPrimaryClipChangedListener`，丢进协程 | 同左，协程 + `Mutex` 串行化 |
| 类型判定 | `ClipboardType` 5 值（含传输策略） | `ClipboardType` 3 值（仅内容类型） |
| 图片存储 | 私有目录 + COS（大图） | 私有目录 + FileProvider |
| 传输策略 | Ori 内联 / Cos 对象存储，按体积切换 | 无（不具备跨设备能力） |
| 去重 | 未确认 | 按 text / uri 比对 |
| UI | React Native 侧面板 | 原生 `ClipBoardView` |
| 过期清理 | 有 `expiration` / `expireAt` 字段 | 未见显式过期字段 |

**核心差异**：微信把"传输策略"编码进了类型枚举，小布的类型只表达"内容是什么"。
因此**同步层的策略判断必须由模块承担**——小布不会告诉我们该怎么传。

---

## 五、落地方案

### 5.1 总体思路

```
本地复制图片
   │
   ├─ 小布自己：识别 → 私有目录 → 入库 → 面板展示（已有，复用）
   │
   └─ 本模块：hook setPrimaryClip 图片分支
          │
          ├─ 读 URI → 解码 → 压缩（目标 ≤ 256 KB，JPEG q=80）
          ├─ 生成 hash 去重
          └─ cmd36 发布 type=TypeImageOri, binaryData=压缩字节
                     │
对端设备 ── cmd34 轮询 ──┴─► 收到 ImageOri
                              ├─ 循环上报的 ID 去重
                              ├─ 写临时文件 → 建 content:// URI（走本模块 FileProvider 或小布 provider）
                              └─ setPrimaryClip(ClipData.newUri(...))  ← 小布随即自动入库并展示
```

**关键取巧点**：对端不需要自己插数据库。只要把图片写成一个 `content://` URI 并塞进系统剪贴板，
小布的监听会自己完成"识别 → 复制 → 入库 → 展示"整条链路。模块只负责"把系统剪贴板喂对"。

### 5.2 需要新增的改动

| # | 位置 | 改动 | 复杂度 |
|---|---|---|---|
| 1 | `WxClient` | `publish()` 增加图片分支：`type=TypeImageOri`，写 `binaryData` | 低 |
| 2 | `WxClient` | 解析入站 `ClipboardMsg` 时读 `type`，区分文本/图片 | 低 |
| 3 | 新增 `ClipImageStore` | 临时文件写入、hash 去重、TTL 清理 | 中 |
| 4 | 新增 `ClipImageCodec` | URI → Bitmap → JPEG 压缩，尺寸/质量双限 | 中 |
| 5 | `WtClient` | 轮询分支增加图片处理；发布侧增加图片采集 | 中 |
| 6 | `MainHook` | `setPrimaryClip` hook 增加 `image/*` 分支（当前只管文本） | 中 |
| 7 | `SyncPage` | 增加"图片同步"开关与体积上限说明 | 低 |
| 8 | Manifest | 注册 FileProvider（供对端读取临时图片） | 低 |

### 5.3 ⚠ 架构性约束：模块无法注册 ContentProvider

实现到一半时踩到一个硬性障碍，必须记下来。

**问题**：LSPosed 模块的代码以 dex 形式注入宿主进程，**不能往宿主的 `AndroidManifest.xml` 里加组件**。
而 `ContentProvider` 是必须在 Manifest 声明的（系统在 PMS 里登记 authority 才能解析 `content://`）。
所以自写 `ClipImageProvider` 这条路在运行时注册会失败，`ClipData.newUri` 给对端后对方也解析不了。

当前代码里 `ClipImageProvider` 仍然保留（编译通过、逻辑正确），但**它只在"模块被单独安装且
Manifest 生效"的场景可用，注入场景下不可用**。

**三个备选方案**（按推荐度排序）：

| 方案 | 做法 | 优点 | 风险 |
|---|---|---|---|
| **A（推荐）** | 反射调用小布自己的入库入口 `com.oplus.keyboard.input.function.e.a(Entity)` | 不需要 provider，直接走小布原生链路；小布会自动复制文件、生成自家 URI、更新数据库、刷新面板 | 需要拿到 `e` 的实例（单例获取方式待确认）；Kotlin 协程方法调用可能需切线程 |
| B | 写文件到小布私有图片目录，再用 `com.oplus.keyboard.provider.clipboard` 的 URI 直接喂剪贴板 | 完全复用宿主 provider | 需要逆向出该 provider 的 path→file 映射规则；`clipboard.c`（provider 类）的方法体被 R8 处理过，暂未完整还原 |
| C | 写入 `MediaStore.Images`，用 `content://media/...` 喂剪贴板 | 标准 API，任何 app 能读 | Android 10+ 需 `WRITE_EXTERNAL_STORAGE`/`MANAGE_EXTERNAL_STORAGE`，小布未必有；会在相册留垃圾 |

**方案 A 的具体形态**（下一阶段首先验证）：

```java
Class<?> cmCls = cl.loadClass("com.oplus.keyboard.input.function.e");   // 小布剪贴板管理器
Class<?> enCls = cl.loadClass("com.oplus.keyboard.db.clipboard.a");     // 实体
// 实体构造：<init>(long id, ClipboardType, String text, String preview, Uri uri, long time, String[] mimes)
Object entity = enCls.getDeclaredConstructor(long.class, typeCls,
        String.class, String.class, Uri.class, long.class, String[].class)
        .newInstance(0L, imageType, null, null, localFileUri, System.currentTimeMillis(),
                     new String[]{"image/jpeg"});
// 入库
cmCls.getDeclaredMethod("a", enCls).invoke(cmInstance, entity);
```

关键未知项：`e` 的实例怎么拿（是单例还是每页一个）、`ClipboardType.IMAGE` 的反射取法、
以及 `a(Entity)` 是否必须在主线程。

**因此阶段一的验收标准调整为**：先确认方案 A 的反射链能跑通，再谈端到端。

### 5.3.1 真机实测结论（2026-10-26，Android 17）

三组实验，结论明确：

| 实验 | 现象 | 结论 |
|---|---|---|
| 查小布数据库 | 500 条记录**全部 `clipboard_type=1`（TEXT）**，`tab_clipboard_file` 为空 | 用户从未成功产生过图片剪贴板 |
| 模块写 `file://` + 显式 `image/png` MIME 到剪贴板 | 首次抛 `FileUriExposedException`；放开 StrictMode 后 `setPrimaryClip` 返回成功 | **写入本身可行** |
| 写完后查库 | **没有任何新增记录**（连同时写入的文本对照也没进） | **小布收不到** |

**对照实验的价值**：同一时刻写了一行 `wt_probe_<ts>` 纯文本和一张 PNG，两者都没入库；
而同一时段用户手动复制的一句话（`是不是正缘的话`）却正常入库为第 661 条。
说明监听逻辑是活的，**只是收不到模块写入的内容**。

**根因**：Android 13+ 的剪贴板读取限制。

```
dumpsys package com.oplus.keyboard | grep READ_CLIPBOARD   → 无结果
```

小布**没有 `READ_CLIPBOARD_IN_BACKGROUND` 权限**，测试时前台又是微信（`com.tencent.mm`）。
按 AOSP 规则，剪贴板只对「有焦点的应用 / 正在显示键盘的输入法 / 持有该权限的应用」开放。
小布在后台且键盘未显示时，`getPrimaryClip()` 拿到的是空值——它不会触发任何入库。

**这意味着"喂系统剪贴板"这条路不可靠**：能否生效取决于小布当前是否有焦点，
在真实使用场景（用户在别的 app 里复制）下几乎必然失效。

**所以方案 A 不再是"备选"，而是唯一可行解**：绕开剪贴板，直接调小布的入库入口。

### 5.3.3 方案 A 的落地形态：直接写库（已验证成功）

比"反射调 `e.a(Entity)`"更进一步：**小布的数据库就在同进程里，直接用 SQLite 写**，
连反射都不需要。

```java
SQLiteDatabase db = SQLiteDatabase.openDatabase(
        new File(ctx.getDataDir(), "databases/KeyBoardDatabase").getAbsolutePath(),
        null, SQLiteDatabase.OPEN_READWRITE);
db.execSQL("INSERT INTO tab_clipboard_data"
        + "(clipboard_type, clipboard_text, clipboard_preview_text,"
        + " clipboard_uri, clipboard_time, clipboard_mime_types)"
        + " VALUES(?, NULL, NULL, ?, ?, ?)",
        new Object[]{2, "file:///.../clip_xxx.jpg", System.currentTimeMillis(), "image/png"});
```

**关键参数全部由反编译确定**：

| 参数 | 值 | 来源 |
|---|---|---|
| `clipboard_type` | `2` | `ClipboardType` 的 `<clinit>`：`TEXT(value=1) / IMAGE(value=2) / VIDEO(value=3)` |
| `clipboard_time` | 毫秒时间戳 | 现有数据 `/1000` 后是合理日期 |
| `clipboard_mime_types` | `"image/png"` | 与现有 `text/plain` 记录同格式（单值不加括号） |
| `clipboard_uri` | `file://` 指向剪贴板图片目录 | 同进程可读 |

**实测结果**（2026-10-26 22:27）：

```
event=clipdb_ok uri=file:///data/user/0/com.oplus.keyboard/files/wtsync/clipimg/clip_83f40ea180b85078.jpg
event=testimg_db db=true
```

数据库确认：
```
类型分布: [(1, 500), (2, 1)]
IMAGE: (663, 2, 'file:///.../clip_83f40ea180b85078.jpg', 'image/png', 1793024866107)
```

**type=2 的记录确实落库了**。Room 的 Flow 会自己感知变化，不需要额外通知。

**待确认（唯一剩下的）**：`ClipBoardView` 拿到这条记录后能否正常渲染缩略图——
这需要在键盘弹出时手动点工具栏的剪贴板图标肉眼确认，自动化成本较高。

### 5.3.4 另一个有价值的实测结论

用 PC 端微信输入法做真实链路对照：

| 动作 | 结果 |
|---|---|
| PC 复制文本 `WT_PC_TEXT_PROBE_2026` | **手机小布入库为第 662 条** |
| PC 复制图片（DIB，192 KB / 259 KB 各一次） | **手机小布零新增** |

结论：**PC ↔ 手机的文本同步链路完全通畅**（微信输入法 → 系统剪贴板 → 小布入库），
但 **PC 端微信输入法并不会把图片同步到手机**（至少默认配置下不会）。
因此"手机端微信输入法收到图片后小布能否入库"这个问题，无法通过 PC 端构造，
只能等手机端真正产生一次图片剪贴板（浏览器长按图片 → 复制图片）才有机会观察。

### 5.3.2 其它实测数据

- **OPPO 相册没有"复制图片到剪贴板"**：长按 → 更多，菜单只有"移动到图集/复制到图集/重命名/转 PDF/设为壁纸"等，
  没有复制图片。这解释了为什么库里 0 条图片记录——系统层面就很少有机会产生图片剪贴板。
  可行的产生方式是浏览器长按图片 → 复制图片。
- **`com.oplus.keyboard.provider.clipboard` 未注册**：
  `content query` 报 `Could not find provider`，`dumpsys activity providers` 里也没有该 authority。
  方案 B（复用宿主 provider）因此不可行。
- **小布无媒体权限**：`READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` 都未声明，
  所以模块也无法主动构造 MediaStore URI（方案 C 也不通）。

### 5.4 分阶段实施

**阶段一（可独立验证）**：单机闭环
- 模块 hook 到图片 → 压缩 → 写自己 provider → 再塞回剪贴板 → 观察小布面板是否出现两条
- 目的：验证"喂 UR I→ 小布自动入库"这条假设

**阶段二**：协议扩展
- `cmd36` 发 `TypeImageOri`；对端解析并落盘
- 用两台设备或 PC 端微信输入法验证互操作

**阶段三**：打磨
- 去重、TTL 清理、体积上限、失败重试、开关

---

## 六、范围边界

**做**：
- 系统剪贴板中的**静态图片**（PNG / JPEG / WebP）
- Android 10 – 15（API 29 – 35）
- 单张图片，压缩后 ≤ 256 KB
- 走 `TypeImageOri` 内联传输

**不做**：
- **视频**（`ClipboardType.VIDEO`）——微信侧虽存在 `TypeImageCos`，但没有上传能力
- **COS 路径**（`TypeImageCos`）——缺上传凭证，与 D7 同源阻塞
- 多图 / 图文混排（`ClipData.itemCount > 1`，仅取第 0 项，与两侧原生行为一致）
- 动图（GIF 会被压成静态 JPEG）
- 双向以外的历史补拉（只同步"当前剪贴板"，不做图片历史库同步）

---

## 七、风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| **URI 权限**（Android 10+） | 对端 `ContentResolver` 打不开图片 | 用本模块 FileProvider + `FLAG_GRANT_READ_URI_PERMISSION`；或复用小布 provider（需验证是否 exported） |
| **大图 OOM** | 解码 4K 图可能 60+ MB | 先用 `BitmapFactory.Options.inJustDecodeBounds` 读尺寸，按 `inSampleSize` 降采样后再解码 |
| **协议字段猜测错误** | 发出去对方不认 | 阶段一先只做本地闭环；阶段二抓 PC 端微信输入法的真实报文对字段 |
| **4.0.0 新增字段缺失** | 对方可能因 `expiration`/`extraInfo` 缺失而拒收 | 先不带，若失败再逐个补；这两字段在旧版不存在，服务端应有默认值 |
| **剪贴板回环** | A 发 → B 收 → B 又发，无限循环 | 复用现有三层防护：写入打标 `cx.synced` + `fromUin` 比对 + 内容 hash 水位 |
| **SELinux / 沙箱** | 写临时文件被拒 | 只在 `files/wtsync/` 下操作（模块自有目录，已验证可写） |
| **压缩后仍有损** | 用户复制截图，同步后清晰度下降 | 界面上明确提示"图片会压缩后同步"；提供原图开关（超限则放弃同步而非强压） |

---

## 八、兼容性处理

| 场景 | 处理 |
|---|---|
| Android 9 及以下 | `minSdk 28` 已覆盖；`ClipData.newUri` 行为一致，无需特判 |
| Android 10+ 分区存储 | 不碰 `MediaStore`，只写私有目录 + FileProvider，规避 scoped storage |
| Android 12+ 剪贴板提示 | 系统会弹"已复制"toast，写入端无法抑制，属正常行为 |
| Android 13+ `READ_MEDIA_IMAGES` | **不需要**——图片来自 `ClipData.Item.getUri()` 的临时授权，非媒体库直读 |
| 小布版本差异 | 类型判定依赖 `ClipboardType`，若后续版本枚举变化，模块只在"喂剪贴板"层介入，不依赖其内部类 |
| 微信输入法版本差异 | `ClipboardMsg` 字段向后兼容（protobuf），新增字段缺失时按默认值处理 |
| 设备无微信输入法 | 不受影响——模块自带协议实现，不依赖对端装微信 |

---

## 九、待确认项（需要设备实测）

1. `TypeImageOri` 的图片字节确实放在 `binaryData`（f6），而非 `data`（f3）
2. 小布的 `com.oplus.keyboard.provider.clipboard` 是否 `exported`，能否跨应用读取
3. 微信 4.0.0 对 `expiration` / `extraInfo` 缺失的容忍度
4. 单条 `cmd36` 的体积上限（文本实测可到 256 KB，图片未知）

以上四项无法靠静态分析确定，需要在阶段二用真机 + PC 端微信输入法交叉验证。

---

## 十、附：分析工具

本次分析产出的可复用脚本（`tools/` 目录）：

| 脚本 | 用途 |
|---|---|
| `dexstr.py` | 解析 dex 字符串表，按关键字过滤类名/常量 |
| `dexcls.py` | 导出指定类的字段、方法列表及方法字节码 |

两者的关键经验：

- 主类被 R8 改名时，可**从内部类的 `this$0` 字段类型反查**真实类名
  （本次即由此把 `ClipboardManager` 定位到 `input.function.e`）
- 匹配类名必须**精确全名**，模糊匹配会先命中 `$xxx$1` 这类内部类
- androguard 会刷 loguru DEBUG 日志，需先 `logger.remove()`
