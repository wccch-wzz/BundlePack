/*
 * Zalith Launcher 2 - 内置整合包解压任务
 *
 * 本文件为第三方修改版新增功能。
 *
 * 做的是和 UnpackJreTask 同一件事：把随包资源解到目标位置。
 *
 * assets 布局（与 Java 环境那套一致）：
 *   assets/builtin_modpack/
 *     ├── version                  ← 纯文本版本号，用于比对是否需要重解
 *     └── shancheng.tar.xz         ← 解出来就是 versions/山之城/ 的完整目录
 *
 * shancheng.tar.xz 内的结构：
 *   versions/山之城/
 *     ├── 山之城.json
 *     ├── 山之城.jar
 *     ├── mods/            （55 个 mod）
 *     ├── options.txt
 *     ├── resourcepacks/
 *     ├── shaderpacks/
 *     └── PCL/
 *
 * 为什么不像之前那样「先解一份再复制」：
 *   原先要先把内置 mc 包里的 versions/1.21.11/1.21.11.jar
 *   解出来，再复制成 versions/山之城/山之城.jar —— 多一次 31MB 的 I/O，
 *   而且版本目录里的 json 还要事后改 id。
 *   现在这些在打包阶段就完成了，运行时只是一次解压，直接到位。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.game.download.modpack.autoinstall

import android.content.Context
import android.content.res.AssetManager
import com.movtery.zalithlauncher.components.AbstractUnpackTask
import com.movtery.zalithlauncher.components.InstallableItem
import com.movtery.zalithlauncher.game.path.getGameHome
import com.movtery.zalithlauncher.game.version.installed.VersionConfig
import com.movtery.zalithlauncher.utils.file.readString
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.io.FileUtils
import java.io.File

private const val TAG = "UnpackBuiltinModpackTask"

/**
 * 内置整合包的相关常量
 *
 * 随包有两份互补的资源，缺一不可：
 *   builtin_mc.tar.xz        → versions/1.21.11/、libraries/、assets/
 *                              游戏本体、115 个库、资源索引
 *   builtin_modpack 目录下的 tar.xz → versions/山之城/
 *                              版本 json、客户端 jar、mods、配置
 */
object BuiltinPack {
    /** assets 下的目录名 */
    const val ASSETS_DIR = "builtin_modpack"

    /** 压缩包文件名（版本目录那一份） */
    const val PACK_NAME = "shancheng.tar.xz"

    /** 游戏本体的压缩包名 */
    const val MC_PACK_NAME = "builtin_mc.tar.xz"

    /** 版本文件 */
    const val VERSION_FILE = "version"

    /** 安装后的版本名，也是版本目录名 */
    const val VERSION_DIR = "山之城"

    /** 记录「已解压版本号」的文件，放在版本目录里 */
    private const val INSTALLED_VERSION_FILE = ".pack_version"

    /** 记录已解压版本号的文件名（供外部清理时使用） */
    const val INSTALLED_MARKER = INSTALLED_VERSION_FILE

    /** assets 中版本目录压缩包的完整路径 */
    val packAssetPath: String get() = "$ASSETS_DIR/$PACK_NAME"

    /** assets 中游戏本体压缩包的路径 */
    val mcPackAssetPath: String get() = MC_PACK_NAME

    /** assets 中版本文件的完整路径 */
    val versionAssetPath: String get() = "$ASSETS_DIR/$VERSION_FILE"
}

/**
 * 内置整合包解压任务
 */
