package com.mavensearch.action

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.io.HttpRequests
import com.mavensearch.config.MavenSearchSettings
import com.mavensearch.model.Artifact
import java.io.File

/**
 * 下载 jar 包到项目 lib/ 目录
 *
 * 默认下载到 ${PROJECT_DIR}/lib/, 不存在则自动创建
 */
class DownloadJarAction(
    private val project: Project,
    private val artifact: Artifact
) {
    private val log = logger<DownloadJarAction>()
    private val settings = MavenSearchSettings.getInstance()

    /**
     * 执行下载
     * @param callback 回调消息 (EDT 线程)
     */
    fun performDownload(callback: (String) -> Unit) {
        val version = artifact.latestVersion
        if (version.isEmpty()) {
            ApplicationManager.getApplication().invokeLater { callback("No version specified") }
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Downloading ${artifact.artifactId}-${version}.jar", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val url = buildJarUrl(artifact.groupId, artifact.artifactId, version)
                    val targetDir = resolveTargetDir()
                    val targetFile = File(targetDir.path, "${artifact.artifactId}-${version}.jar")

                    indicator.text = "Downloading ${artifact.artifactId}-${version}.jar..."
                    indicator.isIndeterminate = true

                    HttpRequests.request(url)
                        .userAgent("Mozilla/5.0")
                        .connectTimeout(settings.state.networkTimeoutSec * 1000)
                        .readTimeout(settings.state.networkTimeoutSec * 1000)
                        .saveToFile(targetFile, indicator)

                    // 刷新 VFS
                    ApplicationManager.getApplication().invokeLater {
                        VfsUtil.markDirtyAndRefresh(false, false, false, targetDir)
                        callback("Downloaded: ${targetFile.name}")
                    }
                } catch (e: Exception) {
                    log.warn("Failed to download jar: ${artifact.toCoordinate()}:$version", e)
                    ApplicationManager.getApplication().invokeLater {
                        callback("Download failed: ${e.message}")
                    }
                }
            }
        })
    }

    /** 构建 Maven Central jar 下载 URL */
    private fun buildJarUrl(groupId: String, artifactId: String, version: String): String {
        val groupPath = groupId.replace(".", "/")
        return "https://repo1.maven.org/maven2/$groupPath/$artifactId/$version/$artifactId-$version.jar"
    }

    /** 解析目标目录,不存在则创建 */
    private fun resolveTargetDir(): VirtualFile {
        val dirName = settings.state.jarDownloadDir.ifBlank { "lib" }
        val projectDir = project.guessProjectDir()
            ?: throw IllegalStateException("Cannot determine project directory")

        val libDir = projectDir.findFileByRelativePath(dirName)
            ?: projectDir.createChildDirectory(LocalFileSystem.getInstance(), dirName)

        return libDir
    }
}
