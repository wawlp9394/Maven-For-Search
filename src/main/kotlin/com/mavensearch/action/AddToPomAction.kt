package com.mavensearch.action

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.mavensearch.model.Artifact

/**
 * 将依赖添加到项目的 pom.xml
 *
 * 在 <dependencies> 标签内插入新的 <dependency> 元素
 */
class AddToPomAction(
    private val project: Project,
    private val artifact: Artifact
) {
    private val log = logger<AddToPomAction>()

    /**
     * 执行添加
     * @param callback 回调消息 (EDT 线程)
     */
    fun performAdd(callback: (String) -> Unit) {
        val pomFile = findPomXml()
        if (pomFile == null) {
            ApplicationManager.getApplication().invokeLater {
                callback("No pom.xml found in project root")
            }
            return
        }

        val version = artifact.latestVersion
        if (version.isEmpty()) {
            ApplicationManager.getApplication().invokeLater {
                callback("No version specified for ${artifact.artifactId}")
            }
            return
        }

        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction.writeCommandAction(project).withName("Add Maven Dependency").run<Exception> {
                try {
                    val psiFile = PsiManager.getInstance(project).findFile(pomFile)
                    if (psiFile == null) {
                        callback("Cannot read pom.xml")
                        return@run
                    }

                    val xmlText = psiFile.text
                    val dependencyXml = artifact.toDependencyXml(version)

                    // 查找 <dependencies> 标签
                    val depsIndex = xmlText.indexOf("<dependencies>")
                    if (depsIndex < 0) {
                        // 没有 <dependencies> 标签,在 <project> 内添加
                        val projectEndIndex = xmlText.lastIndexOf("</project>")
                        if (projectEndIndex < 0) {
                            callback("Invalid pom.xml: no </project> tag")
                            return@run
                        }
                        val newContent = buildString {
                            append(xmlText.substring(0, projectEndIndex))
                            append("    <dependencies>\n")
                            append("        ")
                            append(dependencyXml.replace("\n", "\n        "))
                            append("\n    </dependencies>\n\n")
                            append(xmlText.substring(projectEndIndex))
                        }
                        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(pomFile)
                        document?.setText(newContent)
                    } else {
                        // 在 <dependencies> 后插入
                        val insertPos = depsIndex + "<dependencies>".length
                        val newContent = buildString {
                            append(xmlText.substring(0, insertPos))
                            append("\n        ")
                            append(dependencyXml.replace("\n", "\n        "))
                            append(xmlText.substring(insertPos))
                        }
                        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(pomFile)
                        document?.setText(newContent)
                    }

                    // 打开 pom.xml
                    FileEditorManager.getInstance(project).openFile(pomFile, true)
                    callback("Added: ${artifact.groupId}:${artifact.artifactId}:$version")
                } catch (e: Exception) {
                    log.warn("Failed to add dependency to pom.xml", e)
                    callback("Failed: ${e.message}")
                }
            }
        }
    }

    /** 查找项目根目录的 pom.xml */
    private fun findPomXml(): VirtualFile? {
        val projectDir = project.guessProjectDir() ?: return null
        return projectDir.findChild("pom.xml")
    }
}
