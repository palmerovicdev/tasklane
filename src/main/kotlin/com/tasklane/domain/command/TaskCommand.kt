package com.tasklane.domain.command

import com.tasklane.domain.model.AnchorMove
import com.tasklane.domain.model.CodeAnchor
import com.tasklane.domain.model.PriorityId
import com.tasklane.domain.model.RepoKey
import com.tasklane.domain.model.RepositoryRef
import com.tasklane.domain.model.StateId
import com.tasklane.domain.model.Task
import com.tasklane.domain.model.TaskId
import com.tasklane.domain.model.TasklaneConfig
import java.time.Instant
import java.util.Optional

/**
 * Todas las mutaciones posibles del modelo. `sealed` a propósito: añadir un caso
 * nuevo rompe la compilación del reducer en vez de pasar desapercibido.
 *
 * **Ya no hay un comando de carga.** Hasta la Fase 2 abrir un repositorio emitía un
 * `Loaded` con su lista entera de tareas, que es lo que metía el corpus en el modelo.
 * Desde la Fase 3 las tareas ya están en el almacén y la ventana las pide por páginas;
 * lo único que se «carga» es la migración del `tasks.xml` de una versión anterior, y
 * ésa escribe en la base, no en el modelo.
 *
 * Se divide en dos familias porque desde la Fase 2 no todo cabe en un repositorio:
 * cambiar la configuración o reasignar un estado que se borra afectan a **todos**
 * los repositorios cargados a la vez. Sacar `repo` de la raíz obliga a que cada
 * comando diga a qué alcance pertenece, y es lo que le permite al servicio saber
 * qué ficheros tiene que reescribir sin adivinarlo.
 */
sealed interface TaskCommand {

    // ------------------------------------------------------ acotados a un repo

    data class Create(
        override val repo: RepoKey,
        val body: String,
        val stateId: StateId? = null,
        val priorityId: PriorityId? = null,
        val tags: List<String> = emptyList(),
        val dueDate: Instant? = null,
        val anchors: List<CodeAnchor> = emptyList(),
        /**
         * El id de la tarea nueva, cuando quien la crea necesita saberlo: las
         * herramientas MCP (2.12.0) le devuelven al agente la tarea que acaba de crear.
         * `null` == uno nuevo, que es lo que quiere todo lo demás.
         */
        val id: TaskId? = null,
    ) : RepoScoped

    /**
     * Varias tareas nuevas **de una vez**: una transacción y un snapshot (2.8.0).
     *
     * Lo pide importar los comentarios TODO de un proyecto, que son decenas o cientos
     * de golpe. Mandar un [Create] por cada uno serían otras tantas transacciones y
     * repintados, que es la lección de [Batch] aplicada a crear. No cabe dentro de
     * [Batch] porque aquél compone comandos sobre tareas que ya existen, y aquí no hay
     * ninguna que leer antes.
     *
     * Cada [tasks] se planifica como su [Create] suelto —mismas normalizaciones—, en su
     * orden, y todas en [repo]: el de cada una se ignora.
     */
    data class CreateMany(override val repo: RepoKey, val tasks: List<Create>) : RepoScoped

    data class UpdateBody(override val repo: RepoKey, val id: TaskId, val body: String) : RepoScoped

    /**
     * Lo que el diálogo de edición devuelve al aceptar: **todo a la vez**.
     *
     * Existe porque no hacerlo era caro de dos maneras distintas. `TaskEditDialog`
     * puede cambiar seis cosas de una tarea, y hasta la Fase 1 se enviaban seis
     * comandos seguidos desde el EDT. Eso son seis copias de la lista del repositorio
     * —a 100.000 tareas, 3,6 MB de basura por edición— y, peor, **seis snapshots**, o
     * sea hasta seis repintados del árbol por un solo clic en *Guardar*.
     *
     * Los comandos sueltos siguen existiendo y siguen haciendo falta: *Move To*,
     * el distintivo de prioridad y el marcador cambian **una** cosa, y para eso son.
     * Lo que no tenía sentido era componer una edición con ellos.
     *
     * Cada campo es opcional con el sentido de «no lo toques»: [dueDate] es
     * `Optional`-como-envoltorio y no un `Instant?` porque aquí `null` significaría
     * las dos cosas a la vez —«no lo toques» y «quítale la fecha»— y quitar una fecha
     * de vencimiento tiene que poder pedirse.
     */
    data class UpdateTask(
        override val repo: RepoKey,
        val id: TaskId,
        val body: String? = null,
        val stateId: StateId? = null,
        val priorityId: PriorityId? = null,
        val tags: List<String>? = null,
        val dueDate: Optional<Instant>? = null,
        val anchors: List<CodeAnchor>? = null,
    ) : RepoScoped

