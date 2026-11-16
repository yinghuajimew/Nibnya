# 「按应用选择」（包名识别）需求与待办

> 状态记录于 2026-10-27。**功能当前可用，暂不继续改**，等真正需要时再按本文档推进。
> 相关排查记录见 `PROBLEM-app-picker-shizuku.md`。

---

## 一、用户原始需求（逐条归档，勿改写语义）

> 沈梦瑶：
> 1. 我打算再多一个选择，可以更快并且减少输入
> 2. 但这里的落地就得去考虑了
> 3. 允许指定对应的包名，并且软件会试图去寻找，或者使用当前现有的固定路径，去寻找游戏存档
> 4. 可以直接去使用搜索或者在列表当中找到你修改过后的版本，或者他人修改过的优化版本
> 5. 而且也有一个选择该软件使用过的路径历史，以及也可以设置为本次或者永久为默认选项
> 6. 也允许清除掉以本次或者永久默认的选项回到原版包名
> 7. 搜索支持，模糊搜索，拼音搜索，包名搜索，列表会按照 a 到 z 去排序
> 8. 由于不可能会有人把一款游戏注册成系统软件，仅展示用户安装的列表内软件
> 9. 也支持按照最早安装，最晚安装的时间去寻找

拆成可验收条目：

| # | 需求 | 状态 |
|---|------|------|
| R1 | 菜单新增一个入口，比手动找路径更快、少输入 | ✅ 已完成 |
| R2 | 允许指定包名，软件自动去找存档；找不到就退回现有固定路径 | ⚠️ **部分** —— 只能在列表里点选，没有「手动输入包名」入口 |
| R3 | 能在列表里找到改过的版本 / 他人优化版（共存版） | ✅ 已完成（`looksLikeGame` + 三态验证） |
| R4 | 记录用过的路径历史 | ⚠️ **数据已存，UI 未接** —— `PackagePrefs.history()` 有数据，但界面上看不到 |
| R5 | 可设为「本次」或「永久」默认 | ⚠️ **只有永久** —— 「本次」语义缺失（关掉应用即失效） |
| R6 | 可清除默认，回到原版包名 | ✅ 已完成（长按 → 清除默认 → 回 `DEFAULT_PACKAGE`） |
| R7 | 搜索：模糊 / 拼音 / 包名；列表 A-Z 排序 | ⚠️ **拼音未实现**；模糊与包名已实现；A-Z 已实现 |
| R8 | 只展示用户安装的应用，不展示系统应用 | ⚠️ **未实现** —— 当前不过滤 `FLAG_SYSTEM` |
| R9 | 支持按最早 / 最晚安装时间查找 | ✅ 已完成（点排序行循环 A-Z → 安装↓ → 安装↑） |

---

## 二、待办（按优先级）

### P0 — R8 只展示用户安装的应用

用户明确说过「不可能会有人把一款游戏注册成系统软件」，
所以系统应用一律不该出现在列表里。

**改法**：`PackageResolver.scan()` 里，拿到 `packages` 后过滤：

```kotlin
val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
    .filter { info ->
        (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
        (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }
```

注意：`FLAG_UPDATED_SYSTEM_APP` 要保留（用户更新过的系统应用算用户应用）。
`com.mojang.minecraftpe` 自己不是系统应用，不受影响。

**位置**：`PackageResolver.kt` 约 107-111 行（`val packages = try { ... }`）

**验证**：root 机上点「按应用选择」，确认列表里没有 YouTube / Chrome / 设置之类。

---

### P1 — R7 拼音搜索

**现状**：`MainActivity.kt:1451-1454` 只做了 `label.lowercase().contains(kw)` 和
`packageName.lowercase().contains(kw)`。

**难点**：`android.icu.text.Transliterator` 需要 API 24+，而本项目 `minSdk 23`。

**可行方案**（择一）：

1. **`Transliterator` + 版本判断**（推荐）
   ```kotlin
   private fun pinyin(s: String): String =
       if (Build.VERSION.SDK_INT >= 24) {
           android.icu.text.Transliterator
               .getInstance("Han-Latin; Latin-ASCII; Lower")
               .transliterate(s)
       } else s
   ```
   API 23 上退化为原文匹配（不影响其他功能）。
   注意 `Han-Latin` 输出带声调符号，要配合 `Latin-ASCII` 去掉。

2. 内置拼音首字母表（几十 KB，覆盖常用汉字）。离线可控但体积大。

**顺带**：搜索时同时匹配「拼音全拼」和「首字母」（如「wdsj」→「我的世界」）。
首字母需要自己从全拼推导或查表。

**位置**：`MainActivity.kt` 的 `currentShown()`。

---

### P2 — R2 手动输入包名

**需求**：「允许指定对应的包名，并且软件会试图去寻找」。

**当前**：只能从扫描出来的列表里点，装机少或包名奇怪时没有入口。

**改法**：在应用列表对话框顶部（搜索框旁）加一个「手动输入」按钮 →
弹出 `EditText` → 用户填 `com.xxx.yyy` →
`applyPackage(pkg, ...)` → 扫不到就 toast 提示并退回固定路径。

**注意**：
- 输入要 trim、转小写、校验形如 `a.b.c`
- 填的包名不存在时不要崩，`PackagePrefs.rememberHistory` 照记（下次可能就有了）
- 路径拼错的失败体验要友好（提示「该包名下没有存档」而不是"未找到保存内容"）

