# BundlePack

**Zalith Launcher 2 定制版：把整合包和游戏资源预先打进 APK，实现「安装即玩」。**

本项目是对 [Zalith Launcher 2](https://github.com/ZalithLauncher/ZalithLauncher2) 的**增量补丁**，不包含其源码本体。
使用前请先自行获取 Zalith Launcher 2 源码，再把本仓库的补丁应用上去。

---

## 它做了什么

官方 Zalith Launcher 2 导入整合包时，需要联网下载原版游戏文件（client.jar、约 107 个库、4000+ 个资源文件）以及整合包内的所有模组。本项目把这个过程**提前到构建阶段**：

| 环节 | 官方行为 | 本项目 |
|---|---|---|
| 原版游戏文件 | 首次安装时联网下载（约 535 MB） | 构建时打包进 APK，启动时解包 |
| 整合包模组 | 从 Modrinth 逐个下载 | 打包进 APK，解包即用 |
| 用户操作 | 手动选文件、输版本名、等下载 | 打开即装，无交互 |
| 网络要求 | 必须联网 | 完全离线可用 |

## 工作原理

```
APK 首次启动
  │
  ├─ SplashActivity 检测到 EXTRA_BUILTIN_MODPACK
  │
  └─ MainActivity.handleBuiltinModpackImport()
       ├─ BuiltinAssets.release()     解包 builtin_mc.tar.xz → 游戏目录
       │     versions/1.21.11/{jar,json} + libraries/ + assets/
       │
       ├─ BuiltinModpack.release()    解包 builtin_modpack.tar.xz → .mrpack
       │
       └─ ModpackImporter.import(silent = true)
             走官方导入流程；因本地文件齐备，校验全部命中，零下载
             安装完成 → 写入标记文件 → 清理临时 mrpack
```

两个关键点：

1. **`overrides/` 优先** — Modrinth 格式中，放进 `overrides/` 的文件不参与下载。把整合包全部内容移入 `overrides/` 并清空 `files[]`，即可实现零下载安装。
2. **复用官方校验** — 解包出的原版文件带有正确的 sha1，安装流程的完整性校验会直接判定为有效，因此**不需要改动安装逻辑**，只需保证文件到位。

## 补丁内容

### 新增文件

| 文件 | 作用 |
|---|---|
| `patch/autoinstall/BuiltinAssets.kt` | 解包内置的原版游戏资源（tar.xz） |
| `patch/autoinstall/BuiltinModpack.kt` | 解包内置整合包并静默安装 |
| `patch/autoinstall/XzExtractor.kt` | tar.xz 流式解包器（含路径穿越防护） |

三者放置于：
```
ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/download/modpack/autoinstall/
```

### 修改文件

见 `patch/diff/`，共 4 处改动，合计约 200 行：

| 文件 | 改动 |
|---|---|
| `build.gradle.kts` | 新增 `androidResources.noCompress`，避免已压缩资源被 APK 二次压缩 |
| `gradle.properties` | 应用名 / 短名改为 `BundlePack` |
| `MainActivity.kt` | 接入内置整合包静默安装流程 |
| `SplashActivity.kt` | 启动时检测内置整合包标记 |

## 使用方法

### 1. 获取上游源码

```bash
git clone https://github.com/ZalithLauncher/ZalithLauncher2.git
cd ZalithLauncher2
```

### 2. 应用补丁

```bash
# 新增文件
mkdir -p ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/download/modpack/autoinstall/
cp /path/to/BundlePack/patch/autoinstall/*.kt \
   ZalithLauncher/src/main/java/com/movtery/zalithlauncher/game/download/modpack/autoinstall/

# 修改文件
cd ZalithLauncher2
patch -p1 < /path/to/BundlePack/patch/diff/build.gradle.kts.diff
patch -p1 < /path/to/BundlePack/patch/diff/gradle.properties.diff
patch -p1 < /path/to/BundlePack/patch/diff/MainActivity.kt.diff
patch -p1 < /path/to/BundlePack/patch/diff/SplashActivity.kt.diff
```

### 3. 准备内置资源

补丁依赖两个文件，放在 `ZalithLauncher/src/main/assets/`：

| 文件 | 内容 | 获取方式 |
|---|---|---|
| `builtin_mc.tar.xz` | 原版游戏文件（versions/libraries/assets） | 见下方脚本 |
| `builtin_modpack.tar.xz` | 整合包（Modrinth `.mrpack`） | 见下方脚本 |

打包命令：

```bash
# 原版游戏资源（目录结构须为 versions/ libraries/ assets/）
tar -c -I "xz -6 -T 0" -f builtin_mc.tar.xz -C <游戏目录> versions libraries assets

# 整合包（内含单个 .mrpack）
tar -c -I "xz -1 -T 0" -f builtin_modpack.tar.xz -C <mrpack所在目录> your-pack.mrpack
```

> **`-T 0` 会用满所有 CPU 核心**，实测 535 MB 数据从 51 秒降至 11 秒（32 核环境）。

### 4. 构建

```bash
# 只构建 arm64（现代安卓手机），体积可省约 240 MB
./gradlew :ZalithLauncher:assembleDebug -Darch=arm64
```

## 实现细节

### 资源释放时机

在 `MainActivity` 中异步执行，全程保持屏幕常亮（`keepScreen`），并通过标记文件避免重复释放：

- `.builtin_mc_1.21.11` — 游戏资源已解包
- `.builtin_modpack_installed` — 整合包已安装

### 空间策略

初版实现是把 `.mrpack` 完整拷贝到私有目录再导入，导致同一份数据在 APK 内和私有目录各占一份（约 900 MB）。现改为 `tar.xz` 流式解包，且**安装成功后立即删除临时 mrpack**，回收约 454 MB。

### 体积构成（实测）

| 组成 | 体积 |
|---|---|
| 原版游戏资源（tar.xz） | 431 MB |
| 整合包（tar.xz） | 408 MB |
| 原生库 + JRE + 其余 | 约 205 MB |
| **APK 合计** | **约 1044 MB** |

## 版权声明

Zalith Launcher 2 以 **GPL-3.0** 发布，本项目作为其衍生作品同样遵循 **GPL-3.0**。
详见 [LICENSE](LICENSE)。

⚠️ **本仓库仅包含源代码补丁，不包含任何游戏资源。**

用户自行构建时，请勿将以下内容公开分发：

- Mojang 的 `client.jar` 与官方资源文件（版权归 Mojang / Microsoft）
- 第三方模组（各模组有独立许可，多数禁止再分发）

建议的合法路径：让使用者通过官方渠道获取游戏资源，APK 只包含启动器本身与用户自有内容。

## 致谢

- [Zalith Launcher 2](https://github.com/ZalithLauncher/ZalithLauncher2) — 本项目的上游
- [Zalith Launcher 2 作者 MovTery](mailto:movtery228@qq.com)

## 已知限制

- 仅提供源码补丁，不含可直接使用的 APK
- 未做 release 签名配置，默认使用 debug 签名
- 内置资源版本写死为 MC 1.21.11 + Fabric Loader 0.19.x，更换版本需同步修改 `BuiltinAssets.VERSION_NAME` 与资源索引名 `ASSET_INDEX_NAME`
