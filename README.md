# WtSync · 微信输入法 → 小布输入法 同步模块

![Android](https://img.shields.io/badge/Android-9.0%2B-3DDC84?logo=android&logoColor=white)
![LSPosed](https://img.shields.io/badge/LSPosed-libxposed%20API%20102-1E88E5)
![Version](https://img.shields.io/badge/version-3.18.8-2E7D32)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)

<p align="center">
  <img src="docs/images/icon.png" width="120" alt="WtSync">
</p>

<p align="center">
  <b>让 OPPO / 一加平板上被"阉割"的小布输入法，接入微信输入法的跨设备同步。</b><br>
  LSPosed 模块 · 作用域仅 <code>com.oplus.keyboard</code> · 不注入微信、不读取微信客户端任何文件
</p>

---

## 它是什么

微信输入法（WeType）的跨设备同步在 PC 与手机之间体验很好，但平板上的第三方输入法拿不到这份能力。
本项目是一个 LSPosed 模块，注入 **小布输入法（`com.oplus.keyboard`）** 进程，在宿主原生设置页里挂上
「跨设备同步」入口，让平板**以正常设备成员的身份加入微信输入法的设备组**，直接收发同步数据：

| 能力 | 方向 | 状态 |
|---|---|---|
| 剪贴板 · 纯文本 | PC ⇄ 手机 | ✅ 双向 |
| 剪贴板 · 图片 | PC ⇄ 手机 | ✅ 双向（COS 下载解密 / 上传发布） |
| 常用语（热词） | PC ⇄ 手机 | ✅ 双向 |
| 个人词库 | 微信 → 小布 | ✅ 单向增量 |
| 设备组关联 | 匹配码（cmd30 / 31 / 32） | ✅ |

模块自己实现了微信输入法的接入协议（ECDH 握手 + protobuf + 签名），
**协议层不依赖微信客户端，也不 hook 微信进程**。

## 界面

<table>
<tr>
<td width="50%"><img src="docs/images/user-dict.png" alt="个人词库"></td>
<td>

**个人词库页**（词库设置 → 个人词库）

- 顶部统计：`中文 9888 · 英文 128`
- 按词条或拼音实时搜索，按使用次数排序
- 点词条可改词 / 改拼音 / 改权重，或软删除
- 右上角「新增」，配色跟随宿主深浅色主题

编辑走宿主自己的 API：`userDictSyncExport` → 改 JSON → `userDictSyncImport` → `restartForVocabulary()`。

</td>
</tr>
</table>

## 工作原理

```
PC 微信输入法  ⇄  wetype.weixin.qq.com  ⇄  小布输入法进程 (com.oplus.keyboard)
                    /api_v2                  └─ WtSync 模块
  cmd36 发布 / cmd34 拉取                      WtClient      轮询 · 收发 · 去重
                                               XiaobuInject  系统剪贴板读写
                                               XiaobuDictSync 词库合并导入
```

三条关键链路：

1. **入组**：ECDH(secp128r1) 交换 → AES-256-ECB 包裹请求 → `Kb-Sign` 签名 → 拿到 `server_key` 与
   `uin_token`，之后所有请求走同一条已签名的 `/api_v2` 通道。
2. **剪贴板**：本地 `ClipboardManager.setPrimaryClip` 被 hook → 内容有变即 `cmd36` 发布；
   对端更新则 `cmd34` 带回 `ClipboardMsg{version, type, data, from_uin}` → 写回系统剪贴板。
3. **词库**：`cmd42` 按 `msgId` 游标增量拉取 → 解析 `Entry` → 合并进宿主词库 JSON → 引擎重启。

细节见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) 与协议表 [`docs/PROTOCOL.md`](docs/PROTOCOL.md)。

## 兼容性

| 项 | 要求 |
|---|---|
| Android | 9.0+（`minSdk 28`），实测 ColorOS 16 / Android 16 |
| 框架 | LSPosed 2.1.x（`libxposed` API 102），需 Zygisk / KernelSU 环境 |
| 宿主 | 小布输入法 `com.oplus.keyboard`（实测 1.8.35.17-mkt） |
| 构建 | JDK 17+，Android SDK **Platform 37**（见下方构建说明） |

> 只在 ColorOS 16 的一加 13 / OPPO 平板上做过真机验证。其他机型理论上可用，
> 但宿主设置页的类名与资源 id 随版本变化，菜单注入可能失效 —— 这类问题优先看日志里的
> `settings_hook_*` 事件。

## 安装

1. 设备已 Root 并装好 **LSPosed 2.1.x**（Zygisk 或 KernelSU 均可）。
2. 安装 `app-release.apk`。
3. 打开 LSPosed 管理器 → 模块 → 启用 **WtSync** → **作用域只勾 `com.oplus.keyboard`**。
4. 强行停止小布输入法（或在 LSPosed 里点「重启应用」），下次弹出键盘时模块生效。
5. 桌面会出现「微信同步·小布版」图标，作为备用配置入口。

## 首次配置

模块需要三个值，都来自**你已经在用的微信输入法账号**（模块不做登录、不代注册）：

| 字段 | 说明 |
|---|---|
| `UIN` | 账号数字 id |
| `Group ID` | 设备组 id（PC 与手机已在同一组时可直接取用） |
| `User Token` | 该账号的登录凭证 |

填进「微信同步·小布版」→ 保存并下发到小布输入法。凭证写入
`/data/data/com.oplus.keyboard/files/wtsync/`（宿主私有目录），**不上传任何第三方服务器**。

或者用**匹配码**入组：PC 端微信输入法生成 6 位匹配码 → 模块设置页输入 → 提交后由服务端下发
`SyncBindSuccess` 完成入组（协议对齐官方的 cmd30/31/32）。

小布输入法里「设置 → 跨设备同步」可以随时查看：

- 剪贴板同步 / 常用语同步开关（对应服务端功能位）
- 同步个人词库开关 + 立即同步
- 设备组成员列表、本机身份
- 关联码（本机出码 / 输入对端码）

## 构建

```bash
git clone <your-repo-url> WtSync && cd WtSync

# 需要 JDK 17+；AGP 8.7.3 + Gradle 8.13
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/AndroidSdk

./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

两个必须注意的点：

1. **`compileSdk` 必须是 37**。`libxposed:api:102.0.0` 的 AAR 清单声明
   `minCompiledSdkVersion=37`，用 35/36 会直接编译失败。
2. **签名**。仓库默认用 debug 签名（`assembleRelease` 可直接安装）。要换成自己的签名，
   在仓库根目录放 `keystore.properties`（已被 `.gitignore` 排除）：

   ```properties
   storeFile=release.jks
   storePassword=******
   keyAlias=wtsync
   keyPassword=******
   ```

更多（SDK 37 安装、常见报错、真机部署）见 [`docs/BUILD.md`](docs/BUILD.md)。

## 目录结构

```
WtSync/
├── app/
│   ├── build.gradle                     # 版本号 / 签名 / compileSdk 37
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/cxhuyo/wtsync/      # 20 个源文件，见下表
│       └── resources/META-INF/xposed/   # module.prop / scope.list / java_init.list
├── docs/
│   ├── ARCHITECTURE.md                  # 模块架构与数据流
│   ├── PROTOCOL.md                      # 微信输入法子命令与字段表
│   ├── BUILD.md                         # 构建、签名、部署
│   ├── TROUBLESHOOTING.md               # 息屏不同步 / 面板缺行 / 回声 等
│   ├── clipboard-image-sync.md          # 图片剪贴板专题（逆向分析）
│   └── DEVLOG.md                        # 逐版本开发日志（含踩坑记录）
├── tools/                               # 辅助脚本（图标生成、dex 静态分析）
├── gradle/wrapper/                      # Gradle 8.13 wrapper
├── build.gradle · settings.gradle · gradle.properties
└── LICENSE                              # GPL-3.0
```

### 源码职责

| 文件 | 职责 |
|---|---|
| `MainHook` | 模块入口：`onModuleLoaded` / `onPackageReady`，安装 hook、启动引擎 |
| `XiaobuSettingsHook` | 宿主设置页注入「跨设备同步」与「个人词库」入口 |
| `SyncPage` | 同步页 UI（开关、设备列表、关联码），复用宿主 COUI 组件 |
| `UserDictPage` | 个人词库全屏列表页（搜索、编辑、新增、软删除） |
| `SettingsActivity` | 模块自身配置页（UIN / Group / Token / 匹配码） |
| `WtClient` | 同步引擎：轮询、收发、去重与回声抑制、词库与常用语调度 |
| `WxClient` | 协议客户端：握手、子命令、protobuf 编解码 |
| `WtCrypto` | 纯 Java secp128r1 ECDH + AES-256-ECB + SHA256 |
| `HttpChannel` / `DnsUdp` / `Snappy` | 极简 HTTPS、DNS、Snappy 解压（零第三方运行时依赖） |
| `XiaobuInject` | 系统剪贴板读写，带防回环标记与幂等闸门 |
| `XiaobuClipDb` | 宿主剪贴板历史表的补录与裁剪（直接 SQLite，仅 INSERT/DELETE） |
| `XiaobuDictSync` / `WxDictReader` | 微信词条解析、与小布词库 JSON 的合并导入 |
| `XiaobuPhraseStore` | 常用语读写（宿主 API 封装） |
| `ClipImageCodec` / `ClipImageStore` | 图片压缩、AES-CTR 加解密、MediaStore 托管 |
| `MainHookLogger` | 统一日志（logcat + 落盘） |
| `DictDiag` | 诊断命令分发（`clipdb|stat`、`catchup` 等，需手动开启） |

## 已知限制

- **首次历史词库拿不到**：服务端增量窗口有限，旧 `msgId` 会被拒；全量快照需要
  `ApplyCosTokenReqV2` 的预签名 URL，该子命令尚未定位。日常增量同步不受影响。
- **小布 → 微信 词库方向未实现**：宿主只有 `resetUserDict`（清空），写入需要 COS 上传凭证。
- 超过 256 KiB 的文本只走内联，不走 COS 上传。
- 输入法进程被系统冻结时（键盘收起 + 息屏），写剪贴板会被 `WRITE_CLIPBOARD` 的
  foreground 语义拒绝；模块用「待写队列 + 键盘亮起补写」兜底，极端情况下可能延迟到下次弹键盘。
- CDN 偶发短时限流（约 1 分钟自愈），已做 4 IP 故障转移与失败重握手。

## 数据与隐私

- 作用域只有 `com.oplus.keyboard`，**不注入微信、不读取微信客户端的数据库或文件**。
- 凭证与运行时状态全部落在宿主私有目录 `files/wtsync/`，仅本机可读。
- 仓库内**不含**任何真实账号、token、设备组 id、内网地址；示例一律用占位符。
- 日志默认写到 `files/wtsync/module.log`，可在设置页关闭诊断后不再产生。

## 参与贡献

见 [`CONTRIBUTING.md`](CONTRIBUTING.md)。提 issue 时请附上：

```
机型 / 系统版本 / 小布输入法版本 / LSPosed 版本
module.log 中相关片段（自行脱敏 uin、设备名、token）
```

## 许可

[GPL-3.0](LICENSE)。本项目与腾讯、OPPO 均无关联，为个人逆向学习与自用工具，
按原样提供、不含任何担保。
