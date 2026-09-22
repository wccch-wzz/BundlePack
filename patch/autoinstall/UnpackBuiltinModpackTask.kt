/*
 * Zalith Launcher 2 - 内置整合包解压任务
 *
 * 本文件为第三方修改版新增功能。
 *
 * 作用：把「解压内置整合包」做成启动画面里的一个解压项，
 * 与 Java 环境、JNA 等一起在首次启动时完成。
 *
 * 为什么要放在这里：
 *   启动画面本来就是「把随包资源铺到磁盘」的地方。
 *   整合包也是随包资源，理应在这里一次铺齐，
 *   而不是等进了主界面再补一次安装。
 *   放在这里的好处：
 *     1. 与 Java 解压共用同一个进度界面，用户预期一致
 *     2. 铺完即可直接启动，主界面不需要再等
 *     3. 失败时能与其它解压项一样被统一感知
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.game.download.modpack.autoinstall

import android.content.Context
import com.movtery.zalithlauncher.components.AbstractUnpackTask
import com.movtery.zalithlauncher.components.InstallableItem
import com.movtery.zalithlauncher.game.path.getGameHome
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File

private const val TAG = "UnpackBuiltinModpackTask"

/**
 * 内置整合包解压任务
 *
 * 把内置的本体、Fabric 版本别名、整合包 overrides 一次性铺进游戏目录。
 */
class UnpackBuiltinModpackTask(
    private val context: Context
) : AbstractUnpackTask() {

    /**
     * 本次解压涉及的全部输出目录
     *
     * 用于判断是否真的需要重跑，以及重跑前清理。
     */
    private val versionDir: File
        get() = BuiltinDirectInstaller.versionDir(getGameHome())

    private val gameHome: File
        get() = File(getGameHome())

    override fun checkState(): InstallableItem.State {
        //assets 里没有内置资源 → 这项不适用
        if (!BuiltinAssets.exists(context) || !BuiltinModpack.exists(context)) {
            Logger.info(TAG, "APK 内未打包内置资源，跳过")
            return InstallableItem.State.NOT_EXISTS
        }

        //标记在 → 已经铺过
        if (BuiltinDirectInstaller.isInstalled(getGameHome())) {
            Logger.info(TAG, "内置整合包此前已解压，跳过")
            return InstallableItem.State.FINISHED
        }

        //版本目录有内容但没标记 → 上次没铺完，需要重来
        if (versionDir.exists() && versionDir.listFiles()?.isNotEmpty() == true) {
            Logger.warning(TAG, "版本目录已存在但缺少完成标记，将重新解压")
            return InstallableItem.State.PENDING
        }

        return InstallableItem.State.NOT_STARTED
    }

    override suspend fun run() {
        updateMessage("正在解压内置整合包")

        val ok = BuiltinDirectInstaller.install(
            context = context,
            gameHome = getGameHome()
        ) { stage, ratio ->
            //把阶段描述与百分比拼成一行，供解压界面展示
            updateMessage("$stage ${(ratio * 100).toInt()}%")
        }

        updateMessage(null)

        if (!ok) {
            throw IllegalStateException("内置整合包解压失败，详见日志")
        }
        Logger.info(TAG, "内置整合包解压完成")
    }
}
