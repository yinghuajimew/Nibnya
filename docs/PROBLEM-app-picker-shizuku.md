# 樱喵NBT「按应用选择」在无 root（仅 Shizuku）设备上失效 —— 排查记录与外援请求

> 项目：樱喵NBT（Nibnya），Minecraft 基岩版存档编辑器，Android / Kotlin
> 包名：`yhjmew.nibnya`　版本：1.0.4 (5)
> 分支：`main`　相关提交：`8c04b39` → `6df9962` → `9d3e08c` → `3b3cd13` → (本次)
> 文档用途：把已经排除的和仍未解决的都列清楚，请有 Android 存储权限经验的人接手判断

---

## 一、现象

用户在 **Android 13、OPPO PEMM00、无 root、已安装并授权 Shizuku** 的设备上：

1. 点菜单「按应用选择…」
2. 期望：列出本机上有存档目录的应用，选中后进入该应用的存档列表
3. 实际：**列表为空**，或只弹一个 toast，存档列表始终出不来

**同一份 APK 在另一台有 root 的手机上完全正常**：能列出 3 个应用，每个都标「已确认有存档」。

日志与诊断截图（最新一轮）：

```
[21:18:13][INFO][ExecMode] selected=auto resolved=SHIZUKU
[21:18:13][INFO][Path] 内部路径在无 root 时不可读，改用外部路径:
                        /storage/emulated/0/Android/data/com.mojang.minecraftpe/files/games/com.mojang/minecraftWorlds/
[21:18:13][INFO][Path] 恢复上次路径:
                        /storage/emulated/0/Android/data/com.mojang.minecraftpe/files/games/com.mojang/minecraftWorlds/
```

诊断对话框：

```
运行模式: ROOT
checkShizukuAvailable: false
canReadRestrictedDirs: true
当前路径: /data/user/0/com.mojang.minecraftpe/games/com.mojang/minecraftWorlds/
当前路径可读: 需 root

--- 当前路径 ls ---
ls: /data/user/0/com.mojang.minecraftpe/games/com.mojang/minecraftWorlds/: Permission denied

--- 应用枚举 ls ---
/storage/emulated/0/Android/data/com.mojang.minecraftpe/files/games/com.mojang/minecraftWorlds
/storage/emulated/0/Android/data/com.mojang.minecraftput/files/games/com.mojang/minecraftWorlds

--- 解析出的包名 ---
2 个: [com.mojang.minecraftpe, com.mojang.minecraftput]
```

---

## 二、已经确认的事实（重要，请勿重复排查）

### 2.1 Shizuku 通道本身是好的

诊断里「应用枚举 ls」**成功返回了两条真实路径**，并正确解析出两个包名。
这说明：

- Shizuku 已连接、已授权给本应用
- `Shizuku.newProcess("sh", ...)` 可正常创建进程
- **shell 用户（uid 2000）在这台 ROM 上确实读得到 `/storage/emulated/0/Android/data/`**

→ 所以「Android 11+ 读不到 Android/data」这个常见结论**不适用于本机**。

### 2.2 坏的是「当前路径」，不是权限

诊断里「当前路径」是：

```
/data/user/0/com.mojang.minecraftpe/games/com.mojang/minecraftWorlds/
```

这是 **Minecraft 自己的应用私有目录**，只有 `uid=0` 能进。
Shizuku 以 shell（uid 2000）运行，`ls` 必然 `Permission denied`。
这是内核 DAC 限制，**任何权限声明、任何 SAF、任何 MANAGE_EXTERNAL_STORAGE 都绕不过**。

同一份数据的另一个入口是：

```
/storage/emulated/0/Android/data/com.mojang.minecraftpe/files/games/com.mojang/minecraftWorlds/
```

它下面 `games/com.mojang/minecraftWorlds` 的内容与前者完全一致。

### 2.3 「模式」与「实际能力」已经脱节

- 启动日志：`resolved=SHIZUKU`
- 诊断显示：`运行模式: ROOT`
- 同时日志里出现：`su 启动失败，降级到 Shizuku: Cannot run program "su": error=2, No such file or directory`

→ **这台机器根本没有 `su` 二进制**，但运行模式被标成了 `ROOT`。
`PrivilegedExec.detectRoot()` 用 `Runtime.exec("sh -c ...")` 尝试，本应返回 false，
但诊断里 `运行模式` 仍是 ROOT，说明有某条路径把 mode 直接 `setMode(Mode.ROOT)` 而未校验。

已定位到的直接来源：**路径菜单的「软件内部」分支**，无 root 时也会 `setMode(Mode.ROOT)`。
（该分支本应只在 `detectRoot()==true` 时出现。）

---

## 三、已修复的内容（逐条，含提交号）

