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
 * versions/fabric-loader-0.19.3-1.21.11/fabric-loader-0.19.3-1.21.11.json
 * libraries/...   （含原版库与 Fabric 加载器所需的 8 个库）
 * assets/indexes/...
 * assets/objects/...
 * ```
 * 首次启动时整体解包到用户选择的游戏目录，此后安装流程校验到本地文件
 * 均已存在且校验通过，无需联网下载。
 *
 * 关于 Fabric：内置的加载器 Json 与加载器库是「离线装出 Fabric 端」的关键。
 * 只内置原版是不够的 —— 安装流程必须拿到加载器 Json 才能确定 mainClass 与
 * 额外依赖，缺少它就会退化成纯原版。
 */
object BuiltinAssets {

    /** 内置游戏资源在 assets 中的文件名（tar.xz 格式） */
    const val ASSET_NAME = "builtin_mc.tar.xz"

    /** 内置游戏资源对应的原版版本号 */
    const val VERSION_NAME = "1.21.11"

    /** 内置游戏资源对应的资源索引名（原版 1.21.11 的 assetIndex id） */
    private const val ASSET_INDEX_NAME = "29"

    /**
     * 随包内置的模组加载器版本 Json 文件名
     *
     * 与 [VERSION_NAME] 一起参与标记文件命名：只要其中任意一个变化，
     * 老用户升级 APP 后就会重新解包，避免「旧包不包含加载器资源」的问题。
     */
    private val BUILTIN_LOADER_JSONS = listOf("fabric-loader-0.19.3-1.21.11.json")

    /**
     * 释放标记文件，带上版本号与加载器版本，便于换包后重新释放
     */
    private fun markerFile(gameHome: String): File {
        val loaderTag = BUILTIN_LOADER_JSONS.joinToString("_") { it.removeSuffix(".json") }
        return File(gameHome, ".builtin_mc_${VERSION_NAME}_$loaderTag")
    }

    /**
     * 是否已经释放过
     */
    fun isReleased(gameHome: String = getGameHome()): Boolean = markerFile(gameHome).exists()

    /**
     * 读取内置的模组加载器版本 Json
     *
     * 供加载器安装流程在离线场景下取用，避免再去联网拉取那份只有几 KB
     * 但却是整个离线流程唯一硬依赖的 Json。
     *
     * @param fileName 期望的 Json 文件名（例如 fabric-loader-0.19.3-1.21.11.json）
     * @return 文件内容；内置资源中没有对应文件时返回 null
     */
    fun findLocalLoaderJson(
        fileName: String,
        gameHome: String = getGameHome()
    ): String? {
        if (fileName !in BUILTIN_LOADER_JSONS) return null
        val plain = fileName.removeSuffix(".json")
        val file = File(getVersionsHome(gameHome), "$plain/$fileName")
        if (!file.isFile) {
            Logger.warning(TAG, "内置加载器 Json 不存在: ${file.absolutePath}")
            return null
        }
        return runCatching { file.readText() }
            .onFailure { Logger.error(TAG, "读取内置加载器 Json 失败: ${file.absolutePath}", it) }
            .getOrNull()
    }

    /**
     * 内置加载器的版本信息
     * @param loaderVersion 加载器版本号，如 0.19.3
     * @param gameVersion 对应的游戏版本，如 1.21.11
     * @param jsonName 版本 Json 文件名
     */
    data class BuiltinLoader(
        val loaderVersion: String,
        val gameVersion: String,
        val jsonName: String
    )

    /**
     * 列出内置加载器信息
     *
     * 供加载器解析流程在联网获取版本列表失败时兜底使用。整合包清单里其实
     * 已经写明了加载器版本（例如 fabric-loader = 0.19.3），只要这个版本与
     * 内置资源一致，就完全没有必要再去联网查一次版本列表 —— 联网那一步
     * 恰恰是离线环境下最容易失败、且失败后表现为「装成纯原版」的环节。
     */
    fun builtinLoaders(gameHome: String = getGameHome()): List<BuiltinLoader> {
        return BUILTIN_LOADER_JSONS.mapNotNull { jsonName ->
            val plain = jsonName.removeSuffix(".json")
            //命名规则固定为 <loader>-<loaderVersion>-<gameVersion>
            val parts = plain.split("-")
            if (parts.size < 4) {
                Logger.warning(TAG, "内置加载器 Json 命名不符合预期: $jsonName")
                return@mapNotNull null
            }
            val gameVersion = parts.last()
            val loaderVersion = parts[parts.size - 2]
            BuiltinLoader(
                loaderVersion = loaderVersion,
                gameVersion = gameVersion,
                jsonName = jsonName
            )
        }
    }

    /**
     * 按版本号查找内置加载器
     * @param loaderVersion 期望的加载器版本
     * @return 命中的内置加载器；未内置该版本时返回 null
     */
    fun findBuiltinLoader(loaderVersion: String, gameHome: String = getGameHome()): BuiltinLoader? {
        return builtinLoaders(gameHome).firstOrNull { it.loaderVersion == loaderVersion }
    }


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
        //
        //注意这里必须把加载器的 Json 也列为关键文件：它是离线装出 Fabric 端的
        //必要条件，如果解包后缺失，会导致安装退化成纯原版，且现象很隐蔽
        //（安装流程照常跑完，只是加载器没装上）。宁可在这里失败暴露问题。
        val criticalFiles = buildList {
            add(File(getVersionsHome(gameHome), "$VERSION_NAME/$VERSION_NAME.jar"))
            add(File(getVersionsHome(gameHome), "$VERSION_NAME/$VERSION_NAME.json"))
            add(File(getLibrariesHome(gameHome)))
            add(File(getAssetsHome(gameHome), "indexes/$ASSET_INDEX_NAME.json"))
            BUILTIN_LOADER_JSONS.forEach { jsonName ->
                val plain = jsonName.removeSuffix(".json")
                add(File(getVersionsHome(gameHome), "$plain/$jsonName"))
            }
        }
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
        marker.writeText(
            buildString {
                appendLine("released at ${System.currentTimeMillis()}")
                appendLine("version=$VERSION_NAME")
                appendLine("loaders=${BUILTIN_LOADER_JSONS.joinToString(",")}")
            }
        )
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
