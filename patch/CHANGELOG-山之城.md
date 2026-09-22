# 山之城 · 定制改动说明

在原版 Zalith Launcher 2 (v2.5.3) 基础上的全部改动，以及每处的落点。

## 1. 应用名 = 山之城

改的是 `ZalithLauncher/gradle.properties`，代码零改动：

```properties
launcher_name=山之城
launcher_app_name=山之城
launcher_short_name=山之城
```

`build.gradle.kts` 本来就用 `launcher_app_name` 作 manifest 占位符、
用 `launcher_name` 拼 APK 文件名，所以包名和文件名都自动跟着变：

- App 桌面名称 → 山之城
- 产物文件名 → `山之城-Debug-2.5.3.apk`

## 2. 应用图标 = 封面图

纯资源替换，没有改任何布局或代码：

| 文件 | 说明 |
|---|---|
| `res/mipmap-*/ic_launcher.webp` | 方形图标（5 档密度） |
| `res/mipmap-*/ic_launcher_round.webp` | 圆形图标 |
| `res/mipmap-*/ic_launcher_foreground.webp` | 自适应图标前景（按 108dp 画布 / 72dp 安全区生成） |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | 前景改指向位图，移除已删除的 monochrome 引用 |
| `res/values/ic_launcher_background.xml` | 背景色改为 `#FFF7F3EF`，贴合图片色调 |
| `res/drawable/splash_launcher.xml` | 启动页图标改指向新的 `@mipmap/ic_launcher_foreground` |

## 3. 启动器背景 = 封面图（全局生效）

这里刻意复用了源码**现成的背景机制**，没有新增设置项、没有改渲染逻辑：

- 源码本来就把背景图固定放在 `PathManager.FILE_LAUNCHER_BACKGROUND`
  （即 `files/background/background01.file`），
  再由 `BackgroundViewModel` 全局共享给所有页面。
- 所以只要**开机时把图铺到那个路径**，主页、设置页、账号管理页
  的背景（含模糊效果、透明度）就全部自动生效。

新增 `patch/renderer/BuiltinBackground.kt`，照搬源码里 `UnpackJreTask`
那套「首次释放资源」的模式：

```kotlin
// ZLApplication.kt onCreate 中，refreshContext 之后
BuiltinBackground.apply(this)
```

行为要点：

- 用 `files/background/.builtin_version` 做版本标记，
  已是当前版本就直接跳过，**不覆盖用户自己换的背景**；
- 图片判定走源码的 `File.isImageFile()`（BitmapFactory 解码），
  与扩展名无关，所以 `.file` 后缀也能正确识别为图片；
- `updateState()` 里有短路：图片判定成功就不会再去试视频。

## 4. APK 文件名乱码修复

原文件名 `BundlePack-Debug-2.5.3-arm64-v8a.apk` 里的 `-arm64-v8a` 后缀
来自 `build.gradle.kts` 的 abi 过滤分支。现在 `arch=all`，
走 else 分支直接输出 `山之城-Debug-2.5.3.apk`。

## 5. 构建环境适配

`gradle.properties` 中针对容器环境的三处调整（不影响产物）：

```properties
org.gradle.workers.max=2          # 容器 32 核但内存受限，限并发防 OOM
org.gradle.jvmargs=-Xmx2200m ...  # 容器 cgroup 硬上限 8G，堆不能给大
offline                            # 构建时必须加 --offline：仓库含 google()/mavenCentral()
                                   # 被沙箱拦截，连接可达但零数据，会卡几十分钟
```

> 容器内存上限可用 `cat /sys/fs/cgroup/memory.max` 确认，
> Java 会把 cgroup 限制报告为 `Host: ... 8G`，据此定堆大小。
