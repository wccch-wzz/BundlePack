/*
 * Zalith Launcher 2 - 内置资源解包扩展
 *
 * 本文件为第三方修改版新增功能，用于解压随 APK 打包的 xz 压缩资源包。
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.game.download.modpack.autoinstall

import com.movtery.zalithlauncher.utils.logging.Logger
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

private const val TAG = "XzExtractor"

/**
 * .tar.xz 解包工具
 *
 * 依赖 Apache Commons Compress（tar）与 XZ for Java（xz），
 * 两者均已在项目依赖中提供。
 */
object XzExtractor {

    /**
     * 解压 tar.xz 流到目标目录
     *
     * @param input 压缩流
     * @param targetDir 解压目标目录
     * @param onProgress 已解压字节数回调
     */
    fun extract(
        input: InputStream,
        targetDir: File,
        onProgress: ((Long) -> Unit)? = null
    ) {
        var total = 0L
        val normalizedTarget = targetDir.canonicalFile

        XZInputStream(BufferedInputStream(input, 64 * 1024)).use { xz ->
            TarArchiveInputStream(xz).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    val outFile = File(normalizedTarget, entry.name)

                    //阻止路径穿越（../ 逃逸）
                    if (!outFile.canonicalPath.startsWith(normalizedTarget.path + File.separator)) {
                        Logger.warning(TAG, "跳过越界条目: ${entry.name}")
                        continue
                    }

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                        continue
                    }

                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val read = tar.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            total += read
                        }
                    }
                    onProgress?.invoke(total)
                }
            }
        }
        Logger.info(TAG, "解包完成，共写出 $total 字节到 ${normalizedTarget.absolutePath}")
    }
}
