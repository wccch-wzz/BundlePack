/*
 * Zalith Launcher 2 - 内置整合包兜底安装器
 *
 * 本文件为第三方修改版新增功能。
 *
 * 正常情况下，内置整合包由 UnpackBuiltinModpackTask 在启动画面解压到位
 * （与 Java 环境一起，见 SplashActivity 的 initUnpackItems）。
 * 本文件只在那种流程未生效时兜底，做的事其实一样：
 * 把 assets/builtin_modpack/shancheng.tar.xz 解到游戏目录。
 *
 * 为什么需要兜底：
 *   启动画面的解压项依赖 checkState()，若 assets 布局与预期不符，
 *   该任务会被判定为 NOT_EXISTS 而跳过。
 *   此时若不兜底，用户会拿到一个没有任何版本的空白游戏目录。
 *
 * 与最初的实现相比，这里不再做「解 mrpack 再挑 overrides」的活：
 *   版本目录的完整结构（json/jar/mods/配置）已经在打包阶段就绪，
 *   运行时只需要解压。
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
import org.apache.commons.io.FileUtils
import java.io.File

private const val TAG = "BuiltinDirectInstaller"

/**
 * 内置整合包兜底安装器
 */
object BuiltinDirectInstaller {

    /**
     * 版本名，同时也是版本目录名
     */
    const val VERSION_NAME = BuiltinPack.VERSION_DIR

    /**
     * 版本目录
     */
    fun versionDir(gameHome: String = getGameHome()): File {
        return File(gameHome, "versions/$VERSION_NAME")
    }

    /**
     * 直装是否已完成
     */
    fun isInstalled(gameHome: String = getGameHome()): Boolean {
        return File(versionDir(gameHome), BuiltinPack.INSTALLED_MARKER).isFile
    }

    /**
     * 是否需要兜底
     */
    fun shouldInstall(context: Context, gameHome: String = getGameHome()): Boolean {
        if (isInstalled(gameHome)) return false
        return runCatching {
            context.assets.open(BuiltinPack.packAssetPath).close()
            context.assets.open(BuiltinPack.mcPackAssetPath).close()
            true
        }.getOrDefault(false)
    }

    /**
     * 解压内置整合包
     *
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

            //版本变了先清干净，避免新旧文件混杂
            if (version.isDirectory) {
                runCatching {
                    FileUtils.deleteDirectory(version)
                }.onFailure { Logger.warning(TAG, "清理旧版本目录失败", it) }
            }

            onProgress?.invoke("正在解压游戏本体", 0f)
            context.assets.open(BuiltinPack.mcPackAssetPath).use { input ->
                XzExtractor.extract(
                    input = input,
                    targetDir = root,
                    onProgress = { bytes ->
                        val ratio = (bytes.toFloat() / 400_000_000f).coerceIn(0f, 1f)
                        onProgress?.invoke("正在解压游戏本体", ratio * 0.6f)
                    }
                )
            }

            onProgress?.invoke("正在解压内置整合包", 0.6f)
            context.assets.open(BuiltinPack.packAssetPath).use { input ->
                XzExtractor.extract(
                    input = input,
                    targetDir = root,
                    onProgress = { bytes ->
                        val ratio = (bytes.toFloat() / 420_000_000f).coerceIn(0f, 1f)
                        onProgress?.invoke("正在解压内置整合包", 0.6f + ratio * 0.35f)
                    }
                )
            }

            val versionJson = File(version, "$VERSION_NAME.json")
            if (!versionJson.isFile) {
                Logger.error(TAG, "解压后未找到版本 Json: ${versionJson.absolutePath}", null)
                return@withContext false
            }

            onProgress?.invoke("正在写入版本配置", 0.98f)
            VersionConfig.createIsolation(version).apply {
                versionSummary = "山之城 - 开箱即用整合包"
                ramAllocation = -1
            }.save()

            val packVersion = runCatching {
                context.assets.open(BuiltinPack.versionAssetPath)
                    .bufferedReader().use { it.readText().trim() }
            }.getOrDefault("1")

            File(version, BuiltinPack.INSTALLED_MARKER).writeText("$packVersion\n")

            onProgress?.invoke("完成", 1f)
            Logger.info(TAG, "内置整合包解压完成: ${version.absolutePath}")
            true
        } catch (e: Exception) {
            Logger.error(TAG, "内置整合包解压失败", e)
            false
        }
    }
}
