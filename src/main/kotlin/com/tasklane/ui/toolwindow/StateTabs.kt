package com.tasklane.ui.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.tasklane.domain.model.RepositoryRef
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.TaskState
import com.tasklane.service.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mantiene una pestaña del `ContentManager` por cada estado configurado.
 *
 * Son `Content`s de la tool window y no un `JBTabbedPane` interno porque así la
 * barra de pestañas, `Alt+←/→` y «Show tabs in one row» salen del propio IDE sin
 * escribir nada. Ver `docs/architecture.html` §5.
 *
 * Reconfigurar estados reconstruye las pestañas **preservando el panel** de cada
 * estado que sobrevive: renombrar o reordenar no debe tirar el árbol, su selección
 * ni sus grupos plegados. La identidad es el [StateId], nunca la posición ni el
 * nombre — que son justo las dos cosas que el usuario puede cambiar.
 *
 * Es también quien pone la cabecera de la ventana: el selector de repositorio, que
 * se esconde solo cuando no hay más que uno, y en ese caso el nombre del repositorio
 * en el título.
 */
internal class StateTabs(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : Disposable {

    private val contents = mutableMapOf<StateId, Content>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        val service = TaskService.getInstance(project)

        // Primera pasada síncrona: `createToolWindowContent` ya está en el EDT y así
        // la ventana nunca llega a pintarse sin pestañas.
        sync(service.snapshot.value.config.states)
        // El selector se instala una sola vez y se esconde solo mientras haya un
        // único repositorio: reinstalarlo en cada cambio haría parpadear la cabecera.
        toolWindow.setTitleActions(listOf(RepoSelectorAction(project)))
        syncTitle(service.snapshot.value.repositories)

        scope.launch {
            service.snapshot
                .map { it.config.states }
                .distinctUntilChanged()
                .collect { states -> withContext(Dispatchers.EDT) { sync(states) } }
        }

        scope.launch {
            service.snapshot
                .map { it.repositories }
                .distinctUntilChanged()
                .collect { repositories -> withContext(Dispatchers.EDT) { syncTitle(repositories) } }
        }
    }

    /**
     * Con un solo repositorio el selector desaparece y su nombre pasa al título: la
     * ventana sigue diciendo de quién son las tareas sin gastar un control en una
     * lista de un elemento. Con varios, el título estorba —lo dice el selector— y se
     * limpia.
     */
    private fun syncTitle(repositories: List<RepositoryRef>) {
        if (toolWindow.isDisposed) return
        toolWindow.setTitle(repositories.singleOrNull()?.displayName.orEmpty())
    }

    private fun sync(states: List<TaskState>) {
        if (toolWindow.isDisposed) return
        val manager = toolWindow.contentManager
        if (manager.isDisposed) return

        val selectedBefore = manager.selectedContent?.let { selected ->
            contents.entries.firstOrNull { it.value === selected }?.key
        }

        // 1. Estados que ya no existen. Aquí sí se dispone: el panel se va con ellos.
        val gone = contents.keys - states.map { it.id }.toSet()
        for (id in gone) contents.remove(id)?.let { manager.removeContent(it, true) }

        // 2. Estados nuevos.
        for (state in states) {
            contents.getOrPut(state.id) { createContent(state) }
        }

        // 3. Nombres. Renombrar un estado no toca su panel, sólo la etiqueta.
        for (state in states) contents[state.id]?.displayName = state.name

        // 4. Orden. `ContentManager` no sabe reordenar, así que se sueltan las
        //    pestañas SIN disponerlas y se vuelven a colocar; los paneles siguen vivos.
        val desired = states.mapNotNull { contents[it.id] }
        if (manager.contents.toList() != desired) {
            manager.removeAllContents(false)
            desired.forEach(manager::addContent)
        }

        // 5. Selección por StateId. Si el estado seleccionado desapareció, la primera.
        val target = selectedBefore?.let { contents[it] } ?: desired.firstOrNull()
        target?.let { manager.setSelectedContent(it) }
    }

    private fun createContent(state: TaskState): Content {
        val panel = TasklanePanel(project, state.id)
        val content = ContentFactory.getInstance().createContent(panel, state.name, false)
        content.isCloseable = false
        // El panel muere con su pestaña, no con la tool window: así borrar un estado
        // cancela su scope de corrutinas en el momento.
        Disposer.register(content, panel)
        return content
    }

    override fun dispose() {
        scope.cancel()
        contents.clear()
    }
}
