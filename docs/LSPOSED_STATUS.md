# 模块状态检测与作用域申请

> 适用版本：WetSync 3.20.0+（libxposed API 102）
> 相关源码：`app/src/main/java/com/cxhuyo/wetsync/FrameworkStatus.java`、`WetSyncApp.java`、`StatusActivity.java`

## 1. 要解决的问题

LSPosed 模块由两部分组成：

- **注入侧**：`MainHook` 跑在宿主（`com.oplus.keyboard`）进程里，只有它知道「模块被加载了」；
- **模块 App**：`StatusActivity` 跑在模块自己的进程里，是一个**普通应用**，进程里没有 Xposed 环境，
  也没有任何系统 API 能直接回答「本模块是否已启用 / 作用域勾了谁」。

早期做法是让注入侧写状态文件、模块 App 去读，但两边是不同 uid，读不到对方的私有目录；
用 `SharedPreferences` 共享同样受沙箱限制。最后剩下的可靠通道只有两条：

1. **libxposed service**（首选）：管理器把 binder 递进模块 App，模块直接查询框架状态；
2. **显式广播探活**（兜底）：模块 App 向宿主发包，注入侧回包，证明「注入确实发生了」。

## 2. libxposed service 是怎么工作的

### 2.1 模块侧声明 Provider

`io.github.libxposed:service:102.0.0` 这个 AAR 的清单里带了一个空壳 Provider：

```xml
<provider
    android:name="io.github.libxposed.service.XposedProvider"
    android:authorities="${applicationId}.XposedService"
    android:exported="true" />
```

合并进模块 APK 后，authority 就是 `com.cxhuyo.wetsync.XposedService`。

### 2.2 管理器把 binder 递进来

Provider 的 `call()` 只认一个方法：

```java
if (method.equals("SendBinder") && extras != null) {
    IBinder binder = extras.getBinder("binder");
    if (binder != null) XposedServiceHelper.onBinderReceived(binder);
}
```

也就是说：**由管理器主动调用模块的 Provider**，把 `IXposedService` 的 binder 塞进 Bundle。
模块侧不能反向去「拉」，只能提前 `registerListener` 等它来。

### 2.3 模块侧拿到 XposedService

```java
XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
    @Override public void onServiceBind(XposedService service) { /* 框架在线 */ }
    @Override public void onServiceDied(XposedService service) { /* 管理器断开 */ }
});
```

`XposedService` 提供的能力（API 102）：

| 方法 | 用途 |
|---|---|
| `getApiVersion()` | 框架服务 API，`>= 101` 才具备作用域查询能力 |
| `getFrameworkName()` / `getFrameworkVersion()` / `getFrameworkVersionCode()` | 框架名称与版本 |
| `getScope()` | 本模块被授权的作用域列表 |
| `getRunningTargets()` | 当前真正被注入的进程（进程名 / pid / uid / 状态） |
| `requestScope(List<String>, OnScopeEventListener)` | 申请作用域，管理器弹「作用域请求」通知 |
| `removeScope(List<String>)` | 取消作用域 |

## 3. LuckyTool 的检测方式（反编译结论）

