package com.tasklane.ui.editor

import com.intellij.codeInsight.lookup.LookupEvent
import com.intellij.codeInsight.lookup.LookupListener
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import com.tasklane.TasklaneBundle
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.TasklaneConfig
import com.tasklane.domain.text.InlineTagCompletion
import com.tasklane.domain.text.TypedWord
import com.tasklane.service.TaskService

/** Shows repository tags in the existing multiline editor without replacing its Markdown document. */
internal class InlineTagLookup(
    private val project: Project,
    private val repo: RepoKey,
    private val config: TasklaneConfig,
    private val body: MarkdownField,
    private val tags: TagChipsField,
    private val parent: Disposable,
) {
    private var generation = 0

    init {
        body.component.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                generation++
                if (event.oldLength != 0 || event.newLength != 1) return
                val char = event.newFragment[0]
                if (!(char == '#' || char.isLetterOrDigit() || char in "-_/")) return
                val version = generation
                ApplicationManager.getApplication().invokeLater(
                    { suggest(version) }, ModalityState.stateForComponent(body.component),
                )
            }
        })
    }

    private fun suggest(version: Int) {
        if (version != generation || Disposer.isDisposed(parent) || project.isDisposed) return
        val editor = body.component.editor ?: return
        if (!editor.contentComponent.isFocusOwner || editor.caretModel.caretCount != 1 ||
            LookupManager.getActiveLookup(editor) != null) return
        val prefix = InlineTagCompletion.at(editor.document.text, editor.caretModel.offset) ?: return
        val taken = tags.tags
        ApplicationManager.getApplication().executeOnPooledThread {
            val counts = TaskService.getInstance(project).tagCounts(repo)
            val candidates = InlineTagCompletion.suggestions(counts, "", taken)
                .sortedByDescending { it.tag.startsWith(prefix.typed, ignoreCase = true) }
            ApplicationManager.getApplication().invokeLater({
                if (version != generation || Disposer.isDisposed(parent) || project.isDisposed || editor.isDisposed ||
                    !editor.contentComponent.isFocusOwner || editor.caretModel.caretCount != 1 ||
                    LookupManager.getActiveLookup(editor) != null ||
                    InlineTagCompletion.at(editor.document.text, editor.caretModel.offset) != prefix || candidates.isEmpty()) return@invokeLater
                val items = candidates.map { count ->
                    var item = LookupElementBuilder.create(count.tag)
                        .withPresentableText("#${count.tag}")
                        .withTypeText(TasklaneBundle.message("dialog.task.tags.count", count.tasks), true)
                    config.tagColor(count.tag)?.let { color ->
                        item = item.withIcon(ColorIcon(JBUI.scale(8), JBColor(color.light, color.dark)))
                    }
                    item
                }.toTypedArray()
                LookupManager.getInstance(project).showLookup(editor, items, prefix.typed)?.addLookupListener(object : LookupListener {
                    override fun beforeItemSelected(event: LookupEvent): Boolean = false
                    override fun itemSelected(event: LookupEvent) {
                        val typed = event.completionChar
                        if (InlineTagCompletion.isTypedCharacter(typed)) {
                            val offset = editor.caretModel.offset
                            WriteCommandAction.writeCommandAction(project)
                                .withName(TasklaneBundle.message("dialog.task.tags.inline.command"))
                                .run<RuntimeException> {
                                    editor.document.insertString(offset, typed.toString())
                                    editor.caretModel.moveToOffset(offset + 1)
                                }
                            return
                        }
                        val tag = event.item?.lookupString ?: return
                        val text = editor.document.text
                        val current = InlineTagCompletion.at(text, editor.caretModel.offset) ?: return
                        if (current.start != prefix.start) return
                        val (start, end) = TypedWord.cut(text, current.start, current.end)
                        WriteCommandAction.writeCommandAction(project)
                            .withName(TasklaneBundle.message("dialog.task.tags.inline.command"))
                            .run<RuntimeException> { editor.document.deleteString(start, end) }
                        tags.addTag(tag)
                    }
                })
            }, ModalityState.stateForComponent(body.component))
        }
    }
}
