package com.tasklane.data.sqlite

/**
 * El esquema de `tasklane.db`, y el porqué de cada decisión que no se lee sola.
 *
 * Es la traducción del `docs/plan-escala.md` §2.4 a lo que la implementación acabó
 * necesitando. Lo que **no** cambió del plan: WAL, `user_version` en el sitio de
 * `TasksCodec.CURRENT_VERSION`, `priority_rank` y `state_terminal` desnormalizados,
 * paginación por *keyset* y FTS5 con `unicode61 remove_diacritics 2`. Lo que sí
 * cambió está anotado abajo, caso por caso.
 *
 * ## 1. Una fecha de orden, no cuatro índices por columna-ancla
 *
 * El plan pedía un índice por cada fecha de la que puede colgar la agrupación
 * —`created_at`, `updated_at`, `completed_at`, `due_date`— porque `DateGrouper.anchorOf`
 * elige una según la configuración del estado. Cuatro índices con la tupla de orden
 * completa son ~200 MB a un millón de filas, y tres de ellos no se tocan nunca.
 *
 * Aquí se aplica un paso más el propio truco del plan —desnormalizar lo que el índice
 * necesita para ordenar—: [SORT_DATE] guarda **ya resuelta** la fecha del anclaje del
 * estado en el que está la tarea. Un índice en vez de cuatro, y la agrupación por fecha
 * pasa a ser un rango contiguo sobre él. Se recalcula al escribir la tarea —que es
 * cuando puede cambiar de estado— y con un `UPDATE` masivo cuando cambia la
 * configuración, igual que `priority_rank`.
 *
 * ## 2. Ninguna columna es anulable
 *
 * Una fecha ausente se escribe [NO_DATE] y no `NULL`. Dos motivos, y el segundo es el
 * que manda: enlazar `null` es el único punto de la API de `org.jetbrains.sqlite` cuyo
 * comportamiento no fija ningún test del spike, y la comparación de tuplas del keyset
 * —`(sort_date, seq) < (?, ?)`— con un `NULL` dentro devuelve `NULL`, o sea **descarta
 * la fila**: una tarea sin fecha desaparecería de su propia lista a partir de la
 * segunda página. Con un centinela, la aritmética de orden funciona sin `COALESCE` y
 * «sin fecha» queda al final del orden descendente, que es donde `DateGroup.Undated`
 * la pone.
 *
 * ## 3. `seq` explícito como rowid
 *
 * `task_fts` es una tabla de **contenido externo** (`content = 'task'`): guarda el
 * índice invertido pero no una segunda copia del cuerpo, que a un millón de tareas
 * serían 2 GB duplicados. El precio es que la unión FTS↔tarea es el rowid, y el rowid
 * implícito de una tabla **se renumera en un `VACUUM`** salvo que haya un
 * `INTEGER PRIMARY KEY` declarado. De ahí [SEQ]: sin él, un `VACUUM` —que el propio
 * IDE puede disparar -- dejaría la búsqueda apuntando a tareas equivocadas.
 *
 * ## 4. La tupla de orden, repetida en `tag`
 *
 * Agrupar por etiqueta es lo único que no se puede resolver con la tabla `task`: una
 * tarea sale bajo **todas** sus etiquetas, así que la lista de un grupo vive en la
 * tabla de unión. Si `tag` sólo tuviera `(task_id, tag)`, paginar un grupo obligaría a
 * unir con `task` y ordenar en memoria — un `TEMP B-TREE` sobre todas las tareas de esa
 * etiqueta, que es exactamente lo que esta fase vino a quitar.
 *
 * Por eso `tag` repite la tupla de orden y los dos campos que miran los filtros de
 * vista. Cuestan disco y cuestan mantenerlos —el camino de escritura reescribe las
 * filas de etiqueta de la tarea, y las operaciones masivas tienen que tocar las dos
 * tablas—, y a cambio un grupo de etiqueta se pagina por índice como cualquier otro.
 */
internal object TaskSchema {

