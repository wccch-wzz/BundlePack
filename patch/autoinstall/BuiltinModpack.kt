/*
 * Zalith Launcher 2 - 内置整合包自动安装扩展
 *
 * 本文件为第三方修改版新增功能，用于在应用首次启动时
 * 自动安装随包内置的整合包，实现"安装即玩"。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.game.download.modpack.autoinstall

import android.content.Context
import androidx.core.net.toUri
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "BuiltinModpack"

/**
 * 内置整合包管理器
 *
 * 随 APK 打包的整合包以 fileName 为名存放在 assets 根目录，
 * 首次启动时释放到私有目录，并交由 ModpackImporter 完成安装。
 */
object BuiltinModpack {

    /**
     * 内置整合包在 assets 中的文件名（tar.xz 包裹，内含单个 .mrpack）
     */
    const val ASSET_NAME = "builtin_modpack.tar.xz"

    /** 解包后 .mrpack 的文件名 */
    private const val INNER_NAME = "builtin_modpack.mrpack"

    /**
     * 内置整合包的展示/安装名称，也是安装后的版本名
     */
    const val PACK_DISPLAY_NAME = "1.21.11-Fabric 0.19.2"

    /**
     * 内置资源里 Fabric 版本目录名
     *
     * 必须与 builtin_mc.tar.xz 内的目录名、以及整合包清单声明的加载器版本一致，
     * 否则直装时会找不到版本 Json。
     */
    const val FABRIC_VERSION_DIR = "fabric-loader-0.19.3-1.21.11"

    /**
     * 安装标记文件名，用于判断是否已经自动安装过
     */
    private const val MARKER_NAME = ".builtin_modpack_installed"

    /**
     * 释放到本地后的整合包文件路径
     */
    private fun releasedFile(context: Context): File {
        return File(PathManager.DIR_FILES_PRIVATE, "builtin_pack/$INNER_NAME")
    }
    private fun markerFile(context: Context): File {
        return File(PathManager.DIR_FILES_PRIVATE, MARKER_NAME)
    }

    /**
     * 是否已经完成过内置整合包的自动安装
     */
    fun isInstalled(context: Context): Boolean = markerFile(context).exists()

    /**
     * 标记为已安装（非挂起函数，可从任意回调中调用）
     */
    fun markInstalled(context: Context) {
        runCatching {
            markerFile(context).also { it.parentFile?.mkdirs() }.writeText(
                "installed at ${System.currentTimeMillis()}\nname=$PACK_DISPLAY_NAME\n"
            )
        }.onFailure {
            Logger.warning(TAG, "无法写入安装标记文件", it)
        }
    }

    /**
     * assets 中是否存在内置整合包
     */
    fun exists(context: Context): Boolean {
        return runCatching {
            context.assets.openFd(ASSET_NAME).use { true }
        }.getOrDefault(false)
    }

    /**
     * 将内置整合包从 assets 释放到私有目录
     * @return 释放后的文件，失败返回 null
     */
    suspend fun release(context: Context): File? = withContext(Dispatchers.IO) {
        val target = releasedFile(context)
        try {
            //已释放过则直接复用，避免重复解包 400MB
            if (target.exists() && target.length() > 0) {
                Logger.info(TAG, "复用已释放的内置整合包: ${target.absolutePath}")
                return@withContext target
            }

            target.parentFile?.mkdirs()
            //assets 中以 tar.xz 存放（体积约为 mrpack 的 92%），
            //流式解包后再清理，过程中不会同时占用两份完整副本
            context.assets.open(ASSET_NAME).use { input ->
                XzExtractor.extract(input, target.parentFile!!)
            }

            if (!target.exists()) {
                Logger.error(TAG, "解包后未找到 ${target.absolutePath}", null)
                return@withContext null
            }
            Logger.info(TAG, "已释放内置整合包: ${target.absolutePath} (${target.length()} 字节)")
            target
        } catch (e: Exception) {
            Logger.error(TAG, "释放内置整合包失败", e)
            null
        }
    }

    /**
     * 清理已释放的整合包，安装完成后调用可回收约 454MB 空间
     */
    fun cleanup(context: Context) {
        runCatching {
            releasedFile(context).takeIf { it.exists() }?.delete()
        }.onFailure {
            Logger.warning(TAG, "清理内置整合包失败", it)
        }
    }

    /**
     * 取得可直接交给 ModpackImporter 的 Uri
     */
    fun uriOf(file: File) = file.toUri()
}
