# tools

辅助脚本，都不参与 APK 构建。

| 脚本 | 用途 |
|---|---|
| `make_icon.py` | 生成模块图标（微信绿 + 小布蓝 + 双向箭头），输出 1024/512/192 三档 PNG |
| `fix_appops.sh` | 一键设置/自检剪贴板相关的 appops（`WRITE_CLIPBOARD` / `READ_CLIPBOARD`），支持多用户与克隆空间 |
| `dex_strings.py` | 从 APK 里提取字符串（自写 dex 解析，不依赖 androguard） |
| `dex_classes.py` | 列出 APK 里的类名与继承关系，用于定位宿主混淆后的类 |

## fix_appops.sh

```bash
sh tools/fix_appops.sh                          # 默认设备，直接修
sh tools/fix_appops.sh --check <DEVICE>:<PORT>  # 只检查
WTSYNC_USERS="0 999" sh tools/fix_appops.sh     # 指定用户空间
```

设置页里也能看到当前 appops 状态；只有出现「系统剪贴板有内容、宿主面板没有」时才需要动它。

## dex_strings.py / dex_classes.py

```bash
python tools/dex_strings.py 小布输入法.apk | grep -i clipboard
python tools/dex_classes.py 小布输入法.apk | grep -i ClipboardManager
```

只依赖标准库，Android 的 multidex（`classes2.dex` …）会自动遍历。
