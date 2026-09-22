/*
 * Zalith Launcher 2 - MobileGlues 渲染器守卫
 *
 * 本文件为第三方修改版新增功能。
 *
 * 作用：在本版本启动之前，强制确认当前渲染器是 MobileGlues。
 *   - 是        → 什么都不做
 *   - 不是      → 自动切换为 MobileGlues
 *   - 没安装    → 弹窗提示，给出「网盘下载」与「QQ群文件」两个入口
 *
 * 为什么需要它：
 *   VersionConfig.renderer 默认是空字符串。GameLauncher 拿到空串会走
 *   Renderers.setCurrentRenderer("") 的兜底分支，而该分支选的是 renderers[0]，
 *   也就是内置列表里的第一个（GL4ES 系转译层）。
 *   在部分设备上这个转译层拿不到足够的 GL 能力，表现为
 *   "GLFW error 65542: WGL: The driver does not appear to support OpenGL"，
 *   窗口创建失败 → 闪退。
 *   MobileGlues 直接跑在宿主 GLES 3.2 上，兼容面最广，因此强制使用它。
 *
 * 关于 MobileGlues 的接入方式（依据其源码，非推测）：
 *   包名         com.fcl.plugin.mobileglues        （build.gradle.kts 的 applicationId）
 *   插件协议     meta-data "fclPlugin" = true      （旧架构，不是 fclPlugin_V2）
 *   渲染器唯一标识 就是包名本身                      （RendererPlugin.getUniqueIdentifier() = packageName）
 *   版本范围     minMCVer=1.17, maxMCVer 为空=不限
 *
 * 注意：MobileGlues 是外部独立 App，其内核 .so 位于它自己的 nativeLibraryDir，
 * 无法随本 APK 分发，必须由用户另行安装。因此这里只做「检测 + 引导下载」。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.game.renderer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.movtery.zalithlauncher.game.plugin.renderer_v2.RendererV2PluginManager
import com.movtery.zalithlauncher.game.version.installed.VersionConfig
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "MobileGluesGuard"

/**
 * MobileGlues 渲染器守卫
 */
object MobileGluesGuard {

    /**
     * MobileGlues 插件包名
     *
     * 取自 MobileGlues-plugin 的 app/build.gradle.kts: applicationId
     */
    const val PACKAGE_NAME = "com.fcl.plugin.mobileglues"

    /**
     * 网盘下载地址
     */
    const val NETDISK_URL = "https://1860524481.share.123pan.cn/123pan/wFN5vd-UxVfH"

    /**
     * QQ 群号
     */
    const val QQ_GROUP = "1104854895"

    /**
     * QQ 群资料页 scheme
     *
     * 格式取自手机 QQ 的 scheme 约定。
     * 手机上若装了 QQ 可直接跳到群资料页（群文件在右上角）。
     */
    const val QQ_GROUP_URL =
        "mqqapi://card/show_pslcard?src_type=internal&version=1" +
            "&uin=$QQ_GROUP&card_type=group&source=external"

    /**
     * 未安装 QQ 时的降级地址
     *
     * 用 qun.qq.com 的群主页，浏览器里能看到群信息与「打开QQ」入口。
     */
    const val QQ_WEB_URL = "https://qun.qq.com/"

    /**
     * 检查结果
     */
    sealed interface Result {
        /** 已经是 MobileGlues，无需处理 */
        data object AlreadyUsing : Result

        /** 不是 MobileGlues，已成功自动切换 */
        data class Switched(val from: String) : Result

        /** MobileGlues 未安装，需要提示用户 */
        data class NotInstalled(val current: String) : Result
    }

    /**
     * 强制校正渲染器
     *
     * 在本版本启动之前调用，会按需改写 [VersionConfig.renderer]。
     *
     * @param context 用于查询已安装插件
     * @param versionConfig 目标版本的配置，会被直接改写
     * @return 检查结果
     */
    suspend fun enforce(context: Context, versionConfig: VersionConfig): Result =
        withContext(Dispatchers.IO) {
            val current = versionConfig.renderer

            //已经是 MobileGlues 就直接放行
            if (current == PACKAGE_NAME) {
                Logger.info(TAG, "当前渲染器已是 MobileGlues")
                return@withContext Result.AlreadyUsing
            }

            //检查插件是否已安装并注册到渲染器列表
            if (!isMobileGluesAvailable(context)) {
                Logger.warning(TAG, "未检测到 MobileGlues 插件，当前渲染器: '$current'")
                return@withContext Result.NotInstalled(current)
            }

            //自动切换
            versionConfig.renderer = PACKAGE_NAME
            versionConfig.save()
            Logger.info(TAG, "已强制把渲染器从 '$current' 切换为 MobileGlues")

            Result.Switched(current)
        }

    /**
     * MobileGlues 是否可用
     *
     * 两条判据，满足其一即认为可用：
     *   1. 已注册进渲染器列表（正常路径）
     *   2. 系统里装了该包名（列表尚未刷新时的兜底）
     *
     * MobileGlues 是外部 APK 插件，必须被 PluginLoader 扫到并注册，
     * 才能出现在渲染器列表里。
     */
    fun isMobileGluesAvailable(context: Context?): Boolean {
        val fromScanner = runCatching {
            Renderers.getRenderers().any { it.getUniqueIdentifier() == PACKAGE_NAME } ||
                RendererV2PluginManager.getRendererList().any { it.packageName == PACKAGE_NAME }
        }.getOrDefault(false)

        if (fromScanner) return true
        if (context == null) return false

        return runCatching {
            context.packageManager.getApplicationInfo(PACKAGE_NAME, 0)
            true
        }.getOrDefault(false)
    }

    /**
     * 打开网盘下载页
     */
    fun openNetdisk(context: Context) {
        openUrl(context, NETDISK_URL, "网盘")
    }

    /**
     * 跳转到 QQ 群
     *
     * 优先用 scheme 直接唤起 QQ 并进入群资料页；
     * 没装 QQ（或有 QQ 但 scheme 被拒）时退化为打开 qun.qq.com。
     */
    fun openQqGroup(context: Context) {
        val opened = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(QQ_GROUP_URL)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        }.onFailure { e ->
            if (e is ActivityNotFoundException) {
                Logger.info(TAG, "未安装 QQ，退化为打开群主页")
            } else {
                Logger.error(TAG, "跳转 QQ 群失败", e)
            }
        }.getOrDefault(false)

        if (!opened) {
            openUrl(context, QQ_WEB_URL, "QQ群主页")
        }
    }

    /**
     * 通用外部链接打开
     */
    private fun openUrl(context: Context, url: String, label: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure { e ->
            if (e is ActivityNotFoundException) {
                Logger.error(TAG, "没有可用的应用打开$label: $url", e)
            } else {
                Logger.error(TAG, "打开${label}失败: $url", e)
            }
        }
    }
}