---

### P3 — R4 路径历史 UI

**现状**：`PackagePrefs.history()` 已记录（最多 20 条，`\u0001` 分隔），
`applyPackage()` 每次都会 `rememberHistory`。但**界面上完全没有展示**。

**改法**：应用列表对话框里，搜索框下方加一个「最近使用」区块
（或列表顶部一个可折叠小节），列出历史里的包名，点一下直接应用。

**注意**：
- 历史里的包名可能已卸载 → 点之前检查 `PackageManager` 是否还存在
- 提供单个删除（`PackagePrefs.forgetHistory` 已实现）和「清空历史」

---

### P4 — R5 「本次」默认 vs 「永久」默认

**现状**：只有「永久默认」（写 `PackagePrefs.default_pkg`）。

**需求**：「也可以设置为本次或者永久为默认选项」。

**「本次」的语义**：仅本次运行有效，进程被杀或重启后回落到永久默认 / 原版包名。

**改法**：加一个只在内存里活的字段

```kotlin
// PackagePrefs 或 MainActivity
@Volatile
var sessionPackage: String? = null
```

`effectivePackage()` 的优先级：
```
sessionPackage  >  default_pkg  >  从当前路径反解  >  DEFAULT_PACKAGE
```

长按菜单变成三项：
```
设为默认（永久）
仅本次使用
清除默认
```

**注意**：`main` 进程被系统回收后 `sessionPackage` 自然丢失 —— 这符合语义，不用持久化。

---

### P5 — 补充：`checkShizukuAvailable()` 本身的可靠性

诊断中出现过 `checkShizukuAvailable: false` 但 Shizuku 明显可用（`ls` 成功）的矛盾。
目前所有**读操作**调用方已换成 `canReadRestrictedDirs()`，但函数本身没修。

**待查**：
- 它依赖 `useShizuku` 标志，而 `MainActivity:374` / `setUseShizuku(...)` 可能在别处被改
- 是否应改为直接 `Shizuku.pingBinder()` + `checkSelfPermission`，不看 Mode

**风险**：`saveAndPushBack` 等**写操作**仍在用它，若它不可靠，保存逻辑可能同样走错通道。

---

## 三、已完成部分（供回看，不必重做）

| 模块 | 位置 | 说明 |
|------|------|------|
| 候选枚举与三态判定 | `PackageResolver.scan()` | VERIFIED / UNKNOWN / ABSENT；无提权时列「疑似游戏」标 UNKNOWN |
| 批量探测 | `WorldViewModel.listPackagesWithWorlds()` | 一条 `ls -d` glob 拿全部包名，避免几百次进程创建 |
| 单点探测 | `WorldViewModel.hasWorldsUnder()` | 三通道（当前模式 / 强制 Shizuku / su） |
| 默认包持久化 | `PackagePrefs.defaultPackage()` | `nbt_config` 里的 `default_pkg` |
| 历史持久化 | `PackagePrefs.history()` | `pkg_history`，`\u0001` 分隔，最多 20 条 |
| 内部存储开关 | `PackagePrefs.useInternal()` | 只有 root 时 UI 才显示 |
| 静态路径联动 | `MainActivity.PATH_STANDARD` | `PackagePrefs.defaultPackageForStatic` 驱动 |
| 列表对话框 | `MainActivity.showAppListDialog()` | 搜索 / 排序 / 长按菜单 / 三行式条目 |
| 应用包名应用 | `MainActivity.applyPackage()` | 切路径 + 记历史 + 切模式 + 扫描 |
| 智能首次交互 | `MainActivity.showAppPicker()` | 有默认用默认；只有一个 VERIFIED 直接进；否则弹列表 |
| 诊断入口 | `MainActivity.showPackageDiag()` | 模式 / 通道 / ls 原文 / 解析结果，可长按复制 |

---

## 四、相关文件速查

| 文件 | 作用 |
|------|------|
| `app/src/main/java/yhjmew/nibnya/world/PackageResolver.kt` | 枚举应用、拼路径、三态判定、包名反解 |
| `app/src/main/java/yhjmew/nibnya/world/PackagePrefs.kt` | 默认包 / 历史 / 内部开关的持久化 |
| `app/src/main/java/yhjmew/nibnya/MainActivity.kt` | 菜单入口、列表对话框、applyPackage、诊断 |
| `app/src/main/java/yhjmew/nibnya/world/WorldViewModel.kt` | 批量/单点探测、扫描、路径校正 |
| `app/src/main/res/values*/strings.xml` | `pkg_*` 系列文案（中英需同步） |

---

## 五、恢复工作时先做的事

1. 读本文件，确认 P0-P5 哪些还要做
2. `git log --oneline -10` 看有没有别的改动碰过这些文件
3. 记住三条铁律：
   - 无 root 时 `/data/user/0/<pkg>` **永远**读不了，只有 root 能进
   - 判断"能否读受限目录"用 `canReadRestrictedDirs()`，不要用 `checkShizukuAvailable()`
   - 菜单索引跟着 `labels` 动态算，不要写死数字
4. 改完必须本地同步 + 编译验证（见长期记忆里的 `sync-build.bat` 流程）