class UnpackBuiltinModpackTask(
    private val context: Context
) : AbstractUnpackTask() {

    private lateinit var assetManager: AssetManager

    /** 随包的版本号 */
    private var packVersion: String? = null

    /** 初始化失败（assets 缺失等） */
    private var isCheckFailed: Boolean = false

    init {
        runCatching {
            assetManager = context.assets
            packVersion = assetManager.open(BuiltinPack.versionAssetPath).readString()
            //顺带确认两个压缩包都在，避免有 version 却没包
            assetManager.open(BuiltinPack.packAssetPath).close()
            assetManager.open(BuiltinPack.mcPackAssetPath).close()
            Logger.info(TAG, "内置整合包版本: $packVersion")
        }.onFailure { e ->
            Logger.warning(TAG, "内置整合包资源不可用，跳过：${e.message}")
            isCheckFailed = true
        }
    }

    fun isCheckFailed() = isCheckFailed

    override fun checkState(): InstallableItem.State {
        if (isCheckFailed) return InstallableItem.State.NOT_EXISTS
        val expected = packVersion ?: return InstallableItem.State.NOT_EXISTS

        val versionDir = versionDir()
        val installedFile = File(versionDir, BuiltinPack.INSTALLED_MARKER)

        //游戏本体是否就位（libraries 与 assets 是启动前提）
        val librariesOk = File(gameHome(), "libraries").isDirectory
        val clientJar = File(gameHome(), "versions/1.21.11/1.21.11.jar")

        return when {
            //没解过，或本体缺失
            !versionDir.isDirectory || !installedFile.isFile ||
                !librariesOk || !clientJar.isFile ->
                InstallableItem.State.NOT_STARTED

            //解过，但随包版本变了
            installedFile.readText().trim() != expected ->
                InstallableItem.State.PENDING

            else ->
                InstallableItem.State.FINISHED
        }
    }

    override suspend fun run() {
        val expected = packVersion ?: error("内置整合包版本未知")
        val target = gameHome()

        updateMessage("正在解压游戏本体")
        Logger.info(TAG, "开始解压，目标: ${target.absolutePath}")

        withContext(Dispatchers.IO) {
            // ── 第一步：游戏本体（原版 jar、115 个库、资源索引）──────────
            //这一份不能少：libraries/ 和 assets/ 是启动游戏的前提，
            //versions/1.21.11/1.21.11.jar 也是 inheritsFrom 的实际指向。
            assetManager.open(BuiltinPack.mcPackAssetPath).use { input ->
                XzExtractor.extract(
                    input = input,
                    targetDir = target,
                    onProgress = { bytes ->
                        val ratio = (bytes.toFloat() / 400_000_000f).coerceIn(0f, 1f)
                        updateMessage("正在解压游戏本体 ${(ratio * 100).toInt()}%")
                    }
                )
            }

            // ── 第二步：版本目录（json / jar / mods / 配置）──────────────
            //版本有变化时先清掉旧版本目录，避免新旧文件混杂
            val versionDir = versionDir()
            if (versionDir.isDirectory) {
                runCatching {
                    FileUtils.deleteDirectory(versionDir)
                }.onFailure {
                    Logger.warning(TAG, "清理旧版本目录失败", it)
                }
            }

            updateMessage("正在解压内置整合包")
            assetManager.open(BuiltinPack.packAssetPath).use { input ->
                XzExtractor.extract(
                    input = input,
                    targetDir = target,
                    onProgress = { bytes ->
                        val ratio = (bytes.toFloat() / 420_000_000f).coerceIn(0f, 1f)
                        updateMessage("正在解压内置整合包 ${(ratio * 100).toInt()}%")
                    }
                )
            }

            //写版本标记
            File(versionDir, BuiltinPack.INSTALLED_MARKER)
                .also { it.parentFile?.mkdirs() }
                .writeText("$expected\n")

            //写入版本配置：开启隔离
            //渲染器不在这里写死，由 MobileGluesGuard 在启动前强制校正。
            VersionConfig.createIsolation(versionDir).apply {
                versionSummary = "山之城 - 开箱即用整合包"
                ramAllocation = -1
            }.save()
        }

        updateMessage(null)
        Logger.info(TAG, "内置整合包解压完成: ${versionDir().absolutePath}")
    }

    private fun gameHome(): File = File(getGameHome())

    private fun versionDir(): File =
        File(gameHome(), "versions/${BuiltinPack.VERSION_DIR}")
}