    /**
     * Lo que sustituye a `TasksCodec.CURRENT_VERSION`, con la misma promesa: una base
     * escrita por una versión **posterior** del plugin se abre en solo lectura y se
     * avisa, en vez de degradarla escribiéndola con un esquema viejo.
     *
     * Renombrar o borrar una columna que una versión anterior lea rompería esa promesa en
     * el sentido contrario, así que no se hace.
     *
     * **La Fase 4 NO la sube, y merece la pena decir por qué.** Añade dos tablas —`blob`
     * y `chore`— y la primera reacción fue ponerla a 2. El efecto de hacerlo es concreto:
     * una versión anterior del plugin abriendo ese fichero lo vería «del futuro» y
     * **dejaría el proyecto entero en solo lectura**. Es decir, se bloquearía editar
     * tareas para proteger dos tablas cuyo contenido es *derivado*: `blob` lo reconstruye
     * entero la reconciliación del §4.3 a partir del disco, y `chore` son tres fechas de
     * mantenimiento. Proteger lo recomputable a costa de lo irreemplazable es el reparto
     * al revés.
     *
     * La regla, entonces, es ésta: **se sube la versión cuando una versión anterior
     * escribiendo perdería datos del usuario que no se pueden reconstruir** —una columna
     * nueva de la tarea que el códec viejo no escribiría, una tabla que él no mantiene y
     * que nadie sabría rehacer—. No se sube por contabilidad que se puede volver a
     * calcular. Lo que puede pasar si alguien vuelve a la 2.0 y sigue trabajando es que la
     * tabla `blob` se quede corta; la reconciliación siguiente la pone al día, que es
     * exactamente para lo que existe.
     *
     * Las migraciones, suban versión o no, sólo pueden ser **aditivas**: el
     * `CREATE TABLE IF NOT EXISTS` de [DDL] pone las tablas nuevas al abrir, sin una línea
     * de migración. El día que haga falta **una columna nueva en una tabla que ya
     * existe**, esto se acaba: `IF NOT EXISTS` no la añade, y habrá que escribir el
     * `ALTER TABLE ... DEFAULT` correspondiente aquí al lado.
     */
    const val VERSION = 1

    /**
     * «Esta fecha no existe.» Ver la nota 2 de arriba.
     *
     * `Long.MIN_VALUE` y no `0` ni `-1`: cero es 1970 y una fecha real, y lo que hace
     * falta es un valor que quede por debajo de **cualquier** fecha representable, para
     * que el orden descendente lo mande al final sin excepciones.
     */
    const val NO_DATE: Long = Long.MIN_VALUE

    /** El rowid estable al que se ancla `task_fts`. Ver la nota 3. */
    const val SEQ = "seq"

    /**
     * Lo que cierra la tupla del orden.
     *
     * Es el `id` y no el [SEQ] —que sería más barato— por una razón que no es de
     * rendimiento: `MemoryPager` ordena las mismas tareas en Kotlin y **tiene que
     * llegar al mismo resultado**, o buscar y no buscar darían dos listas distintas con
     * las mismas filas. El `id` está en el modelo; el rowid, no. Es además lo que dice
     * el §2.4 del plan.
     */
    const val TIE = "id"

    /**
     * La fecha del anclaje del estado, ya resuelta. Ver la nota 1.
     *
     * **Nunca está vacía**, ni siquiera cuando el anclaje no existe: una tarea sin
     * completar en un estado que agrupa por fecha de completado ordena por
     * `updated_at`, que es exactamente lo que hace el comparador de `MemoryPager`
     * (`anchorOf(...) ?: updatedAt`). Que caiga en el grupo «sin fecha» lo dice
     * [UNDATED], que es un dato distinto: **dónde** va la fila y **por qué** ordena así
     * no tienen por qué salir de la misma columna, y mezclarlos hacía que las tareas sin
     * fecha salieran en orden alfabético de identificador.
     */
    const val SORT_DATE = "sort_date"

    /** Si la fecha del anclaje no existía. Es lo que reparte `DateGroup.Undated`. */
    const val UNDATED = "undated"

    /**
     * El orden natural de la lista, tal y como lo describe `MemoryPager.build`:
     * marcadas primero, luego prioridad, y dentro de la misma prioridad lo más
     * reciente arriba. [SEQ] cierra la tupla para que dos tareas con la misma fecha
     * al milisegundo no queden una a cada lado de un corte de página.
     */
    const val ORDER_BY = "bookmarked DESC, priority_rank DESC, $SORT_DATE DESC, $TIE DESC"

