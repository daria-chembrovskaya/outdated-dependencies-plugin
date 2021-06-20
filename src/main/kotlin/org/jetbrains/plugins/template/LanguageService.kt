package org.jetbrains.plugins.template

import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.impl.JsonRecursiveElementVisitor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class LanguageService(project: Project) : com.intellij.openapi.Disposable {
    companion object {
        private val KEY = Key<DocumentListener>("outdated-deps-listener")
    }

    private val myProject = project;
    private val psiManager: PsiManager = PsiManager.getInstance(project)
    private val psiDocumentManager: PsiDocumentManager = PsiDocumentManager.getInstance(project)

    private val failedSize = DepVersions("", "", "", null)
    private val evalQueue = MergingUpdateQueue("outdated-deps-eval", 300, true, null, this, null, false).setRestartTimerOnAdd(true)

    data class DepVersions(val current: String, val wanted: String, val latest: String, val url: String?)

    private val cache = ConcurrentHashMap<String, MutableMap<String, DepVersions>>()

    fun extractData(l: String?): Pair<String, DepVersions>? {
        val parts = l!!.split(' ').map { it -> it.trim() }.filter { it -> it.isNotEmpty() }
        if (parts.size < 3) return null
        val url = if (parts.size > 5) parts[5] else null
        return Pair("\"" + parts[0] + "\"", DepVersions(parts[1], parts[2], parts[3], url));
    }

    fun fillVersionData(file: VirtualFile, packageName: String, versions: DepVersions) {
        var map: MutableMap<String, DepVersions>? = cache[file.path]
        if (map.isNullOrEmpty()) {
            map = createNewMap()
            cache.putIfAbsent(file.path, map)
        }
        map[packageName] = versions
    }

    fun getDepVersions(file: VirtualFile, line: Int): DepVersions {
        val psiFile = psiManager.findFile(file) ?: return failedSize
        val document = psiDocumentManager.getDocument(psiFile) ?: return failedSize
        EditorFactory.getInstance().getEditors(document).forEach { editor ->
            if (KEY.get(editor) == null) {
                val listener = createListener(file)
                document.addDocumentListener(listener, (editor as EditorImpl).disposable)
                KEY.set(editor, listener)
            }
        }

        var map: MutableMap<String, DepVersions>? = cache[file.path]
        if (map.isNullOrEmpty()) {
            map = createNewMap()
            cache.putIfAbsent(file.path, map)
            updateDepVersions(document, file, map)
        }
        val name = document.text.split('\n')[line].split(':')[0].trim()
        return map.getOrDefault(name, failedSize)
    }

    private fun createNewMap() = ConcurrentHashMap<String, DepVersions>()

    private fun createListener(file: VirtualFile): DocumentListener {
        return object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val document = event.document

                val map: MutableMap<String, DepVersions>?
                if (event.oldFragment.contains('\n') || event.newFragment.contains('\n')) {
                    map = createNewMap()
                    cache[file.path] = map
                } else {
                    map = cache[file.path] ?: return
/*                    val line = document.getLineNumber(event.offset)
                    map.remove(line)*/
                }

                updateDepVersions(document, file, map)
            }
        }
    }

    private fun updateDepVersions(document: Document, file: VirtualFile, map: MutableMap<String, DepVersions>) {
        evalQueue.queue(object : Update(document) {
            override fun run() {
                processDepVersions(document, file, map)
            }

            override fun canEat(update: Update?): Boolean {
                return Arrays.equals(update?.equalityObjects, equalityObjects)
            }
        })
    }

    private fun processDepVersions(document: Document, file: VirtualFile, map: MutableMap<String, DepVersions>) {
        ApplicationManager.getApplication().runReadAction {
            val psiFile = PsiDocumentManager.getInstance(myProject).getPsiFile(document)
            psiFile?.accept(object : JsonRecursiveElementVisitor() {
                override fun visitProperty(o: JsonProperty) {
                    super.visitProperty(o)
/*                    if (myData[o.nameElement.text] != null) {
                        map[o.nameElement.text] to myData[o.nameElement.text]
                    }*/
                }
            })
        }
    }

    override fun dispose() {
        TODO("Not yet implemented")
    }
}