| # | 问题 | 修法 | 提交 |
|---|------|------|------|
| 1 | `execProbeReadOnly` 把**空输出当成成功**：Shizuku 进程能启动但被 SELinux 拒绝时 stdout 为空，函数直接 `return ""`，后两条通道（强制 Shizuku、su）根本不会执行 | 只有拿到**非空**输出才算成功，否则继续试下一条；三条都空才返回 `null` | `6df9962` |
| 2 | 全项目 **19 处**用 `checkShizukuAvailable()`（按当前模式判断）来决定「能不能读受限目录」，而 `canProbeWithPrivilege()`（探测三条通道）给出相反答案 → 「列表说能读、扫描说不能」 | 统一为 `canReadRestrictedDirs()`，内部探三条通道并缓存 | `6df9962` |
| 3 | Kotlin 无三元运算符、块体函数缺 `return` 等编译错误 | 已改 | `0c2a50f` |
| 4 | 「按应用选择」在无 root 时会**永远为空**：`bulkHint` 为空时自检依赖用户默认包名，未设默认 → 自检不触发 → 全部判 `ABSENT` → 丢弃 | 三态 VERIFIED / UNKNOWN / ABSENT 贯穿扫描与 UI；无法验证时退回 UNKNOWN 而非丢弃 | `8c04b39`、`3b3cd13` |
| 5 | 路径校正只作用于 `restorePath` 一个入口，之后任何 `switchPath(内部路径)` 都会把坏路径塞回来 | 校正下沉到 `switchPath`（所有路径变更的唯一入口） | `3b3cd13` |
| 6 | 校正后没有回写 `prefs.last_path` → 下次启动又读出旧值、又校正一遍，表现为「改了又变回去」 | 校正后立即回写 | `3b3cd13` |
| 7 | `normalizePathForAccess` 判断「有 root 就不校正」时只看 `mode()`，而 mode 可能是假的 ROOT | 改为 `mode()==ROOT && detectRoot()` 双重实测 | `3b3cd13` |
| 8 | 菜单索引写死：`iInternal = 5`，但「软件内部」只在有 root 时才 add。无 root 时 `labels.size - 1 == 5`，于是 `iInternal == iByApp`，点「按应用选择」命中内部路径分支 | 改为构建 labels 时**同步记录每个动作的下标**（`idxAll/idxStandard/.../idxByApp`） | 本次 |
| 9 | 「软件内部」分支无条件 `setMode(ROOT)`，即使本机没有 su | 增加 `detectRoot(this)` 实测门槛，失败 toast 拦下 | `3b3cd13` |

---

## 四、仍然存在的疑点（需要外援判断的地方）

### 疑点 A：为什么「当前路径」会在 16 秒内从外部路径变回内部路径？

日志（同一次运行）：

```
21:18:13  内部路径在无 root 时不可读，改用外部路径: /storage/.../Android/data/...
21:18:13  恢复上次路径: /storage/.../Android/data/...
（诊断，若干秒后）
          当前路径: /data/user/0/com.mojang.minecraftpe/...
```

`restorePath()` 已校正并回写 prefs。理论上此后不应再出现内部路径。
**除非用户在这 16 秒内点了「软件内部」**（这就是第 8/9 条 bug 的现场）
—— 若如此，则已修复。但**尚无该轮修复后的真机验证**。

### 疑点 B：`resolved=SHIZUKU` 与诊断 `运行模式: ROOT` 的矛盾

启动时 `PrivilegedExec.resolveMode()` 在无 su 时应当返回 SHIZUKU。
诊断显示 ROOT，只可能来自后续某次 `setMode(Mode.ROOT)`。
已定位到菜单「软件内部」分支，但**是否还有其它调用点**未完全排除。
可 grep `setMode(` 检查：目前 6 处，其中 `MainActivity:1286`、`MainActivity:1593`（applyPackage 内部模式）可疑。

### 疑点 C：`checkShizukuAvailable()` 返回 false，但 Shizuku 实际可用

诊断里 `checkShizukuAvailable: false` 与「应用枚举 ls 成功」直接冲突。
说明 `checkShizukuAvailable()` 的实现（依赖 `useShizuku` 标志 / mode）与真实状态不符，
它已不再是可靠的判据。目前只是把调用方都换成了 `canReadRestrictedDirs()`，
但**没有从根上修 `checkShizukuAvailable()` 本身**。

### 疑点 D：`/data/user/0/...` 是否应该整个从「路径候选」里移除？

当前设计允许「软件内部」路径存在（仅供 root 用户）。但它一旦被写进 `last_path`，
在无 root 的设备上就会反复污染。是否应当：
- 完全不在无 root 时展示 / 不写入 `last_path`？
- 还是保留但每次读取时强制校正（当前做法）？

---

## 五、代码位置速查