    /**
     * Las pragmas de la conexión de escritura.
     *
     * `journal_mode = WAL` es lo que hace que un lector no espere al escritor —y lo que
     * borra el `.bak` que `TaskFileStore` copiaba en **cada** volcado—. `synchronous =
     * NORMAL` es la elección del §2.4: a prueba de que se caiga el IDE, con una ventana
     * mínima ante un corte de luz, y sin un `fsync` por transacción que se notaría en
     * el debounce de 500 ms.
     *
     * `cache_size` va en páginas negativas, que es como SQLite lee «kibibytes»: -32000
     * son 32 MB, el tope del §3.1.
     */
    val PRAGMAS = listOf(
        "PRAGMA journal_mode = WAL",
        "PRAGMA synchronous = NORMAL",
        "PRAGMA foreign_keys = ON",
        "PRAGMA cache_size = -32000",
        "PRAGMA temp_store = MEMORY",
    )

    /** Las de la conexión de lectura: las que no escriben nada. */
    val READ_PRAGMAS = listOf(
        "PRAGMA foreign_keys = ON",
        "PRAGMA cache_size = -16000",
        "PRAGMA temp_store = MEMORY",
    )

    val DDL: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS task (
          seq            INTEGER PRIMARY KEY,
          id             TEXT    NOT NULL,
          repo           TEXT    NOT NULL,
          title          TEXT    NOT NULL,
          body           TEXT    NOT NULL,
          tags_text      TEXT    NOT NULL,
          files_text     TEXT    NOT NULL,
          state          TEXT    NOT NULL,
          priority       TEXT    NOT NULL,
          priority_rank  INTEGER NOT NULL,
          state_terminal INTEGER NOT NULL,
          sort_date      INTEGER NOT NULL,
          undated        INTEGER NOT NULL,
          ord            INTEGER NOT NULL,
          created_at     INTEGER NOT NULL,
          updated_at     INTEGER NOT NULL,
          completed_at   INTEGER NOT NULL,
          due_date       INTEGER NOT NULL,
          bookmarked     INTEGER NOT NULL,
          has_link       INTEGER NOT NULL,
          has_image      INTEGER NOT NULL,
          has_anchor     INTEGER NOT NULL,
          has_tag        INTEGER NOT NULL,
          extra          TEXT    NOT NULL
        )
        """,
        "CREATE UNIQUE INDEX IF NOT EXISTS task_by_id ON task(id)",
        // El índice que sostiene la lista. La cola `completed_at, due_date` no ordena
        // nada: está para que los filtros de vista se resuelvan DENTRO del índice. Sin
        // ella, filtrar «sin cerrar» sobre un millón de filas haría un millón de saltos
        // a la tabla para leer una columna.
        """
        CREATE INDEX IF NOT EXISTS task_board
            ON task(repo, state, bookmarked DESC, priority_rank DESC, sort_date DESC, id DESC,
                    undated, completed_at, due_date)
        """,
        // El índice de las cabeceras: dónde empieza el siguiente grupo de fecha
        // (`max(sort_date) < corte`, un salto) y cuántas hay dentro (un rango contiguo).
        // Cubre también el filtro de vencidas.
        """
        CREATE INDEX IF NOT EXISTS task_date
            ON task(repo, state, sort_date, undated, bookmarked, completed_at, due_date)
        """,
        "CREATE INDEX IF NOT EXISTS task_by_priority ON task(repo, priority)",
        // PARCIAL otra vez, y por el mismo motivo. Agrupando por etiqueta hay un cajón
        // de «sin etiqueta», y buscar lo que NO esta en la tabla de union obligaria a
        // recorrer el estado entero descartando lo que si lo esta. Este indice contiene
        // exactamente ese cajon, y no ocupa nada en un proyecto donde todo lleva
        // etiqueta — que es cuando el cajon esta vacio.
        """
        CREATE INDEX IF NOT EXISTS task_untagged
            ON task(repo, state, bookmarked DESC, priority_rank DESC, sort_date DESC, id DESC,
                    undated, completed_at, due_date)
         WHERE has_tag = 0
        """,
        // `max(ord)` de un repositorio, que es todo lo que `nextOrder` necesita saber.
        // Un salto de índice en vez del `maxOfOrNull` sobre la lista entera que la Fase 1
        // sustituyo por un cache en el snapshot: ahora ni cache ni recorrido.
        "CREATE INDEX IF NOT EXISTS task_ord ON task(repo, ord)",
        // PARCIAL, y ahi esta todo. Solo contiene las tareas «aparcadas»: las que
        // apuntan a un estado o una prioridad que ya no existe y guardan el original en
        // `extra`. Son un punado entre un millon, y este indice es lo que hace que
        // ConfigChanged —que se emite en CADA arranque y en cada paso por los ajustes—
        // cueste lo que cuestan ellas y no lo que cuesta el proyecto.
        "CREATE INDEX IF NOT EXISTS task_parked ON task(repo, state) WHERE extra <> ''",
        """
        CREATE TABLE IF NOT EXISTS tag (
          task_id       TEXT    NOT NULL REFERENCES task(id) ON DELETE CASCADE,
          tag           TEXT    NOT NULL,
          repo          TEXT    NOT NULL,
          state         TEXT    NOT NULL,
          bookmarked    INTEGER NOT NULL,
          priority_rank INTEGER NOT NULL,
          sort_date     INTEGER NOT NULL,
          undated       INTEGER NOT NULL,
          completed_at  INTEGER NOT NULL,
          due_date      INTEGER NOT NULL,
          PRIMARY KEY (task_id, tag)
        )
        """,
        """
        CREATE INDEX IF NOT EXISTS tag_board
            ON tag(repo, state, tag, bookmarked DESC, priority_rank DESC, sort_date DESC, task_id DESC,
                   undated, completed_at, due_date)
        """,
        """
        CREATE TABLE IF NOT EXISTS anchor (
          task_id TEXT    NOT NULL REFERENCES task(id) ON DELETE CASCADE,
          pos     INTEGER NOT NULL,
          path    TEXT    NOT NULL,
          line    INTEGER NOT NULL,
          col     INTEGER NOT NULL,
          text    TEXT    NOT NULL,
          PRIMARY KEY (task_id, pos)
        )
        """,
        // Lo que hace que abrir un fichero no cueste lo mismo que abrir el proyecto:
        // `AnchorMarkers` pregunta por ruta en vez de recorrer el modelo (§3.6).
        "CREATE INDEX IF NOT EXISTS anchor_by_path ON anchor(path, task_id)",
        // Las referencias a imágenes: quién nombra a quién. La mitad del recolector que
        // la Fase 3 necesitaba para dejar de mirar el corpus en memoria; la otra mitad
        // —qué hay en disco— es la tabla `blob` de aquí abajo.
        """
        CREATE TABLE IF NOT EXISTS blob_ref (
          task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
          blob_id TEXT NOT NULL,
          repo    TEXT NOT NULL,
          PRIMARY KEY (task_id, blob_id)
        )
        """,
        "CREATE INDEX IF NOT EXISTS blob_ref_by_repo ON blob_ref(repo, blob_id)",
        // La contabilidad de blobs de la Fase 4 (§4.2). Lo que el recolector sacaba de
        // `Files.list` sobre el directorio de adjuntos —la operación que con diez
        // millones de ficheros no termina, §1.6— sale ahora de aquí: qué hay, cuánto
        // pesa, de qué tamaño es y desde cuándo.
        //
        // La clave es `(repo, id)` y no `id` porque **cada repositorio tiene su propio
        // directorio de adjuntos**: la misma captura pegada en dos repositorios son dos
        // ficheros, y contar uno solo dejaría el otro sin dueño ni peso.
        //
        // `seen_at` es de la reconciliación (§4.3) y no un adorno: es lo que permite
        // detectar las filas cuyo fichero ya no está **sin tener que acordarse en
        // memoria de los diez millones que sí estaban**. El recorrido sella lo que ve,
        // y al final lo que quedó con el sello viejo es exactamente lo que falta.
        """
        CREATE TABLE IF NOT EXISTS blob (
          repo       TEXT    NOT NULL,
          id         TEXT    NOT NULL,
          bytes      INTEGER NOT NULL,
          width      INTEGER NOT NULL,
          height     INTEGER NOT NULL,
          created_at INTEGER NOT NULL,
          seen_at    INTEGER NOT NULL,
          missing    INTEGER NOT NULL,
          PRIMARY KEY (repo, id)
        )
        """,
        // El índice del recolector: los candidatos son los más viejos que el periodo de
        // gracia, así que la consulta entra por `(repo, created_at)` y sale en cuanto
        // tiene su tanda. Sin él, recoger costaría una pasada por todos los blobs del
        // repositorio en vez de por los que de verdad pueden irse.
        "CREATE INDEX IF NOT EXISTS blob_by_age ON blob(repo, created_at)",
        // Lo que no se puede hacer en cada apertura: la reconciliación del árbol (§4.3,
        // como mucho una vez por semana), el traslado de lo que quedara en plano (§4.1)
        // y el último aviso de cuota (§4.5).
        //
        // `at` es cuándo se hizo por última vez; `n`, un número cuyo significado lo pone
        // cada tarea —los bytes por los que se avisó, los blobs que se adoptaron— y está
        // escrito en `AttachmentChore`. Una tabla y no un `PropertiesComponent` porque
        // esto es estado **del repositorio de datos**, no de la instalación del IDE:
        // copiar el proyecto a otra máquina tiene que llevarse consigo que la
        // reconciliación ya se hizo.
        """
        CREATE TABLE IF NOT EXISTS chore (
          name TEXT    NOT NULL PRIMARY KEY,
          at   INTEGER NOT NULL,
          n    INTEGER NOT NULL
        )
        """,
        // Los contadores de las pestañas. Se mantienen en la MISMA transacción que la
        // escritura porque `count(*)` sobre un estado con un millón de filas sigue
        // siendo un recorrido de índice, y eso se pagaría en cada repintado.
        //
        // Tres cuentas y no una: las pestañas también cuentan con el filtro de vista
        // puesto, y «sin cerrar» y «marcadas» son los dos que no dependen del reloj.
        // El de vencidas sí depende, y por eso es el único que se consulta.
        """
        CREATE TABLE IF NOT EXISTS counter (
          repo     TEXT    NOT NULL,
          state    TEXT    NOT NULL,
          n        INTEGER NOT NULL,
          n_open   INTEGER NOT NULL,
          n_marked INTEGER NOT NULL,
          PRIMARY KEY (repo, state)
        )
        """,
        // El mismo reparto, por prioridad. Sirve para dos cosas: las cabeceras de un
        // estado que agrupa por prioridad, y —sobre todo— ENUMERAR que prioridades hay
        // en uso sin recorrer la tabla, que es lo que ConfigChanged necesita para saber
        // cuales han desaparecido de la configuracion.
        """
        CREATE TABLE IF NOT EXISTS priority_counter (
          repo     TEXT    NOT NULL,
          priority TEXT    NOT NULL,
          n        INTEGER NOT NULL,
          PRIMARY KEY (repo, priority)
        )
        """,
        // Qué repositorios ya se importaron del `tasks.xml`, y por dónde iba la
        // importación. Es lo que la hace idempotente y reanudable (§3.3): el fichero no
        // cambia mientras se lee, así que saltarse las `tasks` primeras es exactamente
        // continuar por donde se quedó.
        """
        CREATE TABLE IF NOT EXISTS imported (
          repo     TEXT    NOT NULL PRIMARY KEY,
          version  INTEGER NOT NULL,
          tasks    INTEGER NOT NULL,
          complete INTEGER NOT NULL,
          at       INTEGER NOT NULL
        )
        """,
        // `content = 'task'`: el índice invertido, sin una segunda copia del cuerpo.
        // El tokenizador ES `TextNormalizer`, pero dentro del motor: minúsculas y sin
        // diacríticos, comprobado en `SqliteSpikeTest`.
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS task_fts USING fts5(
          title, body, tags_text, files_text,
          content = 'task',
          content_rowid = 'seq',
          tokenize = "unicode61 remove_diacritics 2"
        )
        """,
    )

    /**
     * Pesos de `bm25()`, en el orden de las columnas de `task_fts`.
     *
     * El 10 en `title` es lo que sustituye al `TITLE_WEIGHT = 1_000_000` de
     * `LinearScanIndex.score()`: la misma jerarquía —un acierto en el título pesa más
     * que uno enterrado en el cuerpo— sin escalones apilados ni tope contra el
     * desbordamiento de un `Int`. Ver `SqliteSpikeTest`.
     */
    const val BM25 = "bm25(task_fts, 10.0, 1.0, 4.0, 2.0)"
}