参考项目：[`luckyzyx/LuckyTool`](https://github.com/luckyzyx/LuckyTool)（设备上安装的 1.x 版本，
`com.luckyzyx.luckytool`，R8 混淆后类名为 `m72` / `jd0` / `i45` / `e45`）。

它的状态检测可以概括为四步：

1. **注册监听**：`m72.a()` 调用 `XposedServiceHelper.registerListener(new jd0(...))`，全进程只注册一次；
2. **保存服务**：`jd0.k(e45)` 在 `onServiceBind` 里以 **binder 为 key** 存进 `IdentityHashMap`，
   避免重复绑定；随后在主线程回调所有订阅者；
3. **判定在线**：

   ```java
   // m72.b()
   e45 s = m72.m959();               // 取 IdentityHashMap 里的第一个 service
   return s != null && s.m439() >= 101;   // m439() = getApiVersion()
   ```

   即 **拿到 service 且 API ≥ 101** 才算「框架在线」，而不是看包名、看进程、读文件；

4. **读作用域**：`f55.m510()` 直接对 `IXposedService` 发 `transact(11)` 拿 `getScope()`，
   并用同样的 `m72.b()` 做前置判断，未激活时返回 null 并打日志
   `Activate the module in the hook framework to use ...`。

结论：**LuckyTool 不打开管理器、不读 root 文件、不猜包名**，只依赖 libxposed service 的 binder。
WetSync 采用同一套判定。

## 4. WetSync 的实现

### 4.1 单例：`FrameworkStatus`

```
Application.onCreate
  └─ FrameworkStatus.init()
       └─ XposedServiceHelper.registerListener(...)
            ├─ onServiceBind  → service = s; refresh(); notifyListeners()
            └─ onServiceDied  → service = null; apiVersion = -1; notifyListeners()

Activity
  ├─ addListener(this)          // 状态变化时回调
  ├─ refreshFramework()         // 后台线程 refresh()：api/fw/scope/runningTargets
  └─ requestScope(...)          // 走 requestScope()，不拉起管理器
```

要点：

- **尽早注册**：放在 `Application`，而不是 `Activity.onCreate`。binder 是异步递过来的，
  晚注册会让首屏一直停在「检测中」。
- **binder 调用不碰主线程**：`getScope()` / `getRunningTargets()` 都是 IPC，
  管理器卡住时放主线程会 ANR，统一丢到 `WetSyncFw` 线程。
- **作用域是粘性的**：管理器在授权后可能回收 binder（`onServiceDied`），
  这时不能把「已授权」清成「未授权」，否则页面会从 `已激活` 跳回 `未检测到`。
  因此 `scopeGranted` 与 `scopeCheckedAt` 独立保存，只在成功读取或授权回调时更新。

### 4.2 状态机

| service | api | scope 含宿主 | 宿主进程在跑 | 胶囊文案 |
|---|---|---|---|---|
| 有 | ≥101 | 是 | 是 | `已激活`（绿） |
| 有 | ≥101 | 是 | 否 | `已授权`（蓝） |
| 有 | ≥101 | 否 | — | `未授权`（红） |
| 有 | <101 | — | — | `框架版本过低`（红） |
| 无 | — | — | 是（广播回包） | `已注入`（绿） |
| 无 | — | —（本进程从未连上） | 否 | `未检测到`（红） |

「宿主进程在跑」有两个来源，任一为真即可：

- `getRunningTargets()` 里出现 `com.oplus.keyboard`（或它的子进程）；
- 广播探活：向宿主发 `com.cxhuyo.wetsync.PROBE`，注入侧的 `MainHook` 回
  `PROBE_BACK`（附带 `api` / `fw` / `engine`），5 秒内收到即视为活着。

### 4.3 作用域申请时序

```
StatusActivity           FrameworkStatus           管理器（LSPosed）
     │  requestScope()        │                        │
     │───────────────────────>│  service.requestScope  │
     │                        │───────────────────────>│  写 scope 表
     │                        │                        │  发「作用域请求」通知
     │                        │<───────────────────────│  onScopeRequestApproved
     │<───────────────────────│  scopeGranted = true   │
     │  toast + render        │                        │
```

管理器不弹出界面，只发一条通知（通知渠道 `lsposed_module_scope`），
用户点通知里的「允许」即完成授权。**模块不主动拉起管理器 Activity**。

首次打开且确实未授权时，页面会自动发一次申请（`maybeAutoRequestScope()`，
每进程只发一次），之后由用户点「申请作用域」手动重发。

页面不做「刷新检测」按钮：`onResume` 启动 5 秒一次的 `tick()`（`refreshFramework()` +
广播探活），`onPause` 停掉，状态变化通过 `FrameworkStatus.Listener` 回调即时渲染。
这与 LuckyTool 的做法一致 —— 状态是订阅出来的，不是点出来的。

### 4.4 一个坑：failed 不等于没授权

实测 LSPosed 2.2.1：作用域**已经存在**时再调 `requestScope()`，管理器仍会回调
`onScopeRequestFailed`（例如「已有待处理请求」/「已授权」），而 scope 表其实是好的。

所以失败回调不能直接当结论，必须复核：

```java
onScopeRequestFailed(msg) -> 等 400ms -> getScope().contains(host) ?
        是 -> 视为授权成功（onApproved）
        否 -> 才算失败（onFailed，toast 里带 msg）
```

另外，页面在 `scopeGranted == true` 时根本不发请求，只提示「作用域已授权，无需重复申请」，
避免管理器每次都推一条「作用域请求」通知。

### 4.5 降级路径

| 情况 | 表现 | 处理 |
|---|---|---|
| 管理器从未运行 / 未推送 binder | `未检测到` | 提示「确认模块已启用后重开本应用」，保留广播探活 |
| 管理器 API < 101 | `框架版本过低` | 提示升级 LSPosed 2.1.x |
| 已授权但键盘没起来 | `已授权` | 提示弹出一次键盘，页面自动更新 |
| 广播探活回包 | `已注入` | 说明注入成功，但读不到作用域（service 不可用） |

## 5. 与「打开 LSPosed」的区别

旧实现里「申请作用域」会 `startActivity` 拉起管理器（还要猜包名 / Activity），
问题有三个：管理器包名因 fork 而异、Activity 可能未导出、跳过去还要用户自己找模块。

现在：

- 申请走 `requestScope()`，管理器自己决定用通知还是对话框；
- 不查询、不启动任何管理器 Activity；
- 页面只保留 `me.yun.lspilot` 等包名用于**版本信息展示**，不参与跳转。

## 6. 验证方法

```bash
# 1) 模块进程是否拿到 binder（logcat）
adb logcat -s WetSync | grep -E "app_fw_|app_scope_"
#   app_fw_listener_registered  注册成功
#   app_scope_req_fail          申请失败（会打印异常）

# 2) 管理器是否写了作用域（框架日志）
adb shell su -c 'grep wetsync /data/adb/lspd/log/verbose_*.log | tail'
#   [com.oplus.keyboard] scope [com.oplus.keyboard] for com.cxhuyo.wetsync in user 0

# 3) 注入是否真的发生（框架日志）
adb shell su -c 'grep -E "wetsync.*(module_loaded|package_ready)" /data/adb/lspd/log/verbose_*.log | tail'

# 4) 广播探活（不依赖管理器）
adb shell am broadcast -a com.cxhuyo.wetsync.PROBE -p com.oplus.keyboard
```

## 7. 已知边界

- **binder 由管理器推送**，模块无法主动索取。管理器首次运行（安装/启用模块时必然发生）
  之后 binder 才会到达；纯命令行安装、从未打开过管理器的设备上，页面只能显示
  `未连接框架`，此时广播探活仍可判断注入是否成功。
- `getRunningTargets()` 只反映**调用瞬间**的注入进程；键盘进程被系统回收后会变空。
- 作用域授权后需要**强行停止宿主**才会重新加载模块，页面无法代替这一步。
- 所有结论来自 libxposed service 的公开 API，不读取 `/data/adb/lspd` 下的任何文件，
  也不需要 root。

## 8. 参考

- libxposed service API：`io.github.libxposed:service:102.0.0`（AAR 内 `XposedProvider` / `XposedServiceHelper` / `XposedService`）
- LuckyTool：`com.luckyzyx.luckytool` 1.x，`m72`（状态单例）、`jd0`（监听器）、
  `i45`（XposedServiceHelper 的混淆副本）、`f55`（getScope）
- 本仓库：`FrameworkStatus.java`、`StatusActivity.java`、`WetSyncApp.java`