    data class ChangeState(override val repo: RepoKey, val id: TaskId, val stateId: StateId) : RepoScoped

    data class ChangePriority(override val repo: RepoKey, val id: TaskId, val priorityId: PriorityId) : RepoScoped

    /** Fija o quita la fecha de vencimiento. `null` la quita. */
    data class SetDueDate(override val repo: RepoKey, val id: TaskId, val dueDate: Instant?) : RepoScoped

    /** Alterna la marca de la tarea. */
    data class ToggleBookmark(override val repo: RepoKey, val id: TaskId) : RepoScoped

    /** Sustituye las etiquetas de una tarea por las indicadas. */
    data class SetTags(override val repo: RepoKey, val id: TaskId, val tags: List<String>) : RepoScoped

    /**
     * Sustituye las anclas de código. Sólo se quitan, nunca se añaden por aquí: se
     * capturan en el editor al crear la tarea, que es el único sitio que sabe dónde
     * estaba el cursor.
     */
    data class SetAnchors(
        override val repo: RepoKey,
        val id: TaskId,
        val anchors: List<CodeAnchor>,
    ) : RepoScoped

    /**
     * Marca o desmarca la casilla `- [ ]` del cuerpo que está en [offset] (2.11.0). Es lo
     * que hace pulsar una casilla en la tarjeta. Si ahí ya no hay una casilla —el cuerpo
     * cambió entre pintar y pulsar— no hace nada, en vez de escribir una `x` a ciegas.
     */
    data class ToggleCheck(override val repo: RepoKey, val id: TaskId, val offset: Int) : RepoScoped

    /**
     * Lleva una tarea a otro sitio de la lista de un estado con orden manual (2.11.0):
     * entre [above] y [below], las que quedarán justo encima y justo debajo. Cualquiera
     * puede faltar —el principio o el final de lo que se ve—. No toca `updatedAt`, como
     * marcar: reordenar no es editar. Ver `Mutation.Place`.
     */
    data class Move(override val repo: RepoKey, val id: TaskId, val above: TaskId?, val below: TaskId?) : RepoScoped

    /** Alterna entre el estado terminal y el estado por defecto. */
    data class ToggleComplete(override val repo: RepoKey, val id: TaskId) : RepoScoped

    data class Delete(override val repo: RepoKey, val ids: List<TaskId>) : RepoScoped

    /**
     * Saca del modelo un repositorio entero. Lo emite «Exportar y quitar», y sólo
     * después de que sus tareas estén en el portapapeles.
     *
     * No es un `Delete` con todas las tareas: eso dejaría el repositorio en el
     * snapshot con la lista vacía, lo marcaría como sucio y volvería a escribir un
     * `tasks.xml` justo detrás de haberlo borrado del disco.
     */
    data class ForgetRepo(override val repo: RepoKey) : RepoScoped

    // ----------------------------------------------------- alcance de proyecto

    /**
     * Devuelve tareas borradas **tal como estaban** (2.9.0): el mismo id, las mismas
     * fechas, el mismo orden. Es el *Undo* del aviso que sale al borrar.
     *
     * No es un [Create]: una tarea recreada nacería con otro id y con la fecha de hoy,
     * y se iría del grupo de fecha donde estaba, que es justo lo contrario de deshacer.
     *
     * De alcance de proyecto porque cada tarea lleva su repositorio: una selección
     * borrada buscando en todos puede cruzar varios. Las que ya existen se dejan como
     * están, así que deshacer dos veces no duplica nada.
     */
    data class Restore(val tasks: List<Task>) : TaskCommand

    /**
     * Entró una configuración nueva —del fichero, de los ajustes o de la plantilla—.
     * Re-normaliza todas las tareas: las huérfanas se remapean y las que vuelven a
     * tener su estado disponible lo recuperan.
     */
    data class ConfigChanged(val config: TasklaneConfig) : TaskCommand

