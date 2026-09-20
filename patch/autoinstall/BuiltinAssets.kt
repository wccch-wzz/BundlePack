/*
 * Zalith Launcher 2 - 内置游戏资源自动释放扩展
 *
 * 本文件为第三方修改版新增功能。随包内置的原版 Minecraft 资源
 * （client.jar / libraries / assets）以 tar.xz 形式存放在 assets 中，
 * 首次启动时解包到游戏目录，使游戏在完全离线的情况下也能完成安装，
 * 实现"安装即玩"。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.game.download.modpack.autoinstall

import android.content.Context
import com.movtery.zalithlauncher.game.path.getAssetsHome
import com.movtery.zalithlauncher.game.path.getGameHome
import com.movtery.zalithlauncher.game.path.getLibrariesHome
import com.movtery.zalithlauncher.game.path.getVersionsHome
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "BuiltinAssets"

/**
 * 内置游戏资源管理器
 *
 * assets 根目录下的 builtin_mc.tar.xz 内含：
 * ```
 * versions/1.21.11/1.21.11.jar
 * versions/1.21.11/1.21.11.json
 * libraries/...
 * assets/indexes/...
 * assets/objects/...
 * ```
 * 首次启动时整体解包到用户选择的游戏目录，此后安装流程校验到本地文件
 * 均已存在且校验通过，无需联网下载。
 */
object BuiltinAssets {

    /** 内置游戏资源在 assets 中的文件名（tar.xz 格式） */
    const val ASSET_NAME = "builtin_mc.tar.xz"

    /** 内置游戏资源对应的原版版本号 */
    const val VERSION_NAME = "1.21.11"

    /** 内置游戏资源对应的资源索引名（原版 1.21.11 的 assetIndex id） */
    private const val ASSET_INDEX_NAME = "29"

    /**
     * 释放标记文件，带上版本号，便于换包后重新释放
     */
    private fun markerFile(gameHome: String): File =
        File(gameHome, ".builtin_mc_$VERSION_NAME")

    /**
     * 是否已经释放过
     */
    fun isReleased(gameHome: String = getGameHome()): Boolean = markerFile(gameHome).exists()

    /**
     * 检查 assets 中是否随包提供了游戏资源
     */
    fun exists(context: Context): Boolean {
        return runCatching { context.assets.openFd(ASSET_NAME).use { true } }
            .getOrDefault(false)
    }

    /**
     * 将内置游戏资源解包到游戏目录
     *
     * @param onProgress 进度回调（已解压字节数），可为空
     * @return 是否释放成功
     */
    suspend fun release(
        context: Context,
        gameHome: String = getGameHome(),
        onProgress: ((Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val marker = markerFile(gameHome)
        if (marker.exists()) {
            Logger.info(TAG, "内置游戏资源已释放过，跳过")
            return@withContext true
        }
        if (!exists(context)) {
            Logger.warning(TAG, "assets 中没有 $ASSET_NAME，跳过释放")
            return@withContext false
        }

        val root = File(gameHome)
        Logger.info(TAG, "开始解包内置游戏资源 -> ${root.absolutePath}")

        try {
            root.mkdirs()
            context.assets.open(ASSET_NAME).use { input ->
                XzExtractor.extract(input, root, onProgress)
            }
        } catch (e: Exception) {
            Logger.error(TAG, "解包内置游戏资源失败", e)
            return@withContext false
        }

        //校验关键文件是否就位
        val criticalFiles = listOf(
            File(getVersionsHome(gameHome), "$VERSION_NAME/$VERSION_NAME.jar"),
            File(getVersionsHome(gameHome), "$VERSION_NAME/$VERSION_NAME.json"),
            File(getLibrariesHome(gameHome)),
            File(getAssetsHome(gameHome), "indexes/$ASSET_INDEX_NAME.json")
        )
        val missing = criticalFiles.filterNot { it.exists() }
        if (missing.isNotEmpty()) {
            Logger.error(
                TAG,
                "内置游戏资源解包不完整，缺少: ${missing.joinToString { it.absolutePath }}",
                null
            )
            return@withContext false
        }

        marker.parentFile?.mkdirs()
        marker.writeText("released at ${System.currentTimeMillis()}\nversion=$VERSION_NAME\n")
        Logger.info(TAG, "内置游戏资源解包完成")
        true
    }

    /**
     * 取得打包在 assets 中的资源包体积，用于展示
     */
    fun assetSize(context: Context): Long = runCatching {
        context.assets.openFd(ASSET_NAME).use { it.length }
    }.getOrDefault(0L)
}
