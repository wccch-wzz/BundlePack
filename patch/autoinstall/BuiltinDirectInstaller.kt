/*
 * Zalith Launcher 2 - 内置整合包直装扩展
 *
 * 本文件为第三方修改版新增功能。
 *
 * 与原有的「下载式安装」不同，这里不解析整合包清单、不联网核对原版，
 * 而是把随包内置的 tar.xz 直接铺到游戏目录，再做一次版本登记。
 *
 * 为什么需要它：
 *   原有安装链路（ModpackImporter -> GameInstaller）第一步就是「下载安装原版」，
 *   会去联网核对 Mojang 的版本清单/资源索引。这一步在无网或网络不稳时会失败，
 *   导致整条任务流中断，最终 Fabric 版本目录压根不会被创建 ——
 *   表现就是「打开之后还是原版」，而且不报错。
 *
 *   而内置资源本来就是完整的（原版 jar/json、Fabric loader profile、115 个库），
 *   根本不需要「安装」，只需要「摆放」。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.game.download.modpack.autoinstall

import android.content.Context
import com.movtery.zalithlauncher.game.path.getGameHome
import com.movtery.zalithlauncher.game.version.installed.VersionConfig
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipFile as ApacheZipFile
import java.io.File

private const val TAG = "BuiltinDirectInstaller"

/**
 * 内置整合包直装器
 *
 * 把内置资源直接铺进游戏目录，跳过整条下载式安装链路。
 */
object BuiltinDirectInstaller {

    /**
     * 安装后的版本名（游戏列表里显示的名字）
     */
    const val VERSION_NAME = "山之城"

    /**
     * 内置整合包内部的 overrides 目录名
     */
    private const val OVERRIDES_DIR = "overrides"

    /**
     * 安装标记文件（放在版本目录里，随版本一起被删除）
     */
    private const val MARKER_NAME = ".builtin_direct_installed"

    /**
     * 直装是否已完成
     */
    fun isInstalled(gameHome: String = getGameHome()): Boolean {
        return markerFile(gameHome).exists()
    }

    private fun markerFile(gameHome: String): File {
        return File(versionDir(gameHome), MARKER_NAME)
    }

    /**
     * 版本目录：versions/山之城
     */
    fun versionDir(gameHome: String = getGameHome()): File {
        return File(gameHome, "versions/$VERSION_NAME")
    }

