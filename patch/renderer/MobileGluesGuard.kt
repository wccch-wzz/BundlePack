/*
 * Zalith Launcher 2 - MobileGlues 渲染器守卫
 *
 * 本文件为第三方修改版新增功能。
 *
 * 作用：在本版本启动之前，强制确认当前渲染器是 MobileGlues。
 *   - 是        → 什么都不做
 *   - 不是      → 自动切换为 MobileGlues
 *   - 没安装    → 弹窗提示，底下附一个「安装」按钮
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
 *   架构         arm64-v8a
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
import androidx.core.content.FileProvider
import com.movtery.zalithlauncher.game.plugin.renderer_v2.RendererV2PluginManager
import com.movtery.zalithlauncher.game.version.installed.VersionConfig
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
     * 下载页地址
     *
     * TODO: 待用户提供具体链接后替换。
     * 当前指向官方发布页，保证链接可用。
     */
    const val DOWNLOAD_URL = "https://github.com/MobileGL-Dev/MobileGlues-release/releases"

    /**
     * 插件在 assets 中的文件名（可选）
     *
     * 若随包放入了 MobileGlues 的 APK，检测不到时会尝试直接调起安装器；
     * 没放则退化为打开下载页。
     */
    const val ASSET_APK_NAME = "MobileGlues.apk"

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
     * assets 里是否随包放了 MobileGlues 的 APK
     */
    fun hasBundledApk(context: Context): Boolean = runCatching {
        context.assets.open(ASSET_APK_NAME).close()
        true
    }.getOrDefault(false)

    /**
     * 发起安装
     *
     * 优先用随包的 APK 直接调起系统安装器；
     * 没随包就打开下载页让用户自己下。
     *
     * 注意：从 Android 8 起，安装第三方 APK 需要用户在系统设置里
     * 手动授予「安装未知应用」权限，App 无法绕过这一步。
     * 因此调起安装器后，用户仍需要点一次确认。
     */
    fun startInstall(context: Context) {
        if (hasBundledApk(context)) {
            if (installBundledApk(context)) return
            Logger.warning(TAG, "随包 APK 安装未能调起，改为打开下载页")
        } else {
            Logger.info(TAG, "未随包内置 MobileGlues APK，打开下载页")
        }
        openDownloadPage(context)
    }

    /**
     * 把 assets 里的 MobileGlues.apk 释放到缓存目录并调起安装器
     *
     * @return 是否成功调起
     */
    private fun installBundledApk(context: Context): Boolean = runCatching {
        val apk = File(context.cacheDir, ASSET_APK_NAME)
        context.assets.open(ASSET_APK_NAME).use { input ->
            apk.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
        }

        val uri = FileProvider.getUriForFile(
            context,
            //与 AndroidManifest 里 FileProvider 的 authorities 保持一致
            "${context.packageName}.provider",
            apk
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        Logger.info(TAG, "已调起 MobileGlues 安装器: ${apk.absolutePath}")
        true
    }.onFailure { e ->
        Logger.error(TAG, "调起随包 APK 安装失败", e)
    }.getOrDefault(false)

    /**
     * 打开下载页
     */
    fun openDownloadPage(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure { e ->
            if (e is ActivityNotFoundException) {
                Logger.error(TAG, "没有可用的浏览器打开 $DOWNLOAD_URL", e)
            } else {
                Logger.error(TAG, "打开下载页失败", e)
            }
        }
    }
}
