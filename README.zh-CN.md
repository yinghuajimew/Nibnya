<div align="center">

<img height="120" src="./Screenshots/Nibnya.png">

<h1 align="center">樱喵NBT</h1>

<!-- SHIELD GROUP -->
[![Version][version-shield]][version-link]
[![License][license-shield]][license-link]
[![Issues][issues-shield]][issues-link]

[![Forks][forks-shield]][forks-link]
[![Stars][stars-shield]][stars-link]
[![Contributors][contributors-shield]][contributors-link]

<!-- 社交与外部链接 -->
[![Bilibili][bilibili-shield]][bilibili-link]
[![QQ Group][qq-shield]][qq-link]
[![Telegram][tg-shield]][tg-link]

[English](./README.md) · 简体中文 · [变更日志](./CHANGELOG.zh-CN.md)

</div>

<br/>

> [!TIP]
> 目前总算稳定一半了，还有一些东西后续会补下去

> [!WARNING]
> 我知道会有问题，但是我还得道歉<br/>
> **此应用代码由 deepseek v4 编写**<br/>

> [!TIP]
> 您想要参与到此，应用功能添加，功能修改等<br/>
> 请fork并提交pr，或者fork后自己修补并整理

<details>
<summary><kbd>目录</kbd></summary>

#### 目录列表

