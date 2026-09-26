package com.tasklane.ui.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.ContentFactory
import com.tasklane.data.config.TasklaneWorkspaceService
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
import java.awt.CardLayout
import javax.swing.JPanel

/**
 * El contenido de la tool window: un panel por estado, uno visible cada vez.
 *
 * **Un solo `Content`, no uno por estado.** Con varios, el IDE pintaba su barra de
 * pestañas en el header, mezclada con el título de la ventana, el selector de
 * repositorio y el filtro; los recuentos quedaban perdidos ahí arriba. Ahora los
 * estados son una fila dentro del panel, encima del buscador ([StateTabRow]), y el
 * header vuelve a ser sólo cabecera. Se pierde la navegación `Alt+←/→` que ponía el
 * `ContentManager`; es un cambio pedido y consciente, anotado en
 * `docs/plan-rediseno.md`.
 *
 * Cambiar de estado no reconstruye nada: los paneles viven en un [CardLayout] y
 * conservan su árbol, su selección y sus grupos plegados. La identidad es el
 * [StateId] —nunca la posición ni el nombre, que son lo que el usuario puede
 * cambiar—, así que renombrar o reordenar estados en *Settings* tampoco tira nada.
 */
internal class TasklaneWindow(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : Disposable {

    private val workspace = TasklaneWorkspaceService.getInstance(project)
    private val cards = CardLayout()
    private val root = JPanel(cards)
    private val panels = mutableMapOf<StateId, TasklanePanel>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var selected: StateId? = null

    init {
        val service = TaskService.getInstance(project)

        // Primera pasada síncrona: `createToolWindowContent` ya está en el EDT y así
        // la ventana nunca llega a pintarse vacía.
        val first = service.snapshot.value
        sync(first.config.states)
        // El estado que estaba abierto la última vez. Si ya no existe, el primero.
        select(workspace.selectedState?.let(::StateId), focus = false)
        syncTitle(first.repositories)

        val content = ContentFactory.getInstance().createContent(root, "", false)
        content.isCloseable = false
        // Para que quien sólo tiene la tool window —el widget de la barra de estado— pueda
        // pedirle un estado. Ver `TaskReveal.showState`.
        content.putUserData(KEY, this)
        Disposer.register(content, this)
        toolWindow.contentManager.addContent(content)

        // La cabecera se instala una sola vez: el selector se esconde solo mientras
        // haya un único repositorio, y reinstalar las acciones haría parpadear todo.
        toolWindow.setTitleActions(listOf(RepoSelectorAction(project), ViewFilterAction(project)))

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
     * Con un solo repositorio su nombre va al título: la ventana sigue diciendo de
     * quién son las tareas sin gastar un control en una lista de un elemento. Con
     * varios, el título estorba —lo dice el selector— y se limpia.
     */
    private fun syncTitle(repositories: List<RepositoryRef>) {
        if (toolWindow.isDisposed) return
        toolWindow.setTitle(repositories.singleOrNull()?.displayName.orEmpty())
    }

    private fun sync(states: List<TaskState>) {
        if (toolWindow.isDisposed) return

        // 1. Estados que ya no existen. Su panel se dispone: con él se va su scope.
        val gone = panels.keys - states.map { it.id }.toSet()
        for (id in gone) {
            panels.remove(id)?.let { panel ->
                root.remove(panel)
                Disposer.dispose(panel)
            }
        }

        // 2. Estados nuevos. El orden de las tarjetas da igual —sólo se ve una—; el
        //    de la fila de pestañas lo pone la configuración en cada repintado.
        for (state in states) {
            panels.getOrPut(state.id) {
                TasklanePanel(project, state.id, onSelectState = { select(it) }).also { panel ->
                    // Colgado de la ventana: cerrar la tool window cancela el scope de
                    // corrutinas de cada panel sin tener que recorrerlos a mano.
                    Disposer.register(this, panel)
                    root.add(panel, state.id.value)
                }
            }
        }

        // 3. Si el estado visible desapareció, el primero que quede.
        if (selected == null || selected !in panels) select(states.firstOrNull()?.id, focus = false)
        root.revalidate()
        root.repaint()
    }

    /**
     * Enseña un estado. [focus] manda el cursor a la lista, que es lo que se quiere
     * al pulsar una pestaña y lo que **no** se quiere al abrir la ventana: robar el
     * foco al arrancar el IDE es de las cosas que más molestan de un plugin.
     */
    fun select(id: StateId?, focus: Boolean = true) {
        val target = id?.takeIf { it in panels } ?: panels.keys.firstOrNull() ?: return
        selected = target
        workspace.selectedState = target.value
        cards.show(root, target.value)
        if (focus) panels[target]?.focusTree()
    }

    override fun dispose() {
        scope.cancel()
        panels.clear()
    }

    companion object {
        internal val KEY: Key<TasklaneWindow> = Key.create("Tasklane.window")
    }
}