    /**
     * 执行直装
     *
     * @param context 用于读取 assets
     * @param onProgress 进度回调（阶段描述, 0f~1f）
     * @return 是否成功
     */
    suspend fun install(
        context: Context,
        gameHome: String = getGameHome(),
        onProgress: ((String, Float) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val root = File(gameHome)
        val version = versionDir(gameHome)

        try {
            root.mkdirs()

            // ── 第一步：铺游戏本体（原版 + Fabric 库）──────────────────────
            //内置的 builtin_mc.tar.xz 顶层就是 versions/ libraries/ assets/，
            //直接解到游戏根目录即可，目录结构天然正确。
            onProgress?.invoke("正在释放游戏本体", 0f)
            if (!BuiltinAssets.exists(context)) {
                Logger.error(TAG, "assets 中没有 ${BuiltinAssets.ASSET_NAME}", null)
                return@withContext false
            }
            context.assets.open(BuiltinAssets.ASSET_NAME).use { input ->
                XzExtractor.extract(
                    input = input,
                    targetDir = root,
                    onProgress = { bytes ->
                        //用「已写出字节数 / 预期总量」估算进度，前 70% 归游戏本体
                        val ratio = (bytes.toFloat() / 400_000_000f).coerceIn(0f, 1f)
                        onProgress?.invoke("正在释放游戏本体", ratio * 0.7f)
                    }
                )
            }

            // ── 第二步：把版本别名指向内置的 Fabric 版本 ──────────────────
            //游戏本体里已经带了 versions/fabric-loader-0.19.3-1.21.11/，
            //但用户看到的名字应该是「山之城」，所以复制一份到 versions/山之城/。
            //
            //目标结构（版本隔离开启后，这里就是游戏实际目录）：
            //  versions/山之城/
            //    ├── 山之城.json
            //    ├── 山之城.jar
            //    └── mods/
            onProgress?.invoke("正在配置 Fabric 版本", 0.72f)
            val builtinFabric = File(root, "versions/${BuiltinModpack.FABRIC_VERSION_DIR}")
            if (!builtinFabric.isDirectory) {
                Logger.error(TAG, "内置 Fabric 版本目录不存在: ${builtinFabric.absolutePath}", null)
                return@withContext false
            }
            version.mkdirs()
            builtinFabric.listFiles()?.forEach { file ->
                if (file.isFile) {
                    //json 需要改名为「山之城.json」，否则启动器按目录名找不到版本 Json
                    val target = if (file.name.endsWith(".json")) {
                        File(version, "$VERSION_NAME.json")
                    } else {
                        File(version, file.name)
                    }
                    file.copyTo(target, overwrite = true)
                }
            }

            //把复制过来的 json 里的 id 改成新版本名，
            //否则版本信息与目录名不一致，启动器可能判定为无效版本。
            //这里用纯字符串替换即可：id 字段在 Fabric profile 里是固定的字面量。
            val versionJson = File(version, "$VERSION_NAME.json")
            if (versionJson.isFile) {
                val patched = versionJson.readText().replace(
                    "\"id\": \"${BuiltinModpack.FABRIC_VERSION_DIR}\"",
                    "\"id\": \"$VERSION_NAME\""
                )
                versionJson.writeText(patched)
            } else {
                Logger.error(TAG, "未能生成版本 Json: ${versionJson.absolutePath}", null)
                return@withContext false
            }

            // ── 第三步：铺版本目录下的 jar 与 mods ────────────────────────
            onProgress?.invoke("正在释放整合包内容", 0.76f)
            val releasedPack = releasedPackFile(context)
            if (releasedPack == null) {
                Logger.error(TAG, "未能取得内整合包文件", null)
                return@withContext false
            }

            //版本目录下的 jar
            //说明：json 里有 inheritsFrom，启动实际读的是
            //versions/1.21.11/1.21.11.jar（getInheritedClientJar）。
            //这份是照用户要求铺的：目录结构完整，且 inheritsFrom 被去掉时能兜底。
            ensureVersionJar(version, root)

            //mods：从 mrpack 的 overrides/mods/ 拆出来，铺到 versions/山之城/mods/
            applyOverrides(releasedPack, version) { ratio ->
                onProgress?.invoke("正在释放整合包内容", 0.76f + ratio * 0.22f)
            }

            // ── 第四步：写入版本配置（开启隔离）──────────────────────────
            //渲染器不在这里写死。
            //MobileGlues 是外部插件，安装与否、包名是否变化都不确定，
            //因此改由 MobileGluesGuard 在每次启动前强制校验并自动纠正，
            //这样即使插件后装、被卸载或换包名，也能正确跟随。
            onProgress?.invoke("正在写入版本配置", 0.99f)
            VersionConfig.createIsolation(version).apply {
                versionSummary = "山之城 - 开箱即用整合包"
                ramAllocation = -1
            }.save()

            markerFile(gameHome).writeText(
                buildString {
                    appendLine("installed at ${System.currentTimeMillis()}")
                    appendLine("version=$VERSION_NAME")
                    appendLine("source=${BuiltinModpack.FABRIC_VERSION_DIR}")
                    appendLine("mods=${File(version, "mods").listFiles()?.count { it.name.endsWith(".jar") } ?: 0}")
                }
            )

            onProgress?.invoke("完成", 1f)
            Logger.info(TAG, "内置整合包直装完成: ${version.absolutePath}")
            true
        } catch (e: Exception) {
            Logger.error(TAG, "内置整合包直装失败", e)
            false
        }
    }

    /**
     * 确保版本目录下有一份客户端 jar
     *
     * 优先从继承的版本目录复制，其次用版本目录里已有的同名 jar。
     */
    private fun ensureVersionJar(version: File, gameHome: File) {
        val target = File(version, "$VERSION_NAME.jar")
        if (target.isFile && target.length() > 0) return

        val candidates = listOf(
            //继承目标：versions/1.21.11/1.21.11.jar
            File(File(gameHome, "versions/1.21.11"), "1.21.11.jar"),
            //内置 Fabric 版本目录里如果有，也认
            File(File(gameHome, "versions/${BuiltinModpack.FABRIC_VERSION_DIR}"), "$VERSION_NAME.jar")
        )

        val source = candidates.firstOrNull { it.isFile && it.length() > 0 }
        if (source == null) {
            Logger.warning(TAG, "本地没有可用的客户端 jar，跳过版本 jar 的铺设")
            return
        }

        runCatching {
            source.copyTo(target, overwrite = true)
            Logger.info(TAG, "已铺设版本 jar: ${target.name} (${target.length()} 字节)")
        }.onFailure {
            Logger.warning(TAG, "铺设版本 jar 失败: ${it.message}")
        }
    }

    /**
     * 取得已释放到本地的 .mrpack 文件
     *
     * 优先复用 BuiltinModpack 已释放的副本；没有则现场释放一次。
     */
    private suspend fun releasedPackFile(context: Context): File? {
        val target = File(context.filesDir, "builtin_pack/builtin_modpack.mrpack")
        if (target.isFile && target.length() > 0) return target
        return BuiltinModpack.release(context)
    }

    /**
     * 将 mrpack 内的 overrides/ 铺到目标目录
     */
    private fun applyOverrides(
        mrpack: File,
        targetDir: File,
        onProgress: ((Float) -> Unit)? = null
    ) {
        ApacheZipFile.builder().setFile(mrpack).get().use { zip ->
            val entries = zip.entries.toList().filter { entry ->
                entry.name.startsWith("$OVERRIDES_DIR/") && !entry.isDirectory
            }
            if (entries.isEmpty()) {
                Logger.warning(TAG, "mrpack 内没有 overrides 内容")
                return
            }

            entries.forEachIndexed { index, entry ->
                //去掉开头的 overrides/ 前缀，剩下的就是相对游戏目录的路径
                val relative = entry.name.removePrefix("$OVERRIDES_DIR/")
                if (relative.isBlank()) return@forEachIndexed

                val outFile = File(targetDir, relative)
                //防路径穿越
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalFile.path + File.separator)) {
                    Logger.warning(TAG, "跳过越界条目: ${entry.name}")
                    return@forEachIndexed
                }

                outFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
                }

                if (index % 5 == 0) {
                    onProgress?.invoke(index.toFloat() / entries.size)
                }
            }
            Logger.info(TAG, "已铺设 ${entries.size} 个整合包文件到 ${targetDir.absolutePath}")
        }
    }

    /**
     * 供外部（如解压界面）判断是否需要直装
     */
    fun shouldInstall(context: Context, gameHome: String = getGameHome()): Boolean {
        if (isInstalled(gameHome)) return false
        return BuiltinAssets.exists(context) && BuiltinModpack.exists(context)
    }
}
