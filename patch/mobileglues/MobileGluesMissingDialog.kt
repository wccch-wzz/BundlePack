/*
 * Zalith Launcher 2 - MobileGlues 缺少提示弹窗
 *
 * 本文件为第三方修改版新增功能。
 *
 * 版式参照 ui/vulkan_checker/VulkanChecker.kt：
 *   Dialog + Surface(cardColor) + 标题/正文/底部按钮行。
 *
 * 与其他弹窗的区别：这里只有一个「安装」按钮，没有取消。
 * 因为不装 MobileGlues 游戏大概率起不来，给个「取消」反而误导用户。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.ui.mobileglues

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.movtery.zalithlauncher.game.renderer.MobileGluesGuard
import com.movtery.zalithlauncher.ui.components.fadeEdge
import com.movtery.zalithlauncher.ui.components.rememberDialogMaxHeight
import com.movtery.zalithlauncher.ui.components.verticalScrollWithBar
import com.movtery.zalithlauncher.ui.theme.cardColor
import com.movtery.zalithlauncher.ui.theme.onCardColor

/**
 * MobileGlues 渲染器缺少提示
 *
 * @param onNetdisk 点击「网盘下载」
 * @param onQqGroup 点击「QQ群文件」
 */
@Composable
fun MobileGluesMissingDialog(
    onNetdisk: () -> Unit,
    onQqGroup: () -> Unit,
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            dismissOnBackPress = false,
        )
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .heightIn(max = rememberDialogMaxHeight())
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .padding(all = 6.dp)
                    .heightIn(max = (maxHeight - 12.dp).coerceAtMost(rememberDialogMaxHeight()))
                    .wrapContentHeight(),
                shape = MaterialTheme.shapes.extraLarge,
                color = cardColor(false),
                contentColor = onCardColor(),
                shadowElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(all = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "缺少渲染器 MobileGlues",
                        style = MaterialTheme.typography.headlineSmall
                    )

                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .fadeEdge(scrollState)
                            .verticalScrollWithBar(scrollState),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "没有检测到 MobileGlues 渲染器，游戏可能无法正常启动。",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = "MobileGlues 是一个外部插件，需要单独安装。" +
                                "它直接运行在设备的 OpenGL ES 上，是当前版本推荐使用的渲染器。",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = "安装后回到启动器，会自动切换为它，不需要手动设置。",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            //用内层字符串模板引用常量，避免与 Compose 的作用域混淆
                            text = "可以从网盘下载，也可以加 QQ 群 ${MobileGluesGuard.QQ_GROUP} 在群文件里获取。",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(
                            12.dp,
                            Alignment.End
                        )
                    ) {
                        FilledTonalButton(
                            modifier = Modifier.focusProperties { canFocus = false },
                            onClick = onQqGroup
                        ) {
                            Text(text = "QQ群文件下载")
                        }
                        Button(
                            modifier = Modifier.focusProperties { canFocus = false },
                            onClick = onNetdisk
                        ) {
                            Text(text = "网盘下载")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 没有 Activity 可用时的降级提示（纯文本 Toast 内容）
 */
object MobileGluesTexts {
    const val MISSING_TOAST: String =
        "缺少 MobileGlues 渲染器，请先安装：https://github.com/MobileGL-Dev/MobileGlues-release/releases"
}
