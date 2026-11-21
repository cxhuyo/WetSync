#!/bin/sh
# ============================================================================
# WtSync 剪贴板 appops 一键修复 / 自检
#
# 【电脑上跑】需要 adb + 设备已连接
#   sh tools/fix_appops.sh                              # 默认（唯一）设备
#   sh tools/fix_appops.sh <DEVICE>:<PORT>           # 指定设备
#   sh tools/fix_appops.sh --check <DEVICE>:<PORT>   # 只检查，不改
#
# 【手机上跑】MT 管理器 / Termux / adb shell 均可，需要 root（会调用 su）
#   sh /sdcard/Download/fix_appops.sh            # 修
#   sh /sdcard/Download/fix_appops.sh --check    # 只查
#   已经是 root（# 提示符）时直接执行，不再套 su
#
# 【用户范围】默认自动覆盖「机主 + 所有应用分身/隐私空间」：
#   用 pm list users 枚举全部用户，逐用户设置各自空间的 appops。
#   某个空间没装该应用会自动跳过（不会报错）。
#   想手工指定：WTSYNC_USERS="0 999" sh ...
#
# 环境变量：WTSYNC_IME / WTSYNC_WX / WTSYNC_USERS
#
# 什么时候要重跑：卸载重装输入法、清除输入法“应用数据”、恢复出厂/双清/刷机、
# 换新设备（每台各跑一次）、大版本 OTA 之后、新开了应用分身/隐私空间。
# ============================================================================

set -u

IME=${WTSYNC_IME:-com.oplus.keyboard}
WX=${WTSYNC_WX:-com.tencent.mm}

# ---------- 参数 ----------
ACTION=fix
if [ "${1:-}" = "--check" ]; then ACTION=check; shift; fi
ARG=${1:-}

# ---------- 环境判定：手机本机 vs 电脑 adb ----------
if [ -f /system/build.prop ] || [ -d /system/app ]; then
    MODE=local
else
    MODE=adb
fi

if [ "$MODE" = adb ]; then
    if ! command -v adb >/dev/null 2>&1; then
        echo "!! 找不到 adb。若你想在手机上直接跑，请在手机终端里执行本脚本（会自动走本地模式）。" >&2
        exit 3
    fi
    DEV=$ARG
    if [ -n "$DEV" ]; then A="adb -s $DEV"; else A="adb"; fi
    STATE=$($A get-state 2>&1 | tr -d '\r')
    if [ "$STATE" != "device" ]; then
        echo "!! 设备不可用：${DEV:-默认设备}（adb get-state = $STATE）" >&2
        echo "   先连上：adb connect <ip>:<port>；或用 adb devices 确认设备在列表里" >&2
        exit 4
    fi
    run() { $A shell "su -c '$1'" 2>&1; }
else
    # 本机模式：已是 root 就直接跑，否则找 su
    if [ "$(id -u 2>/dev/null)" = "0" ]; then
        SU=""
    else
        SU=""
        for c in su /system/bin/su /debug_ramdisk/su /data/adb/ksu/bin/su; do
            if command -v "$c" >/dev/null 2>&1 || [ -x "$c" ]; then
                SU="$c -c"
                break
            fi
        done
        if [ -z "$SU" ]; then
            echo "!! 找不到 su：这台设备没有 root，或 root 没授权给当前终端。" >&2
            exit 2
        fi
    fi
    run() {
        if [ -z "$SU" ]; then sh -c "$1" 2>&1; else $SU "$1" 2>&1; fi
    }
fi

# ---------- root 自检 ----------
if ! run "id" | grep -q "uid=0"; then
    echo "!! 拿不到 root。请在 KernelSU/Magisk 里给当前终端（或 MT 管理器）授权后重试。" >&2
    echo "   自检：su -c id  → 应输出 uid=0(root)" >&2
    exit 2
fi

# ---------- 用户列表：机主 + 所有分身/隐私空间 ----------
USERS_TXT=$(run "pm list users")
if [ -n "${WTSYNC_USERS:-}" ]; then
    UIDS=$WTSYNC_USERS
    USERS_TXT=""
else
    UIDS=$(printf '%s\n' "$USERS_TXT" | sed -n 's/.*UserInfo{\([0-9][0-9]*\):.*/\1/p' | tr '\n' ' ')
    [ -z "$UIDS" ] && UIDS=0
fi

if [ "$MODE" = adb ]; then
    echo "模式: adb -> ${ARG:-默认设备}   动作: $ACTION"
else
    echo "模式: 本机(su)   动作: $ACTION"
fi
echo "用户: $UIDS"
echo

# ---------- 执行 ----------
fail=0
skip=0
for u in $UIDS; do
    uname=$(printf '%s\n' "$USERS_TXT" | sed -n "s/.*UserInfo{$u:\([^:}]*\).*/\1/p")
    [ -z "$uname" ] && uname="user"
    echo "== user $u ($uname)"

    for pair in "$IME:WRITE_CLIPBOARD" "$IME:READ_CLIPBOARD" "$WX:READ_CLIPBOARD"; do
        pkg=${pair%%:*}
        op=${pair##*:}

        # 该用户空间没装这个应用就跳过（appops set 不会报错但也没意义）
        if [ -z "$(run "pm list packages --user $u $pkg" | grep "package:$pkg")" ]; then
            printf '   [ -  ] %-22s %-16s 该空间未安装，跳过\n' "$pkg" "$op"
            skip=$((skip + 1))
            continue
        fi

        if [ "$ACTION" = fix ]; then
            run "appops set --user $u $pkg $op allow" >/dev/null
        fi
        # appops get 可能同时打印 "Uid mode: OP: …" 与包级 "OP: …" 两行，
        # 只取以操作名开头的那一行（真正的包级模式）。
        raw=$(run "appops get --user $u $pkg $op")
        out=$(printf '%s\n' "$raw" | grep -E "^$op:" | head -n 1)
        [ -z "$out" ] && out=$(printf '%s\n' "$raw" | head -n 1)
        case "$out" in
            *allow*) mark="OK ";;
            "")      mark="?? "; fail=$((fail + 1));;
            *)       mark="BAD"; fail=$((fail + 1));;
        esac
        printf '   [%s] %-22s %-16s %s\n' "$mark" "$pkg" "$op" "$out"
    done
done

echo
if [ "$fail" -eq 0 ]; then
    echo "全部 allow（跳过 $skip 项：对应空间未安装该应用）。"
else
    echo "有 $fail 项不是 allow。排查："
    echo "  - 包名对不对：pm list packages | grep -i keyboard"
    echo "  - 该空间是否装了应用（上面标 [ -  ] 的就是没装）"
fi
echo
echo "各项含义："
echo "  $IME WRITE_CLIPBOARD  —— 输入法写系统剪贴板（PC 复制的内容进手机剪贴板）"
echo "  $IME READ_CLIPBOARD   —— 输入法读剪贴板（手机 → PC 方向）"
echo "  $WX  READ_CLIPBOARD   —— 微信读剪贴板（长按粘贴）"

[ "$fail" -eq 0 ] || exit 1
