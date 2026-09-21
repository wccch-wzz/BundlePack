/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.download.modpack.install

import com.movtery.zalithlauncher.game.addons.modloader.ModLoader
import com.movtery.zalithlauncher.game.addons.modloader.fabriclike.fabric.FabricVersion
import com.movtery.zalithlauncher.game.addons.modloader.fabriclike.fabric.FabricVersions
import com.movtery.zalithlauncher.game.addons.modloader.fabriclike.quilt.QuiltVersions
import com.movtery.zalithlauncher.game.addons.modloader.forgelike.forge.ForgeVersions
import com.movtery.zalithlauncher.game.addons.modloader.forgelike.neoforge.NeoForgeVersions
import com.movtery.zalithlauncher.game.download.game.GameDownloadInfo
import com.movtery.zalithlauncher.game.download.modpack.autoinstall.BuiltinAssets
import com.movtery.zalithlauncher.utils.logging.Logger

private const val TAG = "ModPackInfo"

/**
 * 整合包信息
 * @param name 整合包名称
 * @param summary 整合包的简介（可用到版本描述上）
 * @param ram 整合包推荐分配的内存
 * @param files 整合包所有需要下载的模组
 * @param loaders 整合包需要安装的模组加载器
 * @param gameVersion 整合包需要的游戏版本
 */
data class ModPackInfo(
    val name: String,
    val summary: String? = null,
    val ram: Int? = null,
    val files: List<ModFile>,
    val loaders: List<Pair<ModLoader, String>>,
    val gameVersion: String
)

/**
 * 模组加载器解析匹配任务
 * @return 构建好的游戏下载安装信息
 */
suspend fun ModPackInfo.retrieveLoaderTask(
    targetVersionName: String
): GameDownloadInfo {
    var gameInfo = GameDownloadInfo(
        gameVersion = gameVersion,
        customVersionName = targetVersionName
    )

    //匹配目标加载器版本，获取详细版本信息
    loaders.forEach { pair ->
        pair.retrieveLoader(
            gameVersion = gameVersion,
            gameInfo = gameInfo,
            pasteGameInfo = { newInfo ->
                gameInfo = newInfo
            }
        )
    }

    return gameInfo
}

/**
 * 模组加载器解析匹配，并粘贴游戏下载信息
 * @param gameVersion 当前游戏版本
 * @param pasteGameInfo 将识别到的模组加载器版本贴回信息类
 */
suspend fun Pair<ModLoader, String>.retrieveLoader(
    gameVersion: String,
    gameInfo: GameDownloadInfo,
    pasteGameInfo: (GameDownloadInfo) -> Unit
) {
    val (loader, version) = this
    when (loader) {
        ModLoader.FORGE -> {
            ForgeVersions.fetchForgeList(gameVersion)?.find {
                it.versionName == version
            }?.let { forgeVersion ->
                pasteGameInfo(gameInfo.copy(forge = forgeVersion))
            }
        }
        ModLoader.NEOFORGE -> {
            NeoForgeVersions.fetchNeoForgeList(gameVersion = gameVersion)?.find {
                it.versionName == version
            }?.let { neoforgeVersion ->
                pasteGameInfo(gameInfo.copy(neoforge = neoforgeVersion))
            }
        }
        ModLoader.FABRIC -> {
            //优先用随包内置的加载器信息
            //
            //背景：这里原本只走 FabricVersions.fetchFabricLoaderList() 联网查版本列表，
            //是离线安装流程里真正的头号硬依赖。一旦这一步拿不到数据（无网 / 镜像不通），
            //下面的 ?.let 就不会执行，gameInfo.fabric 保持为 null，
            //最终结果是「整合包装完了但加载器没装」——用户看到的就是纯原版，
            //而且全程不报错，非常隐蔽。
            //
            //修复：整合包清单已经写明了加载器版本号，只要它与内置资源里的版本一致，
            //直接构造 FabricVersion 即可，完全不需要联网。联网仅作为兜底保留。
            val builtin = BuiltinAssets.findBuiltinLoader(version)
            val fabricVersion = if (builtin != null && builtin.gameVersion == gameVersion) {
                Logger.info(TAG, "使用内置的 Fabric 加载器信息: ${builtin.loaderVersion}-${builtin.gameVersion}")
                FabricVersion(
                    inherit = gameVersion,
                    version = builtin.loaderVersion,
                    stable = true
                )
            } else {
                Logger.info(TAG, "内置资源中没有 Fabric ${version}，改为联网查询")
                FabricVersions.fetchFabricLoaderList(gameVersion)?.find {
                    it.version == version
                }
            }
            fabricVersion?.let {
                pasteGameInfo(gameInfo.copy(fabric = it))
            } ?: Logger.warning(TAG, "未能解析 Fabric 加载器 $version，安装结果将是纯原版")
        }
        ModLoader.QUILT -> {
            QuiltVersions.fetchQuiltLoaderList(gameVersion)?.find {
                it.version == version
            }?.let { quiltVersion ->
                pasteGameInfo(gameInfo.copy(quilt = quiltVersion))
            }
        }
        else -> {
            //不支持
        }
    }
}