| 文件 | 关键符号 |
|------|---------|
| `world/WorldViewModel.kt` | `scanWorlds`、`switchPath`、`restorePath`、`normalizePathForAccess`、`execProbeReadOnly`、`readAll`、`canReadRestrictedDirs`、`canProbeWithPrivilege`、`listPackagesWithWorlds`、`hasWorldsUnder`、`listWorldFolders`、`looksLikeWorld`、`fallbackExternalRoot`、`diagnosePackageScan`、`checkShizukuAvailable` |
| `util/PrivilegedExec.kt` | `exec`、`execRoot`、`execShizuku`、`execShizukuForced`、`detectRoot`、`detectShizuku`、`resolveMode`、`setMode`、`canFallbackToShizuku`、`validate` |
| `world/PackageResolver.kt` | `externalBase`、`internalBase`、`externalVariantBase`、`scan`、`packageOfPath`、`currentPackageName`、`looksLikeGame`、`checkByFile` |
| `world/PackagePrefs.kt` | `defaultPackage`、`history`、`useInternal`、`effectiveBase`、`attach` |
| `MainActivity.kt` | `showPathSelector`、`showAppPicker`、`showAppListDialog`、`applyPackage`、`applyPath`、`showWorldSelector`、`showWorldSelectorDialog`、`PATH_STANDARD`、`PATH_INTERNAL`、`DEFAULT_PACKAGE` |

设备上的路径常量：

```
PATH_STANDARD = /storage/emulated/0/Android/data/<pkg>/files/games/com.mojang/minecraftWorlds/
PATH_LEGACY   = /storage/emulated/0/games/com.mojang/minecraftWorlds/
PATH_INTERNAL = /data/user/0/<pkg>/games/com.mojang/minecraftWorlds/          ← 仅 root
VARIANT       = /storage/emulated/0/Android/data/<pkg>/files/minecraftWorlds/ ← 个别变体
```

---

## 六、请外援重点判断的问题

1. **Android 13 + Shizuku（uid 2000）读 `/storage/emulated/0/Android/data/<pkg>/` 的稳定性**
   本机诊断证明可以读。但 `ls` 成功 ≠ `open()/read()` 成功 —— POSIX 权限与 FUSE 的
   `sdcardfs`/`FuseDaemon` 行为不同。是否需要用 `Shizuku.newProcess` 走 **`cat` / `find`**
   而不是 `ls`？或者用 `Shizuku` 的 `UserService` 以更完整的身份执行？

2. **是否存在比「拼路径 + 探测」更可靠的入口**
   - `PackageManager.getApplicationInfo().dataDir` 拿到的是 `/data/user/0/<pkg>`，
     Shizuku 读不了；能否用 `ApplicationInfo.sourceDir` 之类的旁路？
   - `Context.createPackageContext()` / `ContextImpl` 反射能否拿到对方的
     `StorageManager` 或 `ExternalStorageProvider`？
   - `MediaStore` 明确不覆盖 `Android/data`（已排除）。
   - `DocumentsUI` 在 Android 11+ 屏蔽了 `Android/data` 入口（已排除）。

3. **「模式」状态机是否应当彻底重做**
   当前 `PrivilegedExec.Mode { ROOT, SHIZUKU, NORMAL }` 是单一全局状态，
   但实际设备可能同时满足多个条件，或「标了 ROOT 却不可用」。
   是否应改成**按命令实时选通道**（"尝试 ROOT → 失败则 Shizuku → 失败则普通"），
   而不是先定一个全局 mode？

4. **`checkShizukuAvailable()` 与真实状态不符的根因**
   它返回 false 而 Shizuku 明显可用。是否有 `useShizuku` 标志未同步？
   （`MainActivity:374` 有 `worldVM.setUseShizuku(resolvedMode != NORMAL)`，
   而诊断时 mode 已是 ROOT，此处可能被改过。）

---

## 七、复现步骤

1. 设备：Android 13，无 root，装 Shizuku 并授权给樱喵NBT
2. 安装 APK（debug 或 release 均可）
3. 打开应用 → 右下角「切换路径」→「按应用选择…」
4. 观察：应用列表是否出现；选中后存档列表是否出现
5. 若失败：点任意「未找到保存内容」对话框里的「诊断」，导出文本

**期望**：列出 `com.mojang.minecraftpe` 等应用，选中后看到 `minecraftWorlds` 下的存档。
**实际**：列表为空 / 只弹 toast。

---

## 八、相关日志与样本

- 崩溃日志：`crash-2026-10-22_18-36-12.log`（`GridPreviewView.applyDrag` `coerceIn` 空区间）
- 运行日志：`debug-2026-10-26_23-04-16.log`、`debug-2026-10-27_01-47-01.log`、
  `debug-2026-11-06_21-39-09.log`
- 存档样本：`C:\MinecraftRef\samples\ext\w1\db`
- 玩家 NBT 快照：`1_U5nJfMmEQ_20261026_230314.json`
- level.dat 快照：`1_U5nJfMmEQ_20261026_230321.json`

---

## 九、结论摘要（给接手的人一句话）

> Shizuku 通道可用且能列出 `Android/data` 下的存档根目录；
> 失效的原因是**当前路径被设成了只有 root 才能读的 `/data/user/0/<pkg>/...`**，
> 并且**「运行模式」被错误地标成 ROOT**（本机无 su），
> 导致后续所有扫描都走 su 并失败。
> 路径校正、模式实测、菜单索引三处已修，但缺少真机验证；
> 若仍未解决，优先怀疑「Shizuku 通道对具体文件操作的权限」与「模式状态机设计」。
