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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.renderer

import android.content.Context
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.utils.logging.Logger
import org.apache.commons.io.FileUtils
import java.io.File

/**
 * 内置启动器背景。
 *
 * 做法与 Java 运行时的解压逻辑一致：随包携带一份背景图，
 * 首次启动（或版本号变化）时释放到背景目录，之后不再覆盖，
 * 所以用户自己换过背景之后不会被重新刷回去。
 */
object BuiltinBackground {
    /** 资源文件名，放在 assets 根目录 */
    private const val ASSET_NAME = "builtin_background.jpg"

    /** 记录已释放版本的标记文件，位于背景目录内 */
    private const val MARKER_NAME = ".builtin_version"

    /** 背景版本号。改图时把这个数字加一，老用户也会被更新到新图 */
    private const val VERSION = "1"

    /**
     * 把内置背景释放到 [PathManager.FILE_LAUNCHER_BACKGROUND]。
     *
     * 该函数应当在 `PathManager.DIR_FILES_PRIVATE` 赋值之后调用。
     */
    fun apply(context: Context) {
        runCatching {
            val target = PathManager.FILE_LAUNCHER_BACKGROUND
            val parent = target.parentFile ?: return
            val marker = File(parent, MARKER_NAME)

            //已经是这个版本的背景了，直接跳过
            if (target.exists() && marker.exists() && marker.readText().trim() == VERSION) {
                return
            }

            parent.mkdirs()

            context.assets.open(ASSET_NAME).use { input ->
                //先清掉旧文件与旧标记，避免中途失败留下半截文件
                FileUtils.deleteQuietly(target)
                FileUtils.deleteQuietly(marker)
                target.outputStream().use { output -> input.copyTo(output) }
            }

            marker.writeText(VERSION)
            Logger.info("BuiltinBackground", "已释放内置背景到 ${target.absolutePath}")
        }.onFailure {
            Logger.error("BuiltinBackground", "释放内置背景失败", it)
        }
    }
}