- [🌸 项目简介](#-项目简介)
- [🖼️ 应用截图](#-应用截图)
- [🚀 快速上手](#-快速上手)
- [📄 更新日志](#-更新日志)
- [👥 感谢列表](#-感谢列表)
- [📜 开源协议](#-开源协议)

</details>

<br/>

## 🌸 项目简介
仿造Blocktopograph，并没有直接从他而改来的
就像轮胎一样，仿造了一个类似

## 🖼️ 应用截图
<details>
<summary><kbd>🌸 查看应用界面预览</kbd></summary>

<div align="center">
  <br/>
  <!-- 这里直接引用底部的链接名是不行的，必须写路径或者直接用引用式 -->
  <!-- 建议如果需要限宽，直接在 details 里用 img 标签 -->
  <img src="./Screenshots/zh/主页.jpg" width="80%" alt="主页">
  <br/>
  <img src="./Screenshots/zh/世界展示.jpg" width="80%" alt="世界展示">
  <br/>
  <img src="./Screenshots/zh/全屏加树型展示.jpg" width="80%" alt="全屏加树型展示">
  <br/>
  <img src="./Screenshots/zh/根节点操作.jpg" width="80%" alt="根节点操作">
  <br/>
  <img src="./Screenshots/zh/切换显示模式.jpg" width="80%" alt="切换显示模式">
  <br/>
  <img src="./Screenshots/zh/恢复备份.jpg" width="80%" alt="恢复备份">
  <br/>
  <img src="./Screenshots/zh/侧边栏.jpg" width="80%" alt="侧边栏">
  <br/>
  <img src="./Screenshots/zh/玩家数据.jpg" width="80%" alt="玩家数据">
  <br/>
  <img src="./Screenshots/zh/地图数据.jpg" width="80%" alt="地图数据">
  <br/>
  <img src="./Screenshots/zh/更多操作.jpg" width="80%" alt="更多操作">
  <br/>
  <img src="./Screenshots/zh/地图拼图警告.jpg" width="80%" alt="地图拼图警告">
  <br/>
  <img src="./Screenshots/zh/选择拼图规模.jpg" width="80%" alt="选择拼图规模">
  <br/>
  <img src="./Screenshots/zh/村庄数据.jpg" width="80%" alt="村庄数据">
  <br/>
  <img src="./Screenshots/zh/长按菜单.jpg" width="80%" alt="长按菜单">
  <br/>
  <img src="./Screenshots/zh/按名称打开.jpg" width="80%" alt="按名称打开">
  <br/>
  <img src="./Screenshots/zh/全局系统数据.jpg" width="80%" alt="全局系统数据">
  <br/>
</div>

</details>

## 🚀 快速上手
### 📺 使用介绍

介绍与使用教程：

[![Watch the video](./Screenshots/Minecraft_安卓_NBT编辑器.png)](https://www.bilibili.com/video/BV1mkfnBnEKC)

> [!TIP]
> 确保您**具有基础[Shizuku](https://github.com/RikkaApps/Shizuku)使用**，此应用会在启动时索要<br/>
> 否则会**无法访问Android/data** Minecraft 文件<br/>
> **目前支持Android 6 ~ 13**<br/>
> 获取所有文件权限是为了，下载目录与旧目录读写和私有目录往返

## 📄 更新日志
<details>
<summary><kbd>本次更新（1.0.4 正式版）</kbd></summary>

#### 新增

- [x] **ROOT 支持**：新增统一提权层，ROOT / Shizuku / 正常三种模式可选。root 检测不是看 `su` 文件在不在，而是真跑一次 `su -c id` 查 `uid=0`——装了 su 但没授权、假 root 都不会误判。
- [x] **运行模式选择器**：设置里新增「运行模式」，点一下就地展开（不是弹窗）：自动 / ROOT / Shizuku / 正常。选 ROOT 但设备没有 root 会自动降级到 Shizuku，再不行降正常模式，并 toast 告知。
- [x] **自定义 su 命令模板**：可改写 `su -c {cmd}` 的形式（适配 Magisk / KernelSU / 改版 su）。另附三个预设（`su -c`、`su -0 -c`、`su --mount-master -c`），点选后立即实测并告诉你这个模板通不通。模板必须含 `{cmd}` 才允许保存。
- [x] **「软件内部」路径**：路径选择新增 `/data/user/0/<包名>/games/com.mojang/minecraftWorlds/`，这是 Minecraft 真正的应用私有目录。**仅在真实 root 下显示**，每次打开都重新实测。
- [x] **写回改用原子替换**：新数据先完整落到同分区的临时目录，原目录改名让位，新目录顶上，成功才删旧、失败立刻回滚。任何时刻原存档都完整存在。同时加写回自检（`CURRENT` 必须存在、不能出现嵌套），异常自动回滚。
- [x] **存档缩略图真正显示**：标准路径在 `Android/data` 下，Android 11+ 分区存储让直接读文件必然失败，所以以前三个方块永远是灰底。改为让提权通道直接把字节流读进内存解码，全程不落盘。
- [x] **存档列表三行布局**：世界名 / 文件夹 ID / 最后游玩时间拆成三行独立显示，名字再长也挤不掉时间；文件夹 ID 中间省略、保证尾部 `=` 可见。
- [x] **边缘滑动侧边栏**：从左边缘 18dp 内往右滑拉出，已打开时任意位置左滑收起；遮罩改为全透明但仍承担点击关闭。
- [x] **夜间模式真正适配**：新增两套色板（日间 / 夜间），布局与代码里 76 处硬编码颜色全部改为主题色；系统弹窗（设置 / 备份 / 恢复 / 根节点操作等）也一并跟随，不再白底看不清。
- [x] **权限检查并入设置**：主页顶部精简为「清理缓存 / 切换路径」，权限检查移进设置，点击立即实测并给出明确结论。
- [x] **种子单击复制**：不再需要长按选中，点一下就复制，且不会把 emoji 一起复制进去。
- [x] **自由裁剪按行列出图**：以前选了 5×5 却在「自由裁剪」只出 1 张，现在会按选定的行列正确切分成 25 张。
- [x] **锁定正方形自动调整**：打开「锁定比例（正方形）」时，裁剪框会立刻收敛成最大正方形，不用再手动拖一下才生效。

#### 修复

- [x] **写回后游戏读不了存档**（最严重）：用 root 写出的文件归属是 `root:root`，而 Minecraft 以它自己的 uid 运行，权限位只有 `rw-------`，于是文件明明在、大小也正常，游戏却报「未找到本地服务器」，列表还显示 `4167549952` 这种荒谬体积。现在写回前会把归属还给该存档所属的应用。
- [x] **归属修复的自我循环**：旧逻辑优先读「当前 db 目录」的归属，一旦它已被写坏成 root，读回来还是 root，于是每次保存继续写坏、永远修不好。改为以「应用自己的目录归属」为准。
- [x] **su 命令被外层 shell 拆断**：命令里有 `&&` 时，只有第一段进了 root，后面的段落跑在普通应用身份上，`cp` 直接 `Permission denied`，看起来像「root 写不了」。改为把整条命令作为单个参数交给 su。
- [x] **root 下无法删除「软件内部」存档**：`/data/...` 只有 root 能看见，原生存在性判断恒为 false，导致明明存档在却提示「找不到存档目录」。
- [x] **老坏档自动修复**：打开存档时会自动检查归属，之前被写坏的存档不用重新保存也能救回来。
- [x] **Android 16 / HyperOS 保存失败只说一句「无法写入」**：现在会真实探测能否写，并给出可操作的解决路径（HyperOS 需要单独开启「USB 调试（安全设置）」）。
- [x] **侧边栏三个交互问题**：关闭后再次滑动失效、遮罩层点不到（点下去会穿透到主界面）、无法从右往左关闭。
- [x] **首次滑动只出现遮罩 / 宽度算错**：控件刚显示时读宽度还是 0，导致位置算错。

</details>

<details open>
<summary><kbd>已解决</kbd></summary>

- [x] 回收站：删除存档先移入回收站，不再直接抹掉
- [x] 导出 `.mcworld`：把整个世界打包成可被 Minecraft 直接导入的存档包
- [x] 草稿箱：未保存的编辑收进草稿箱，不再用弹窗打扰
- [x] 剪贴板重做：复制同时写入系统剪贴板，支持 JSON / SNBT / 纯文本
- [x] 导出到文件：可存下载目录，也可走 SAF 自选任意目录
- [x] **SAF 完整实现**：读写删全部走 DocumentFile，保存不再「谎报成功」
- [x] 选择世界 / 草稿箱 / 备份三个列表统一格式，底部标签栏 + 长按菜单
- [x] **修复：玩家数据新增 List 后保存丢失** —— 真根因是误删 LevelDB 索引文件
- [x] 修复：大存档被误判为 LOCK 导致打不开
- [x] 修复：树形视图全面可用（增 / 改 / 删 / 搜索 / 保存）
- [x] 修复：地图画生成内存溢出
- [x] 修复：备份目录两套定义不一致（「清空备份」清的是空目录却提示成功）
- [x] 修复：一展开设置就弹「自定义」输入框、设置面板无法滚动
- [x] 修复：设置自定义草稿间隔 / 线程数时崩溃
- [x] 修复：会话恢复对话框张冠李戴、「丢弃后会话又复活」
- [x] 修复：标签栏不贴底
- [x] 修复：issue #6 —— 自定义路径保存不再误报「需要 Shizuku」
- [x] 多线程设置真正生效（文件复制 + 地图画，并发数可调）
- [x] 国际化补齐（中英字符串严格对称）
- [x] 按功能域重新分包（`nbt/` `db/` `editor/` `world/` `mapart/` `util/`）
- [x] 改名为 樱喵NBT / Nibnya，更换图标，数据目录 `NbtEditor_Data/` → `Nibnya/`

</details>

<details open>
<summary><kbd>待解决</kbd></summary>

- [ ] 运行内存不足（大存档会被系统杀进程）
- [ ] 自动更新
- [ ] 手动优化 UI
- [ ] 备份逻辑优化（不再每次保存都备份；改为按时间 / 用户勾选 / 手动）
- [ ] 地图平面展示与编辑（区块查看 / 修改 / 移动）
- [ ] 存档多开（同一存档不可多窗打开）
- [ ] Blocktopograph 风格界面
- [ ] 预设模板（玩家 / 村庄 / 地图）
- [ ] 一键修改工具（装备 32767、命令行物品等）
- [ ] Minecraft 实用基岩版工具页（第三页面）
- [ ] 老地图升级（跨大版本 NBT 升级）
- [ ] 多用户兼容
- [ ] KMP 跨平台（Windows / Linux 桌面版）

</details>

_其他想法和修改还在实现中_

## 👥 感谢列表
 [@Yejdhi](https://github.com/Yejdhi) 给我提供了许多方法和修改的点子<br/>
 [@LeviMC](https://github.com/LiteLDev) 给我提供了方法和修改的点子<br/>
(也推荐看看LeviMC他们的开源项目)

## 📜 开源协议

本项目采用 **[AGPL 3.0](https://www.gnu.org/licenses/agpl-3.0)** 协议



<!-- 这里是底部的链接定义-->

[version-shield]: https://img.shields.io/badge/版本-v1.0.4-369eff?style=flat-square&labelColor=black
[version-link]: https://github.com/yinghuajimew/Nibnya/releases

[issues-shield]: https://img.shields.io/github/issues/yinghuajimew/Nibnya?color=ff80eb&labelColor=black&style=flat-square
[issues-link]: https://github.com/yinghuajimew/Nibnya/issues

[forks-shield]: https://img.shields.io/github/forks/yinghuajimew/Nibnya?color=8ae8ff&labelColor=black&style=flat-square
[forks-link]: https://github.com/yinghuajimew/Nibnya/network/members

[stars-shield]: https://img.shields.io/github/stars/yinghuajimew/Nibnya?color=ffcb47&labelColor=black&style=flat-square
[stars-link]: https://github.com/yinghuajimew/Nibnya/stargazers

[contributors-shield]: https://img.shields.io/github/contributors/yinghuajimew/Nibnya?color=c4f042&labelColor=black&style=flat-square
[contributors-link]: https://github.com/yinghuajimew/Nibnya/graphs/contributors

[license-shield]: https://img.shields.io/badge/license-AGPL_3.0-blue?style=flat-square&labelColor=black
[license-link]: https://www.gnu.org/licenses/agpl-3.0

<!-- 社交链接定义 -->

<!-- Bilibili: 少女粉 #FB7299 -->
[bilibili-shield]: https://img.shields.io/badge/Bilibili-FB7299?style=flat-square&labelColor=black&logo=bilibili&logoColor=white
[bilibili-link]: https://www.bilibili.com/video/BV1mkfnBnEKC

<!-- QQ交流群: 腾讯蓝 #12B7F5 -->
[qq-shield]: https://img.shields.io/badge/QQ交流群-12B7F5?style=flat-square&labelColor=black&logo=qq&logoColor=white
[qq-link]: https://qm.qq.com/cgi-bin/qm/qr?_wv=1027&group_code=1077349003

<!-- Telegram频道: 电报蓝 #26A5E4 -->
[tg-shield]: https://img.shields.io/badge/Telegram频道-26A5E4?style=flat-square&labelColor=black&logo=telegram&logoColor=white
[tg-link]: https://t.me/yhjmew_minecraft_nbteditor
