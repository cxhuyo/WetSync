# 贡献指南

## 提 issue 前

先按 [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) 自查一遍，并准备好这些信息：

```
机型 / 系统版本（如 ColorOS 17 / Android 17）
小布输入法版本（设置 → 关于）
LSPosed 版本 / libxposed API
WetSync 版本（LSPosed 管理器里显示）
复现步骤（PC 端操作 + 手机端操作，尽量精确到"复制了什么、在第几秒"）
module.log 相关片段
```

日志抓取：

```bash
adb shell su -c 'cp /data/data/com.oplus.keyboard/files/wtsync/module.log /sdcard/m.log'
adb pull /sdcard/m.log
```

**提交前请自行脱敏**：`uin`、`token`、设备组 id、设备名、内网地址都不要贴出来。

## 开发环境

| 依赖 | 版本 |
|---|---|
| JDK | 17 / 21 |
| Android SDK Platform | 37（必须，见 [docs/BUILD.md](docs/BUILD.md)） |
| Gradle | 8.13（仓库自带 wrapper） |

```bash
./gradlew :app:assembleRelease
```

## 代码约定

1. **不引入任何 androidx 运行期依赖**。宿主的 androidx 被 R8 重名化，模块 ClassLoader 只有自身 dex，
   按原名加载必然 `NoClassDefFoundError`。需要宿主类时用 `classLoader` 反射。
2. **主线程不做重活**。图片解码、网络、SQLite 一律甩到后台线程。
3. **hook 里不抛异常**。任何 `setPrimaryClip` 相关的 hook 都要 `try/catch` 兜底，
   异常逃出去会打断输入法自身的流程（表现为"输入法偶发卡死"）。
4. **新增写剪贴板的路径必须走 `XiaobuInject`**，它会处理幂等闸门与防回环标记。
5. **改去重逻辑前先读** [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) 第 4 节，
   四道闸少一道就会出现回声循环。
6. 缩进 4 空格，文件 UTF-8 + LF，注释用中文、说明"为什么"而不是"做了什么"。

## 提交 PR

- 一个 PR 只做一件事，附上真机验证记录（日志片段或截图）。
- 涉及协议字段的改动，请在 PR 描述里写清楚是哪个子命令、哪个字段、如何验证的。
- 版本号改动请同时更新 `app/build.gradle` 与 `module.prop`。
- 不要提交 `keystore.properties`、`*.jks`、`local.properties`、`build/` 目录。

提交信息建议：

```
fix(clipboard): 修正 type=2 图片引用未处理导致丢图

- 新增 cosRefToJson()，把纯文本直链收敛到 type=6 的 JSON 形态
- 修复 lastRxAt 未置位导致收到图片后立刻回传旧文本
```

## 不符合预期的改动

以下方向经实测不可行或收益过低，PR 前请先开 issue 讨论：

- 面板内图片缩略图渲染（宿主 1.8.35.x 无此能力）
- 通过 `Kernel.updateUserWord` 做单条删除（各参数组合均返回 false，只能软删除）
- 用 `am start-service` 拉起宿主进程（无 launcher，会报 `app is in background uid null`）