    /**
     * Mueve a [to] todo lo que estaba en [from]. Es el comando que respalda el
     * diálogo de borrado de un estado: la reasignación es explícita y del usuario,
     * no el remapeo automático al estado por defecto.
     */
    data class ReassignState(val from: StateId, val to: StateId) : TaskCommand

    data class ReassignPriority(val from: PriorityId, val to: PriorityId) : TaskCommand

    /**
     * Un fichero o un directorio cambió de sitio dentro del IDE, y las anclas de [ids] se
     * van con él (2.13.0). Lo emite `AnchorFiles` al ver el evento de mover o renombrar;
     * [ids] son las tareas que apuntaban debajo de algún `from`, que el almacén encuentra
     * por `anchor_by_path`.
     *
     * De alcance de proyecto porque un fichero no sabe de repositorios: una tarea de
     * `backend` puede apuntar a uno de `frontend`, y renombrarlo tiene que arrastrarla.
     * Ver [AnchorMove].
     */
    data class RelinkAnchors(val ids: List<TaskId>, val moves: List<AnchorMove>) : TaskCommand

    /**
     * Pasar un estado a orden manual sin que la lista se mueva (2.11.0): su orden empieza
     * siendo el que se estaba viendo. Se emite justo antes de cambiar la configuración.
     */
    data class SeedManualOrder(val state: StateId) : TaskCommand

    /**
     * El registro acaba de publicar un catálogo nuevo —arranque, evento de VCS o
     * cambio de profundidad—.
     *
     * Fija también qué repositorio queda activo: si el que estaba desapareció, se
     * cae al primero de la lista en vez de dejar el árbol apuntando a una clave que
     * ya no existe.
     */
    data class RepositoriesChanged(val repositories: List<RepositoryRef>) : TaskCommand

    /**
     * El usuario cambió de repositorio en el selector. No se valida contra el
     * catálogo a propósito: al abrir el proyecto se restaura la selección guardada
     * antes de que la detección haya terminado.
     */
    data class SelectRepo(val repo: RepoKey) : TaskCommand

    /**
     * Rellena `completedAt` desde `updatedAt` en las tareas de [states] que aún no
     * lo tengan. Se emite una sola vez, cuando el usuario marca como terminal un
     * estado que ya tenía tareas dentro y acepta el ofrecimiento.
     */
    data class BackfillCompletedAt(val states: Set<StateId>) : TaskCommand

    /**
     * Muchos comandos sobre tareas que ya existen, **como si fueran uno**: una
     * transacción y un snapshot.
     *
     * Es la operación masiva de la Fase 5 (`docs/plan-escala.md`, Fase 5.3). Mover de
     * estado, completar, marcar o cambiar la prioridad de una selección mandaba **un
     * comando por fila**, y cada uno era su transacción y su snapshot: quinientas filas
     * seleccionadas eran quinientos repintados encolados. Es exactamente la lección de
     * [UpdateTask] en la Fase 1 —seis comandos por un clic en *Guardar*—, un orden de
     * magnitud más arriba.
     *
     * **Se describe con los comandos sueltos, y no con un comando nuevo por operación.**
     * Así la semántica de mover cuarenta tareas es, por construcción, la de mover cada
     * una: sellar `completedAt` al entrar en un estado terminal, no tocar `updatedAt` al
     * marcar, quitar la marca de aparcada al elegir a mano. Un test fija que el lote da
     * **las mismas tareas** que los comandos uno detrás de otro.
     *
     * Alcance de proyecto y no de repositorio porque la selección puede cruzarlos:
     * buscando en todos, una misma lista mezcla filas de varios. Cada comando de dentro
     * sigue diciendo el suyo.
     *
     * Sólo comandos que **tocan tareas existentes**: [Create] y [ForgetRepo] no caben,
     * porque ni tienen una fila que leer antes ni se componen con nada.
     */
    data class Batch(val commands: List<RepoScoped>) : TaskCommand {
        init {
            require(commands.none { it is Create || it is ForgetRepo }) {
                "Batch sólo compone comandos sobre tareas existentes"
            }
        }
    }
}

/** Comando acotado a un único repositorio. */
sealed interface RepoScoped : TaskCommand {
    val repo: RepoKey
}
