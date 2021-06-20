package org.jetbrains.plugins.template

import com.intellij.json.JsonFileType
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.EditorLinePainter
import com.intellij.openapi.editor.LineExtensionInfo
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color
import java.awt.Font
import java.io.BufferedReader
import java.io.InputStreamReader

class LinePainter : EditorLinePainter() {
    private var myInit = false
    private var myError = false

    override fun getLineExtensions(project: Project, file: VirtualFile, line: Int): MutableCollection<LineExtensionInfo> {
        if (!JsonFileType.INSTANCE.equals(file.fileType)) {
            return arrayListOf()
        }
        val service = ServiceManager.getService(project, LanguageService::class.java)
        if (!myInit && !myError) {
            try {
                val rt = Runtime.getRuntime()
                val command = "yarn --cwd \"" + file.parent.path + "\" outdated"
                val process = rt.exec(arrayOf("bash", "-l", "-c", command))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var l: String? = null
                var tableStarted = false
                while (reader.readLine().also { l = it } != null) {
                    if (tableStarted) {
                        if (!l?.startsWith("Done in")!!) {
                            val pair = service.extractData(l);
                            if (pair != null) {
                                service.fillVersionData(file, pair.first, pair.second)
                            }
                        }
                    } else if (l?.startsWith("Package")!!) {
                        tableStarted = true
                    }
                }
                process.waitFor()

                myInit = true
                myError = false
            } catch (e: Throwable) {
                e.printStackTrace()
                myError = true
            }
        }
        if (!myInit) {
            return arrayListOf()
        }

        val depVersions = service.getDepVersions(file, line)
        val textAttributes = TextAttributes(getTextColor(0), null, null, EffectType.BOXED, Font.ITALIC)
        val textAttributesImp = TextAttributes(getTextColor(1), null, null, EffectType.BOXED, Font.ITALIC)
        if (depVersions.current.isBlank() || depVersions.current.contains(" ")) {
            return arrayListOf()
        }
        if (depVersions.current == depVersions.latest) {
            return arrayListOf(LineExtensionInfo("\uD83D\uDC4D", textAttributes))
        }
        val resolved = depVersions.wanted == depVersions.latest
        return arrayListOf(
                LineExtensionInfo("  ${depVersions.latest} ", textAttributesImp),
                LineExtensionInfo("  ${if (depVersions.wanted != depVersions.current) "Resolved: ${depVersions.wanted} ${if (resolved) " \uD83D\uDC4D " else "" }" else ""} ", textAttributes),
        )
    }

    private fun getTextColor(important: Int?): Color? {
        return when {
            important!! > 1 -> EditorColorsManager.getInstance().globalScheme.getAttributes(CodeInsightColors.ERRORS_ATTRIBUTES)?.effectColor
            important == 1 -> EditorColorsManager.getInstance().globalScheme.getAttributes(CodeInsightColors.WARNINGS_ATTRIBUTES)?.errorStripeColor
            else -> EditorColorsManager.getInstance().globalScheme.getAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT)?.foregroundColor
        }
    }
}
