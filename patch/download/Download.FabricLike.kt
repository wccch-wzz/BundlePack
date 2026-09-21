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

package com.movtery.zalithlauncher.game.download.game.fabric

import com.movtery.zalithlauncher.coroutine.Task
import com.movtery.zalithlauncher.game.addons.mirror.mapBMCLMirrorUrls
import com.movtery.zalithlauncher.game.addons.modloader.fabriclike.FabricLikeVersion
import com.movtery.zalithlauncher.game.download.modpack.autoinstall.BuiltinAssets
import com.movtery.zalithlauncher.utils.file.ensureParentDirectory
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.network.fetchStringFromUrls
import kotlinx.coroutines.Dispatchers
import java.io.File

private const val TAG = "Download.FabricLike"

const val FABRIC_LIKE_DOWNLOAD_ID = "Download.FabricLike"

fun getFabricLikeDownloadTask(
    fabricLikeVersion: FabricLikeVersion,
    tempVersionJson: File
): Task {
    return Task.runTask(
        id = FABRIC_LIKE_DOWNLOAD_ID,
        dispatcher = Dispatchers.IO,
        task = {
            //优先使用随包内置的加载器 Json，实现完全离线安装
            //
            //背景：本方法原本无条件联网下载 loader Json，是整套离线安装流程里
            //唯一剩下的网络硬依赖。只要这一步失败，外层就无法拿到加载器信息，
            //最终安装出来的会是纯原版而不是 Fabric 端。
            //
            //修复：内置资源里已经放了同样内容的 Json，这里先查本地；
            //命中就直接用，彻底不产生网络请求。未命中才回落到原来的联网逻辑。
            val localJson = BuiltinAssets.findLocalLoaderJson(tempVersionJson.name)
            val loaderJson = if (localJson != null) {
                Logger.info(TAG, "使用内置的加载器 Json: ${tempVersionJson.name}")
                localJson
            } else {
                Logger.info(TAG, "内置资源中没有 ${tempVersionJson.name}，改为联网获取")
                fetchStringFromUrls(fabricLikeVersion.loaderJsonUrl.mapBMCLMirrorUrls())
            }
            tempVersionJson
                .ensureParentDirectory()
                .writeText(loaderJson)
        }
    )
}