# 改动说明

完整的说明（为什么要裁剪、改了哪些文件、验证数据、行为差异、如何构建）
见：

- [README.md](./README.md) — English
- [README.zh-CN.md](./README.zh-CN.md) — 简体中文

---

## 快速索引

| 关心什么 | 看哪一节 |
|---|---|
| 为什么不用现成的 jar | README → *Why this fork exists* / *为什么要裁剪* |
| 具体改了哪些文件 | README → *What changed* / *改了哪些文件* |
| 怎么确认没改坏 | README → *Verification* / *验证方式* |
| 和原版行为有什么区别 | README → *Behaviour differences* / *行为差异* |
| 怎么重新构建 | README → *Building* / *构建* |

---

## 一句话版本

上游 jar 需要 Guava（2.33 MB），实际只用其中 30 个类型。
本目录把这些类型各自实现一遍放进 `minimal/Minimal.java`，
同时去掉了用不上的 Snappy 和两处 Android / API 26 依赖。
需要打包的体积从 **2605 KB 降到 263 KB**。

正确性用真实存档逐键 SHA-256 比对确认，与裁剪前**完全一致**。

---

## 改动文件清单

| 文件 | 改动 |
|---|---|
| `leveldb/.../minimal/Minimal.java` | **新增**，约 700 行，Guava 替代实现 |
| `leveldb/.../util/Snappy.java` | 重写为「不可用」空实现 |
| `leveldb/.../util/ZLib.java` | 去掉 `android.os.Build`，改反射探测 |
| `leveldb/.../fileenv/ByteBufferSupport.java` | 去掉 `MethodHandle`，改纯反射 |
| 其余 41 个文件 | import 替换 + 调用点改名 |

---

## 重新构建

```bash
# 在仓库根目录（编辑器/）执行
python build_leveldb_java.py
```

产物覆盖 `app/libs/leveldb-java.jar` 和 `app/libs/leveldb-java-api.jar`。
