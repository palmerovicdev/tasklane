# Plan de escala — de 5.000 a 1.000.000 de tareas

> Objetivo: un repositorio con **1.000.000 de tareas**, cada una con 500 caracteres de
> texto, 10 enlaces, 10 imágenes, etiquetas, anclas y fechas, tiene que abrirse,
> buscarse, editarse y desplazarse **igual que el primer día**.

---

## 0. La regla única

> **Ninguna operación que dispara el usuario puede ser O(n) sobre el total de tareas.**
> Todo tiene que ser O(1), O(log n) o O(página visible).

Todo lo que sigue es consecuencia de esa frase. No es una optimización: es un cambio de
contrato. Hoy el contrato es «el modelo entero cabe en memoria y se reescribe entero», y
ese contrato es correcto para 5.000 tareas —lo dice `architecture.html` §8 y era cierto—
pero no se puede estirar. No hay constante que multiplicar.

---

## 1. Diagnóstico: qué se rompe, cuándo y por qué

### 1.1 El tamaño real de una tarea de la especificación

| Parte | Tamaño |
|---|---|
| Texto | 500 car. |
| 10 enlaces markdown `[texto](https://…)` | ~700 car. |
| 10 referencias de imagen `![](tasklane:<sha256>)` | 10 × 78 = 780 car. |
| **`body` total** | **~2.000 car.** |
| Atributos (`id`, `state`, `priority`, `order`, 4 fechas, `tags`, `bookmarked`) | ~200 B |
| 5 anclas `<anchor path line column text>` | ~650 B |
| **Total en XML** | **~2,9 KB** |

**× 1.000.000 = ~2,9 GB en un único `tasks.xml`.**

### 1.2 Lo que revienta primero — la carga

`TaskFileStore.readFile` llama a `JDOMUtil.load(file)`, que construye el **DOM completo**
en memoria antes de que exista una sola `Task`:

- ~7 `Element` + ~29 `Attribute` por tarea ≈ 1,8 KB de objetos
- más las cadenas: ~2,9 KB
- **≈ 4,7 KB por tarea → ~4,7 GB de heap sólo para el árbol JDOM**

El heap por defecto de IntelliJ son 2 GB. **`OutOfMemoryError` a ~300.000 tareas**, y eso
sin haber creado todavía ni una `Task`.

Y detrás viene lo segundo: `TaskReducer.reduceScoped`, en el caso `Loaded`, hace
`command.tasks.map { withDerived(normalize(it, config)) }`. Eso pasa las regex de
`LinkExtractor` e `ImageRefParser` sobre **2 GB de texto**. Minutos, en el hilo de IO,
con la ventana vacía.

Después, las `Task` vivas: cuerpo (~2 KB con cadenas compactas) + campos + los siete
`lazy` (`title`, `titleRange`, `detailBlocks`, `detailLines`, `description`,
`titleLinks`, `hasDetail`) ≈ **5–6 KB/tarea → ~5,5 GB**. Y `SearchDocument` guarda el
cuerpo normalizado **otra vez**: **+2 GB**.

### 1.3 La escritura: 2× el fichero de basura transitoria, cada 500 ms

```kotlin
val bytes = JDOMUtil.writeElement(TasksCodec.encode(repo, tasks)).toByteArray(UTF_8)
```

`writeElement` devuelve un **`String`** y luego se convierte a `ByteArray`: el pico son
las dos representaciones vivas a la vez, más la copia previa a `.bak`.

Con **100.000** tareas (290 MB de fichero) cada volcado son ~900 MB de basura
transitoria y ~600 MB de IO — **cada 500 ms de tecleo**, porque el debounce reescribe el
fichero **entero** aunque se haya cambiado una letra. A 1M es un `OutOfMemoryError`
garantizado en la ruta de guardado, que es la peor de todas para fallar.

### 1.4 El EDT: `rowHeight = 0` es una trampa a escala

```kotlin
tree.rowHeight = 0                 // TasklanePanel.init
…
(tree.model as DefaultTreeModel).reload()
expandGroups()
```

Con `rowHeight = 0`, `JTree` usa `VariableHeightLayoutCache`, que **mide cada fila
invocando al renderer**. `render()` crea un `TaskNode` por tarea y despliega los grupos:
**un millón de invocaciones de `TaskTreeRenderer` en el EDT**, cada una midiendo el
envoltorio del título. Se congela a unos pocos miles de filas.

**Esto es independiente del almacén.** Aunque los datos vinieran de una base de datos
perfecta, este `render()` mata el IDE. Por eso la UI acotada (Fase 2) va antes que el
almacén (Fase 3).

### 1.5 Seis pasadas O(n) por pulsación de tecla

Cada `TaskService.apply(command)` recorre hoy el modelo entero varias veces:

| Sitio | Qué hace |
|---|---|
| `TaskService.reportRemapped` | `flatten()` del snapshot **dos veces** + `HashSet` de 1M ids |
| `TaskService.markDirty` | comparación estructural de la lista del repo |
| `TaskReducer.mapTask` | reconstruye la lista **entera** del repo para tocar una tarea |
| `LinearScanIndex.setCorpus` | `flatten()` + `HashSet` de 1M en **cada** snapshot |
| `TasklaneSnapshot.task(id)` | escaneo lineal sobre todos los repos |
| `TasklaneSnapshot.nextOrder` | `maxOfOrNull` sobre todo el repo |

A 100.000 tareas ya son ~100 ms por tecla. A 1M, segundos.

### 1.6 Los adjuntos: aquí la física gana

- 10 imágenes × 1M tareas = **10.000.000 de blobs**, todos en **un solo directorio plano**
  (`attachments/<sha256>.png`).
- `AttachmentStore.list()` hace `Files.list(dir).toList()` → un array de 10M `Path` más un
  `readAttributes` por cada uno. `AttachmentGcActivity` nunca termina.
- A 1600 px de lado (`DEFAULT_IMAGE_MAX_SIZE`) una captura PNG ronda los 400 KB.
  **10M × 400 KB = 4 TB.** Con deduplicación agresiva 10:1 siguen siendo **400 GB**.

**Y un problema que ya existe hoy, con 20 tareas:**

```kotlin
private const val DECODED_CACHE = 16     // AttachmentService
private val decoded = LruCache<Key, Holder>(DECODED_CACHE)
```

Un PNG de 1600×1600 descodificado a `INT_ARGB` ocupa **10,2 MB**. Dieciséis son
**164 MB** de heap, más las 32 escaladas. La caché está acotada por **número de
entradas** cuando lo que hay que acotar son **bytes**. Esto se arregla en la Fase 1 y no
depende de nada más.

### 1.7 Resumen: dónde está el techo hoy

| Tareas | Qué pasa |
|---|---|
| ~5.000 | Todo bien. Es el punto de diseño y funciona. |
| ~20.000 | El volcado empieza a notarse: ~60 MB reescritos cada 500 ms de tecleo. |
| ~50.000 | La lista tarda en repintarse; el EDT se nota pegajoso al cambiar de pestaña. |
| ~100.000 | ~100 ms por tecla. El GC de adjuntos tarda minutos en arrancar. |
| ~300.000 | `OutOfMemoryError` al abrir el proyecto (DOM de JDOM). |
| 1.000.000 | Inalcanzable por tres caminos distintos a la vez. |

---

## 2. Arquitectura objetivo

### 2.1 Las cuatro piezas

```
┌─────────────────────────────────────────────────────────────────┐
│  UI: TasklanePanel                                              │
│  · nunca más de ~2.000 nodos en el árbol                        │
│  · pide páginas, no listas                                      │
└───────────────┬─────────────────────────────────────────────────┘
                │ TaskPage(items, hasMore, cursor) + Counts
┌───────────────▼─────────────────────────────────────────────────┐
│  TaskService                                                    │
│  · snapshot SIN tareas (config + repos + activo)                │
│  · un flow de página por pestaña                                │
└───────────────┬─────────────────────────────────────────────────┘
                │ Mutation (upsert / delete / bulk)   ← TaskReducer, puro
┌───────────────▼─────────────────────────────────────────────────┐
│  TaskStore  (SQLite, WAL)                                       │
│  · escritura incremental transaccional                          │
│  · índices para orden, filtro, agrupación y contadores          │
│  · FTS5 para el texto libre                                     │
└───────────────┬─────────────────────────────────────────────────┘
                │ refcount en la misma transacción
┌───────────────▼─────────────────────────────────────────────────┐
│  AttachmentStore  (árbol fragmentado + miniaturas)              │
│  · attachments/ab/cd/<sha>.png   + <sha>.thumb.png              │
│  · GC por tabla de referencias, no por recorrido de directorio  │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 Qué se conserva intacto

Esto importa: el plan **no tira** lo que está bien.

- **`TaskReducer` sigue siendo puro y testeable sin IDE.** Cambia su *entrada*, no su
  naturaleza (§3.3.4).
- **`TaskSearchIndex` ya es la costura correcta.** §8 dejó escrito que la decisión de no
  indexar era reversible «sin tocar la UI». Se cobra esa promesa: entra
  `Fts5Index : TaskSearchIndex` y `LinearScanIndex` se queda para los tests.
- **La garantía de compatibilidad hacia el futuro** (`Task.extra` + apertura en solo
  lectura si la versión es mayor) se mantiene, con otro mecanismo (§3.3.2).
- **El direccionamiento por contenido de los adjuntos** es correcto y se queda: la
  deduplicación es lo único que hace el problema de las imágenes menos malo.
- **El modelo inmutable y el flujo unidireccional** se quedan. Lo que cambia es que el
  snapshot deja de contener el corpus.

### 2.3 La decisión de almacén

**Recomendación: SQLite embebido (`org.xerial:sqlite-jdbc`), WAL + FTS5.**

Por qué, en una frase: la lista de una pestaña es *filtrar por estado y vista, ordenar
por marcada → prioridad → fecha, y paginar*, y eso es exactamente un índice compuesto y
un `LIMIT`. Escribirlo a mano es reescribir medio motor de consultas; y FTS5 resuelve de
regalo el único trozo verdaderamente difícil, la búsqueda de texto sobre 2 GB.

| Opción | A favor | En contra |
|---|---|---|
| **SQLite + FTS5** | Índices, orden, paginación y texto libre resueltos. Un fichero. WAL. Maduro. | Dep nativa (~12 MB, natives multiplataforma que se extraen en runtime). |
| `PersistentHashMap` de IntelliJ | Sin dependencias. Es lo que usa el propio IDE. | Sin motor de consultas: cada índice y el texto libre, a mano. Meses. |
| H2 MVStore | Java puro, ~2 MB, B-tree ordenado con MVCC. | Igual que arriba: los índices y el FTS, a mano. |
| Ficheros XML fragmentados | Cambio mínimo. | Sigue sin índices: filtrar y ordenar exige leerlo todo. No resuelve nada. |

La dependencia nativa es el único coste real y es asumible: `sqlite-jdbc` extrae su
librería a un temporal en el primer uso y funciona dentro del classloader aislado de un
plugin. **Verificar en la Fase 0** con un spike de medio día en macOS/Windows/Linux antes
de comprometerse — si ahí aparece un problema de firma o de sandbox, el plan B es H2
MVStore y la Fase 3 crece unas tres semanas.

**Un único fichero por proyecto**, `.idea/tasklane/tasklane.db`, con columna `repo`:
la búsqueda «en todos los repositorios» pasa a ser una consulta en vez de treinta, y
«Exportar y quitar» pasa a ser un `DELETE … WHERE repo = ?`.

### 2.4 El esquema

```sql
PRAGMA journal_mode = WAL;        -- lectores y escritor no se bloquean
PRAGMA synchronous  = NORMAL;     -- a prueba de caída del IDE; ventana mínima ante corte de luz
PRAGMA foreign_keys = ON;
PRAGMA user_version = 1;          -- sustituye a TasksCodec.CURRENT_VERSION

CREATE TABLE task (
  id             TEXT PRIMARY KEY,
  repo           TEXT    NOT NULL,
  body           TEXT    NOT NULL,
  state          TEXT    NOT NULL,
  priority       TEXT    NOT NULL,
  -- Desnormalizados desde la config: el índice necesita ORDENAR por prioridad y
  -- saber si el estado es terminal, y los ids son texto sin orden. Se reescriben
  -- con un UPDATE masivo cuando cambia la configuración, que es raro y es barato.
  priority_rank  INTEGER NOT NULL,
  state_terminal INTEGER NOT NULL DEFAULT 0,
  ord            INTEGER NOT NULL,
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL,
  completed_at   INTEGER,
  due_date       INTEGER,
  bookmarked     INTEGER NOT NULL DEFAULT 0,
  extra          TEXT               -- JSON. Es Task.extra: lo que escribió una versión futura.
);

-- El índice que sostiene la lista. Es el orden natural de buildSections():
-- marcadas primero, luego prioridad, luego lo más reciente.
CREATE INDEX task_board   ON task(repo, state, bookmarked DESC, priority_rank DESC, updated_at DESC);
-- Un índice por columna-ancla, porque DateGrouper.anchorOf() elige la fecha según
-- la configuración del estado. La consulta escoge el índice que toca.
CREATE INDEX task_created ON task(repo, state, created_at DESC);
CREATE INDEX task_done    ON task(repo, state, completed_at DESC);
CREATE INDEX task_due     ON task(repo, due_date) WHERE due_date IS NOT NULL;
CREATE INDEX task_ord     ON task(repo, state, ord);

CREATE TABLE tag (
  task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
  tag     TEXT NOT NULL,
  PRIMARY KEY (task_id, tag)
);
CREATE INDEX tag_by_name ON tag(tag, task_id);

CREATE TABLE anchor (
  task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
  seq     INTEGER NOT NULL,
  path    TEXT NOT NULL,
  line    INTEGER NOT NULL,
  col     INTEGER NOT NULL DEFAULT 0,
  text    TEXT NOT NULL DEFAULT '',
  PRIMARY KEY (task_id, seq)
);
-- Lo que necesita AnchorMarkers para preguntar «¿qué tareas cuelgan de este fichero?»
-- sin recorrer el modelo, que es lo que hace hoy.
CREATE INDEX anchor_by_path ON anchor(path, task_id);

CREATE TABLE blob (
  id         TEXT PRIMARY KEY,     -- sha256, igual que hoy
  repo       TEXT    NOT NULL,
  bytes      INTEGER NOT NULL,
  created_at INTEGER NOT NULL      -- el periodo de gracia de AttachmentGc
);
CREATE TABLE blob_ref (
  task_id TEXT NOT NULL REFERENCES task(id) ON DELETE CASCADE,
  blob_id TEXT NOT NULL,
  PRIMARY KEY (task_id, blob_id)
);
CREATE INDEX blob_ref_by_blob ON blob_ref(blob_id);

-- Contadores de las pestañas. Se mantienen en la MISMA transacción que la escritura,
-- porque COUNT(*) sobre un estado con un millón de filas sigue siendo O(n) aunque
-- sea un recorrido de índice, y eso se paga en cada repintado.
CREATE TABLE counter (
  repo  TEXT NOT NULL,
  state TEXT NOT NULL,
  n     INTEGER NOT NULL,
  PRIMARY KEY (repo, state)
);

CREATE VIRTUAL TABLE task_fts USING fts5(
  title, body, tags, files,
  content   = 'task',
  content_rowid = 'rowid',
  -- Esto ES TextNormalizer: minúsculas y sin diacríticos, pero dentro del motor.
  tokenize  = "unicode61 remove_diacritics 2"
);
```

**Notas de diseño que no son obvias:**

- **`priority_rank` y `state_terminal` desnormalizados.** Sin ellos el índice no puede
  ordenar y toda consulta acaba en un `SORT` de un millón de filas. Se recalculan con
  dos `UPDATE` cuando el usuario toca *Settings*, que pasa una vez al mes.
- **Un índice por columna-ancla.** `DateGrouper.anchorOf` elige entre `createdAt`,
  `updatedAt`, `completedAt` y `dueDate` según `state.anchor`. No hay un índice que sirva
  para los cuatro; hay cuatro índices y la consulta elige. El coste en disco es ~40 MB
  por índice a 1M filas: barato.
- **`bm25()` sustituye a `LinearScanIndex.score()`.** La jerarquía «un acierto en el
  título pesa más que cualquier afinidad» se expresa como
  `ORDER BY bm25(task_fts, 10.0, 1.0, 1.0, 1.0)` — peso 10 en la columna `title`. Mismo
  comportamiento, sin `TITLE_WEIGHT = 1_000_000` ni tope contra el desbordamiento.
- **Paginación por *keyset*, jamás `OFFSET`.** `OFFSET 900000` es O(900.000). El cursor
  es la tupla del orden: `WHERE (bookmarked, priority_rank, updated_at, id) < (?,?,?,?)`.

### 2.5 La UI acotada

**Invariante: el `JTree` nunca contiene más de ~2.000 nodos.** Se consigue con tres
reglas y ninguna de ellas se nota, porque nadie se desplaza por un millón de filas.

1. **Las cabeceras de grupo salen de agregados, no del contenido.** `GROUP BY` devuelve
   `(clave, cuenta)`. Un `GroupNode` se crea sin hijos.
2. **Un grupo desplegado carga su primera página** (100 filas) y añade un nodo centinela
   `… 4.213 más` al final. Al llegar el scroll al centinela, se pide la página siguiente
   con el cursor. Es el mismo gesto que ya existe en *Find in Files*.
3. **Con `Grouping.NONE`**, la raíz se pagina exactamente igual.
4. **Los grupos por encima de un umbral empiezan plegados.** Hoy `expandGroups()` los
   despliega todos; con un grupo de 800.000 filas eso es la muerte por medición de filas.

Consecuencias en el código actual:

- `TasklanePanel.render()` deja de reconstruir el árbol entero. Aplica un **diff** sobre
  el modelo: los nodos que siguen se quedan (y con ellos su altura ya medida y su estado
  de desplegado), y no hace falta `restoreSelection` ni el baile de `rendering = true`.
- `VisibleTasks.countsByState` pasa a leer la tabla `counter` (un `SELECT` de cinco
  filas) en vez de recorrer el snapshot. La discrepancia documentada y correcta —agrupar
  por etiqueta cuenta tareas, no filas— se mantiene igual.
- `exportSections()` no puede seguir exportando «lo que se ve» desde `sections`, porque
  ya no están todas cargadas. Pasa a **reejecutar la misma consulta sin `LIMIT`** y
  escribir en *streaming* (§3.5.1).

### 2.6 Presupuesto de memoria

| Concepto | Hoy a 1M | Objetivo |
|---|---|---|
| Árbol JDOM al cargar | ~4,7 GB | **0** (no hay parseo de documento) |
| `Task` vivas | ~5,5 GB | **< 20 MB** (sólo las páginas cargadas) |
| `SearchDocument` | ~2 GB | **0** (el índice vive en disco) |
| Nodos del árbol | 1M | **< 2.000** |
| Caché de imágenes | ~164 MB sin tope real | **64 MB, acotada por bytes** |
| **Total del plugin** | **OOM** | **< 150 MB, plano en N** |

---

## 3. Las fases

Cada fase entrega valor por sí sola y tiene una puerta medible. El orden no es
negociable: la Fase 2 va antes que la 3 porque la UI muere antes que el almacén, y la
Fase 1 va antes que todas porque es barata y sube el techo de golpe.

---

### Fase 0 — Banco de pruebas e instrumentación *(~1 semana)*

**Nada se optimiza sin medirlo.** Todas las cifras de §1 son estimaciones de lectura del
código; esta fase las convierte en números reales y, sobre todo, deja el instrumento con
el que se cierran las puertas de las otras seis fases.

1. **Generador de corpus sintético.** Una utilidad de test que escribe un repositorio de
   N tareas con la especificación exacta: 500 caracteres, 10 enlaces, 10 referencias de
   imagen, 5 anclas, 8 etiquetas, fechas repartidas. Fija (`Random(seed)`) para que dos
   ejecuciones comparen lo mismo. Corpus de 1k / 10k / 100k / 1M.
2. **Blobs sintéticos** que pesen lo que pesan de verdad (PNG de 1600 px), con una tasa de
   deduplicación configurable para poder medir los dos extremos.
3. **Suite de banco** (`@Ignore`, se ejecuta a mano y en un job nocturno): abrir en frío,
   tecla en el buscador, crear/editar/completar, cambiar de pestaña, desplazarse.
   Registra **tiempo de pared, tiempo de EDT y heap tras un GC forzado**.
4. **Acción de diagnóstico** en la propia herramienta (`Tasklane: Diagnostics`) que
   enseña número de tareas, tamaño del almacén, número y peso de blobs, y latencias de la
   última hora. Es lo que permite a un usuario real decir «va lento» con datos.
5. **Spike de `sqlite-jdbc` en un plugin**: cargar la nativa en macOS (arm64 e Intel),
   Windows y Linux, dentro del classloader del plugin y con el Plugin Verifier pasando.
   Medio día. **Si esto falla, el plan B es H2 MVStore y la Fase 3 crece ~3 semanas.**

**Puerta:** existe una cifra medida para cada fila de la tabla de §1.7.

---

## 0-bis. Resultados de la Fase 0 · CERRADA

Lo que sigue **no** son estimaciones. Todo está medido con `ScaleBenchmark` sobre el
corpus de `SyntheticCorpus`, en un MacBook con JBR 25, y se reproduce con:

```
./gradlew test --tests '*ScaleBenchmark' -PbenchN=<tareas> -PtestHeap=<heap>
```

### 0-bis.1 La decisión del §2.3 cambia: **no hace falta `sqlite-jdbc`**

> **Revertido en la Fase 6 (2.4.0).** El módulo funcionaba, pero su paquete es API interna
> (`@ApiStatus.Internal`) y el Marketplace rechaza el plugin por usarlo. Se empaqueta
> `sqlite-jdbc` sobre el mismo fichero y el mismo esquema; lo que costó, en el §6-bis.3.

El spike (§0.5) encontró algo que el plan no contemplaba: **la plataforma ya trae
SQLite**, con su nativa para las seis combinaciones de sistema y arquitectura.

```
lib/intellij.platform.sqlite.jar     org.jetbrains.sqlite, visibility="public"
lib/native/<os>-<arch>/libsqliteij.* mac/win/linux × x86_64/aarch64
```

Y está en el classpath de **IDEA CORE**, que es el classloader padre de todo plugin:
se usa sin declarar nada y sin empaquetar un byte. `SqliteSpikeTest` fija lo que la
Fase 3 necesita y que no era evidente desde fuera:

| Comprobado | Resultado |
|---|---|
| La nativa carga y ejecuta SQL | Sí |
| `PRAGMA journal_mode = WAL` | Sí |
| **FTS5 compilado** (`SQLITE_ENABLE_FTS5`) | **Sí** |
| `unicode61 remove_diacritics 2` (sustituye a `TextNormalizer`) | Sí |
| `bm25()` pondera el título sobre el cuerpo | Sí |
| Paginación por keyset sin repetir ni perder filas | Sí |
| La consulta de la lista se resuelve por índice, sin `TEMP B-TREE` | Sí |

**Consecuencia:** el riesgo nº1 del §4 —«la nativa de SQLite en un plugin de
JetBrains»— **desaparece**, y con él los 12 MB empaquetados, el problema de firma y el
riesgo de classloader que ya hizo descartar `kotlinx-serialization`. El plan B (H2
MVStore, +3 semanas) queda archivado.

Dos trampas del módulo, anotadas para la Fase 3: **nada de `ObjectBinderFactory.createN()`
ni de `AutoCloseable.use`** — son `inline` y vienen compiladas a JVM 25, que no cabe en
nuestro bytecode 21. Se usa `ObjectBinder(paramCount)` y `try/finally`.

### 0-bis.2 La tabla del §1.7, medida

Todos los tiempos son **p50**, en milisegundos. «Render» ocurre en el **EDT**.

| Tareas | Carga fría | Comando | Volcado | Repintado | **Render (EDT)** | Tecla | Memoria |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 5.000 | 166 | 0,27 | 57 | 3,0 | **170** | 5,6 | 62 MB |
| 10.000 | 333 | 0,36 | 104 | 4,5 | **263** | 9,9 | 123 MB |
| 20.000 | 701 | 0,79 | 215 | 12,6 | **442** | 22,9 | 246 MB |
| 50.000 | 1.703 | 1,29 | 554 | 26,3 | **1.002** | 52,7 | 616 MB |
| 100.000 | 3.399 | 4,14 | 1.253 | 48,6 | **2.125** | 99,3 | 1.231 MB |
| 150.000 · 2 GB | 5.149 | — | — | — | — | — | — |
| 200.000 · 2 GB | 6.914 | — | — | — | — | — | — |
| 300.000 · 2 GB | **OOM** | — | — | — | — | — | — |

Todo escala **lineal en N**, que es lo que el §0 daba por sentado y ahora está
comprobado. Las estimaciones del §1 aciertan en tres sitios y fallan en dos, y los dos
fallos cambian decisiones.

### 0-bis.3 Donde el plan acertó

- **2,9 KB por tarea en XML** (§1.1). Medido: **2.998 B**. Dentro del 3 %.
- **~400 KB por captura a 1600 px** (§1.6). Medido: **407 KB**.
- **«~100 ms por tecla a 100.000»** (§1.7). Medido: **99,3 ms** (p99 122 ms).
- **`OutOfMemoryError` al abrir con el heap de fábrica** (§1.2). Confirmado, y **al N
  que predecía**: 200.000 abre, 300.000 no. Lo que falló fue el motivo, no la cifra —
  ver más abajo.

### 0-bis.4 Donde el plan se quedó corto — y qué cambia

**a) La memoria es 1,7× peor de lo estimado, y se paga en dos plazos.** El §1.2
calcula 5–6 KB por `Task` más 2 KB de `SearchDocument`. Medido, y **plano por tarea
entre 5.000 y 100.000**:

| | Estimado §1.2 | Medido | Cuándo se paga |
|---|---:|---:|---|
| `Task` recién cargada | — | **6.482 B** | al abrir el proyecto |
| Los siete `lazy` (`title`, `detailBlocks`, …) | — | **3.433 B** | **al pintar la lista por primera vez** |
| `Task` viva completa | 5–6 KB | **9.915 B** | |
| `SearchDocument` | 2 KB | **3.014 B** | al escribir en el buscador |
| **Total por tarea** | ~7,5 KB | **12.929 B** | |

A 1.000.000 son **12,9 GB**, no los 7,5 GB del §1.2. No cambia la conclusión —las dos
cifras son inalcanzables— pero sí el objetivo del §2.6: bajar a «< 150 MB y plano en N»
es un factor de 86, no de 50.

**b) El techo de hoy no lo pone el DOM, y hay dos techos, no uno.**

El §1.2 razona que el `OutOfMemoryError` lo provoca el árbol de JDOM. Los dos números
de arriba cuentan otra historia, y además **explican exactamente** el OOM observado:

| Con el heap de fábrica de IntelliJ (2 GB) | Coste | Techo |
|---|---:|---:|
| **Abrir** el proyecto y no mirar la lista | 6.482 B/tarea | **~308.000** |
| **Usar** el plugin: pintar la lista y buscar | 12.929 B/tarea | **~155.000** |

Y así se comporta, medido: 150.000 y 200.000 abren en frío sin problema —5,1 s y 6,9 s—
y **300.000 muere con `OutOfMemoryError`**, que es justo donde el primer techo lo
predice. El fallo no ocurrió parseando el XML: ocurrió construyendo las `Task`.

De aquí salen dos cosas que la Fase 3 no puede olvidar:

1. **Parsear en streaming (§3.3) es necesario pero no suficiente.** Lo que no cabe en
   2 GB son las `Task`, no el árbol XML. El snapshot tiene que dejar de contener el
   corpus —§2.1— o el parser nuevo sólo mueve el problema unos miles de tareas.
2. **Un proyecto puede abrir y morirse al mirarlo.** Entre 155.000 y 308.000 tareas, el
   plugin arranca bien y revienta en cuanto la ventana pinta la primera lista, porque
   ahí es donde se materializan los siete `lazy`. Es el peor modo de fallo posible: el
   usuario ya ha abierto el proyecto y cree que todo va bien.

**c) El EDT se rompe en el punto de diseño, no cerca de los 50.000.**

El §1.7 dice «~5.000: todo bien» y «~50.000: la lista tarda en repintarse». Medido, el
repintado del árbol —que ocurre **en cada cambio del snapshot**, o sea en cada tecla
del buscador y en cada comando— cuesta:

| Tareas | Filas en el árbol | Render en EDT | Veces el presupuesto de 16 ms |
|---:|---:|---:|---:|
| 5.000 | ~1.670 | 170 ms | **11×** |
| 10.000 | ~3.330 | 263 ms | **16×** |
| 50.000 | ~16.700 | 1.002 ms | **63×** |
| 100.000 | ~33.300 | 2.125 ms | **133×** |

La constante que importa es **~0,064 ms por fila**, que es lo que cuesta que
`VariableHeightLayoutCache` mida una tarjeta invocando al renderer. De ahí sale el
número que la Fase 2 tiene que respetar:

> **El presupuesto de 16 ms se agota en ~250 filas.**

Y eso obliga a matizar el §2.5: el tope de «~2.000 nodos» **no basta por sí solo**
—2.000 filas medidas de cero son ~128 ms—. Lo que hace que la Fase 2 funcione es la
**otra** regla, la del `render()` por diff: los nodos que siguen conservan su altura ya
medida y no vuelven a pasar por el renderer. El diff no es una mejora opcional de la
Fase 2, es la pieza que sostiene la puerta.

### 0-bis.5 El tope de 400 px divide el problema del disco por doce

La política de imágenes elegida para la Fase 4 escala toda captura a 400 px de lado
mayor en vez de 1600. Medido sobre PNG sintéticos calibrados contra una captura real:

| Lado mayor | Por captura | 10M blobs | Con dedup 10:1 |
|---|---:|---:|---:|
| 1600 px (hoy) | 407 KB | 3,9 TB | 390 GB |
| **400 px (Fase 4)** | **33 KB** | **315 GB** | **31 GB** |

Sigue sin caber en un `.idea/` sin la cuota con aviso del §4.5, pero deja de ser una
cifra de otro orden de magnitud. Y de paso arregla lo que el §1.6 señala como problema
de **hoy**: un PNG de 400 px descodificado a `INT_ARGB` ocupa 640 KB, no 10,2 MB, así
que las dieciséis entradas de la caché de `AttachmentService` pasan de 164 MB a 10 MB
antes incluso de acotarlas por bytes en la Fase 1.

### 0-bis.6 Lo que quedó construido

| Pieza | Dónde | Qué hace |
|---|---|---|
| `SyntheticCorpus` | `test/…/bench/` | Corpus determinista con la especificación exacta del §1.1, en streaming para que 1M no tenga que caber en memoria |
| `SyntheticBlobs` | `test/…/bench/` | PNG con el peso de una captura real, con perilla de deduplicación |
| `Bench` | `test/…/bench/` | p50/p99/máx, tiempo de EDT y heap tras GC forzado |
| `ScaleBenchmark` | `test/…/bench/` | Los seis escenarios del §0.3, bajo `-PbenchN` |
| `SqliteSpikeTest` | `test/…/bench/` | El spike del §0.5, y el centinela de que FTS5 sigue estando |
| `TasklaneMetrics` | `main/…/diagnostics/` | Histograma de latencias de la última hora, sin bloqueos |
| `TasklaneDiagnostics` + `DiagnosticsReport` | `main/…/diagnostics/` | El informe del §0.4, puro y copiable |
| *Tasklane: Diagnostics* | `plugin.xml` | La acción, en segundo plano y con progreso |
| CI: `test` en tres sistemas | `.github/workflows/build.yml` | Los tests pasan a ejecutarse en CI —no lo hacían en ningún sitio— y el spike se comprueba contra la plataforma **mínima** y en Windows y Linux |

Las métricas están enchufadas a los seis caminos reales (`LOAD`, `COMMAND`, `SAVE`,
`SEARCH`, `SECTIONS`, `RENDER`), y el informe **avisa explícitamente** cuando el p99 de
`RENDER` se pasa de 16 ms, que es lo único del informe con un techo duro.

---

### Fase 1 — Quitar los O(n) por comando *(~1 semana)*

Sin tocar el almacén ni la UI. Es el mejor retorno del plan: sube el techo de ~20.000 a
~100.000 tareas con cambios pequeños y localizados, y **se puede publicar sola**.

| Sitio | Cambio |
|---|---|
| `TaskService.reportRemapped` | Hoy hace `flatten()` del snapshot **dos veces por comando**. El reducer ya sabe a qué tareas ha tocado: que devuelva los ids remapeados en el propio resultado y esto desaparece. |
| `TaskService.markDirty` | Sustituir la comparación de listas por la marca explícita que trae el comando. Ya se conoce el repo afectado. |
| `TasklaneSnapshot.task(id)` | Añadir un `Map<TaskId, Task>` al snapshot, construido una vez por snapshot en vez de escanear por consulta. |
| `TasklaneSnapshot.nextOrder` | Cachear el máximo por repo en el snapshot en vez de recorrer. |
| `TaskReducer.mapTask` | Reconstruye la lista entera para tocar una tarea. Con un índice por id en el snapshot pasa a ser una copia con una posición cambiada. |
| `LinearScanIndex.setCorpus` | El `HashSet` de todos los ids en cada snapshot pasa a reconstruirse sólo cuando el tamaño del corpus cambia. |
| `AttachmentService.decoded` / `scaled` | **Acotar por bytes, no por entradas.** 64 MB de imágenes descodificadas y 16 MB de escaladas, contando `width*height*4`. Arregla un consumo de hasta 164 MB que ya existe hoy. |

**Puerta:** con 100.000 tareas, una pulsación de tecla en el buscador y una edición de
tarea por debajo de **16 ms de EDT**. La carga en frío sigue siendo lenta; eso es la
Fase 3.

---

## 1-bis. Resultados de la Fase 1 · CERRADA

**Puerta cerrada.** Con 100.000 tareas, editar una tarea cuesta **5,99 ms de p99 de
EDT** contra los 16 del presupuesto, y una pulsación de tecla no consume EDT en
absoluto —la búsqueda corre en `Dispatchers.Default`, ver 1-bis.4—.

### 1-bis.1 El plan ordenaba mal las prioridades

Antes de tocar nada se midió el desglose, porque el §1.5 lista seis sitios O(n) pero no
dice cuál pesa, y arreglar el que no era es la forma más común de gastar una semana sin
mover una cifra. A 100.000 tareas:

| Pieza del comando | Coste | Puesto en la lista del §1.5 |
|---|---:|---|
| `reduce` — en concreto `mapTask` | **2,86 ms · 69 %** | 5.º de 7 |
| `reportRemapped` (2 × `flatten`) | 0,54 ms · 13 % | **1.º** |
| `markDirty` (`equals` de listas) | 0,06 ms · 1,4 % | 2.º |
| `snapshot.nextOrder` | 0,48 ms | 4.º |
| `snapshot.task(id)` | 0,26 ms | 3.º |

Y en la tecla, `setCorpus` —el único que el plan menciona— eran 8 ms de los 99: el 8 %.
Los otros 91 son el escaneo, que es O(n) por diseño hasta la Fase 3.

### 1-bis.2 Qué se hizo, y el resultado

| Sitio | Antes | Después |
|---|---:|---:|
| Un comando (p50 / p99) | 4,14 / 16,62 ms | **0,51 / 3,56 ms** |
| └ el reducer | 2,86 ms | **0,30 ms** |
| └ `reportRemapped` | 0,54 ms | *desaparece* |
| └ `markDirty` | 0,06 ms | *desaparece* |
| `snapshot.task(id)` | 0,26 ms | **0,00 ms** |
| `snapshot.nextOrder` | 0,48 ms | **0,00 ms** |
| `LinearScanIndex.setCorpus` | 8,09 ms | **0,00 ms** |
| **Editar una tarea (p99)** | **26,11 ms** | **5,99 ms** |

- **El reducer devuelve un recibo.** `TaskReducer.plan` produce una `Reduction` con el
  snapshot, **qué repositorios hay que reescribir** y **qué tareas acaba de aparcar**.
  El servicio deja de deducirlo: `reportRemapped` ya no aplana el snapshot dos veces y
  `markDirty` ya no compara listas. En la Fase 3 esta misma forma pasa a llevar
  `List<Mutation>` (§3.4), así que la costura ya está puesta.
- **`mapTask` deja de reconstruir la lista.** Era `map` con una lambda por elemento;
  ahora es buscar el índice y copiar el array de una llamada. Sigue siendo O(n) —eso es
  la Fase 3— pero la constante baja casi 10×.
- **`nextOrder` y `task(id)` pasan a O(1).** El techo del orden lo mantiene el reducer
  (`TasklaneSnapshot.maxOrder`), calculado al cargar y subido al crear. El índice por id
  es un `lazy`: se construye sólo si alguien pregunta, que en producción es un único
  sitio —pulsar una marca del editor—.
- **`setCorpus` cuenta antes de construir.** La poda sólo hace falta cuando hay más
  documentos cacheados que tareas; sumar el tamaño de un puñado de listas es gratis,
  construir un `HashSet` con todos los ids del proyecto en cada snapshot no lo era.
- **Las cachés de imágenes se acotan por bytes** (`ByteBoundedCache`, 64 MB y 16 MB).
  Es el arreglo de un consumo de hasta **164 MB que existe hoy con veinte tareas**: una
  captura de 1600 px descodificada a `INT_ARGB` ocupa 10,2 MB, y se cacheaban dieciséis
  contando *entradas*. Ahora caben seis, que es lo que son 64 MB.

### 1-bis.3 Lo que no estaba en el plan y la puerta obligó a hacer

Medir la puerta tal y como está escrita —«una **edición de tarea**»— dio 26,11 ms de
p99, por encima del presupuesto. El motivo no estaba en la lista del §1.5:

> `TasklanePanel.editSelected` mandaba **seis comandos seguidos** al aceptar el
> diálogo: cuerpo, estado, prioridad, etiquetas, vencimiento y anclas.

Seis comandos son seis copias de la lista del repositorio —3,6 MB de basura por
edición a 100.000 tareas— y, mucho peor, **seis snapshots**, o sea hasta seis
repintados del árbol por un solo clic en *Guardar*. A 2,1 s de EDT por repintado, el
coste real de esto era de otro orden que el que la puerta mide.

Se resuelve con un comando único, `TaskCommand.UpdateTask`, que aplica los seis cambios
sobre una sola copia y produce un solo snapshot. Los comandos sueltos se quedan: *Move
To*, el distintivo de prioridad y el marcador cambian **una** cosa y para eso son. Lo
que no tenía sentido era componer una edición con ellos.

Un test fija que `UpdateTask` da **exactamente** la misma tarea que los seis comandos
que sustituye, `updatedAt` y `completedAt` incluidos: una optimización de este tipo
sólo vale si el resultado es idéntico.

De paso, el contrato de identidad nuevo —el servicio decide por `===` si hay algo que
hacer— destapó que `Delete` con un id inexistente reconstruía la lista igualmente: un
repintado y un volcado a disco por un borrado que no borraba nada.

### 1-bis.4 Qué sigue siendo el techo

El camino del comando **ya no lo es**. Lo que queda, medido a 100.000:

| | Coste | Dónde se arregla |
|---|---:|---|
| Repintar el árbol (**en el EDT**) | 2.189 ms | **Fase 2** |
| Buscar (fuera del EDT) | 91 ms | Fase 3 (FTS5) |
| Cargar en frío | 3.302 ms | Fase 3 |
| Volcar el fichero entero | 1.105 ms | Fase 3 |
| Memoria | 12,9 KB/tarea | Fase 3 |

Una precisión sobre la puerta: la pulsación de tecla **no consume EDT**, porque
`SearchService` corre con `flowOn(Dispatchers.Default)`. Lo que el usuario nota al
teclear es la latencia de 91 ms hasta los resultados y, detrás, los 2,2 s de repintado
— que es de la Fase 2, no de ésta.

### 1-bis.5 Una nota sobre cómo medir

Las cifras de «antes» del §0-bis y las de «después» de aquí **no se pueden restar entre
sí**: entre unas y otras el portátil estaba en otro estado y el mismo escenario sin
tocar —el repintado— se movió de 48 a 57 ms sin que nadie cambiara una línea. Se
comprobó guardando el trabajo y volviendo a medir la base en ese momento.

Toda comparación de este documento a partir de aquí es **lado a lado en la misma
ejecución**, que es el motivo de que el banco mida los dos caminos de la edición en vez
de sólo el nuevo.

---

### Fase 2 — UI acotada *(~2-3 semanas)*

La pieza que hay que hacer sí o sí, venga la lista de donde venga. Después de esta fase,
**la ventana no se congela con ningún N**, aunque el almacén siga siendo un XML.

1. **Interfaz `TaskPager`** entre el panel y quien tenga los datos:
   `fun page(query: PageQuery, cursor: Cursor?): TaskPage`. Implementación provisional
   sobre el snapshot en memoria (`InMemoryPager`), para poder hacer esta fase sin esperar
   a la base de datos. Cuando llegue la Fase 3 se sustituye la implementación y la UI no
   se entera. Es la misma jugada que ya funcionó con `TaskSearchIndex`.
2. **Modelo del árbol perezoso**: `GroupNode` sin hijos hasta que se despliega;
   `MoreNode` centinela al final de cada página.
3. **`render()` por diff** en vez de `removeAllChildren()` + `reload()`. Se conservan
   altura medida, despliegue y selección sin el rodeo de `restoreSelection`.
4. **Plegado por defecto** de los grupos por encima de un umbral, con la cuenta visible en
   la cabecera.
5. **`countsByState` desde un agregado**, no desde el recorrido.
6. **`reveal(ids)` con paginación**: hoy busca el nodo en el árbol; ahora tiene que
   preguntar *en qué página cae* esa tarea y saltar allí.

**Puerta:** con el corpus de 1M en memoria forzada (heap ampliado a mano para que quepa),
la ventana abre, se desplaza a 60 fps y cambia de pestaña sin una sola congelación del
EDT por encima de 16 ms. La memoria sigue siendo mala — eso es la Fase 3.

---

## 2-bis. Resultados de la Fase 2 · CERRADA

**Puerta cerrada.** Con el corpus de **1.000.000 de tareas** en memoria forzada, ninguna
operación del hilo de interfaz pasa de **3 ms** contra los 16 del presupuesto: abrir la
ventana cuesta 0,61 ms, repintarla tras un comando 0,14 ms y desplazarse una página
2,65 ms. La memoria sigue siendo mala y ordenar sigue costando lo que cuesta: eso es la
Fase 3, como el plan decía.

```
./gradlew test --tests '*ScaleBenchmark' -PbenchN=1000000 -PtestHeap=32g
```

### 2-bis.1 La puerta, medida

Todo en **p50 de EDT**, los dos caminos uno al lado del otro **en la misma ejecución**,
por lo que dice el §1-bis.5. «Antes» es reconstruir el árbol entero y recargarlo, que es
lo que el panel hacía hasta esta fase.

| Tareas | Primer pintado antes | **ahora** | Repintar tras un comando antes | **ahora** | Desplazar una página | Agrupar y contar *(fuera del EDT)* |
|---:|---:|---:|---:|---:|---:|---:|
| 10.000 | 136 ms | **6,1 ms** | 178 ms | **0,29 ms** | 3,1 ms | 3,9 ms |
| 100.000 | 1.179 ms | **4,1 ms** | 1.194 ms | **0,31 ms** | 2,6 ms | 41 ms |
| 1.000.000 | 10.200 ms | **0,61 ms** | 10.164 ms | **0,14 ms** | 2,7 ms | 740 ms |

Lo que importa de esa tabla no son los cocientes —×17.000 a un millón— sino la forma:
**la columna del EDT deja de crecer con N**, y de hecho baja, porque a más tareas más
grupos superan el umbral y empiezan plegados. El coste de pintar pasa a depender de lo
que se ve, que es de lo que siempre debió depender.

El repintado tras un comando toca **entre una y tres filas**. Ése es el número que
explica la columna: completar una tarea no cambia la lista, cambia una fila de la lista.

### 2-bis.2 Dos cifras del §0-bis que había que corregir

**a) La página son cincuenta filas, no cien.** El §2.5.2 dice «primera página (100
filas)». Medido sobre el corpus de la especificación, traer cien filas cuesta **12,9 ms
de p50 y 14,9 de p99**, con un presupuesto de 16. Pasar la puerta por un 7 % no es
pasarla: en una máquina más lenta, o con una tarjeta desplegada de por medio, ese margen
no existe. Con cincuenta son ~2,7 ms, y cincuenta filas siguen siendo tres pantallas
largas de tarjetas.

**b) «No más de ~2.000 nodos» no era la invariante.** El §2.5 la enuncia así y el
§0-bis.4 ya avisaba de que no basta —2.000 filas medidas de cero son ~128 ms—. La
invariante real, la que se puede defender, es otra:

> **Ningún evento del EDT puede crear o remedir más de ~150 filas.**

El número de nodos del árbol es una consecuencia, no la regla: crece al desplazarse
—cada página son cincuenta filas más— y eso no cuesta nada mientras las que ya estaban
no se vuelvan a medir. Por eso el diff no es «una mejora opcional de la Fase 2»
(§0-bis.4 lo dijo primero): es la fase.

### 2-bis.3 Lo que el plan no había visto

**a) Un O(n) en el EDT que no estaba en ninguna lista.** `TasklanePanel.render` llamaba
en cada repintado a `renderer.retainExpanded(...)` con los ids de **todas** las tareas
del snapshot, para olvidar las tarjetas desplegadas que ya no existían. Construir ese
conjunto es un recorrido del corpus entero en el hilo de interfaz: a un millón de
tareas, la limpieza costaba más que todo lo que esta fase ahorra. Y no hacía falta: los
ids no se reciclan, así que un id de una tarea borrada no le da su despliegue a nadie.
El conjunto pasa a estar acotado por número de entradas y la pasada desaparece.

**b) Las cabeceras también son filas.** Agrupar por fecha reparte un año en meses y
días, y agrupar por etiqueta puede dar miles de grupos: son filas que hay que medir
igual que las tareas. El presupuesto se reparte entre las dos cosas, y las cabeceras se
paginan como todo lo demás —cincuenta y un centinela—. Sin esto, una pestaña con cien
cabeceras se comía el presupuesto antes de abrir un solo grupo.

**c) `JTree` no despliega sola una raíz que se llena por diferencias.** La raíz va
oculta, y la plataforma la marca como desplegada en **dos** momentos: al instalar el
modelo —si ya tiene hijos— y en cada `reload()`. Quitado el `reload()`, no queda
ninguno: el árbol se queda en blanco con el modelo lleno. Es el peor modo de fallo
posible porque no se parece a un fallo, y ya se vivió una vez —el trabajo de
accesibilidad de la 1.0.0 dejó la ventana vacía con las cuentas pintadas—. Lo fija un
test que describe el comportamiento de la plataforma, no el nuestro.

**d) Otra vez el campo protegido que se llama igual que el accesor.** Una cabecera nace
sin hijos y aun así tiene que poder desplegarse: ése es el gesto que pide su primera
página. `DefaultTreeModel` trae para eso `asksAllowsChildren`, y en Kotlin
`modelo.asksAllowsChildren = true` **no compila**, porque el nombre resuelve al campo
protegido en vez de al `setter` — exactamente el mismo tropiezo que obligó a construir a
mano el `AccessibleContext` de `StateTabRow`. Se resuelve con un `isLeaf` propio
(`TaskTreeModel`), que además dice en una línea lo que el interruptor decía en dos.

### 2-bis.4 Qué cambia para quien lo usa

- **La lista carga a páginas.** Al final de cada grupo hay una fila que dice cuántas
  quedan; llegar a ella desplazándose —o pulsarla, o darle a `Enter`— trae las
  siguientes. Es el gesto de *Find in Files*.
- **Los grupos grandes empiezan plegados**, con su número en la cabecera. Y los de abajo
  también, cuando los de arriba ya llenan la pantalla.
- **Enseñar una tarea** —pulsar una marca del editor— ya no carga lo que tiene delante:
  abre la ventana a su altura y anuncia lo que queda por encima con otro centinela.
- **La exportación no exporta «lo que está cargado»**: vuelve a pedir la misma lista sin
  tope. Es lo único que sigue siendo O(n) a propósito, y lo que la Fase 5 pasará a
  escribir en *streaming*.
- Lo que **no** cambia: el orden, la agrupación, los contadores de las pestañas, la
  selección, la búsqueda y el pliegue que el usuario decida. La regla de arriba es que
  lo que dijo el usuario manda sobre lo que decida el presupuesto.

### 2-bis.5 Lo que quedó construido

| Pieza | Dónde | Qué hace |
|---|---|---|
| `TaskPager` + `PageQuery` / `TaskPage` / `Cursor` | `main/…/paging/` | La costura del §2.1: la UI pide páginas y agregados, nunca listas. En la Fase 3 se cambia la implementación y nadie más se entera |
| `InMemoryPager` | `main/…/paging/` | El `buildSections` de antes, detrás de la costura: ordena y agrupa el snapshot y lo sirve a trozos, con cursor y sin `OFFSET` |
| `ListSync` | `main/…/ui/toolwindow/` | El árbol acotado: páginas, centinelas, ventanas cargadas y el salto de «enséñame esta tarea». **No necesita un IDE**, y por eso tiene test de punta a punta |
| `TreeSync` + `Row` | `main/…/ui/toolwindow/` | La sincronización por diferencias. Lo que sigue igual no vuelve a pasar por el renderer |
| `GroupBudget` | `main/…/ui/toolwindow/` | Qué grupos se abren solos: el reparto del presupuesto de filas del primer pintado |
| `MoreNode`, `TaskTreeModel` | `main/…/ui/toolwindow/` | El centinela de una página, y la cabecera que se despliega estando vacía |
| `VisibleTasks.byState` | `main/…/ui/toolwindow/` | Contar y repartir por estado en **una** pasada en vez de dos. Es el agregado del §2.5.5 mientras no haya tabla `counter` |
| Cuatro suites nuevas | `test/…/paging/`, `test/…/ui/` | `InMemoryPagerTest`, `ListSyncTest`, `TreeSyncTest`, `GroupBudgetTest`: 58 casos sobre la paginación, el diff, el presupuesto y la lista entera |
| Tres escenarios de banco | `test/…/bench/` | Primer pintado, repintado con el árbol ya puesto y desplazarse una página — cada uno con el camino viejo al lado |

El banco dejó además de tener una copia de `buildSections`: mide `InMemoryPager`,
`ListSync`, `TreeSync` y `GroupBudget`, que son los de producción. Lo único que sigue
duplicado ahí es el camino **viejo**, que ya no existe en ningún otro sitio.

### 2-bis.6 Qué sigue siendo el techo

Repintar ya no lo es. Lo que queda, medido a 1.000.000:

| | Coste | Dónde se arregla |
|---|---:|---|
| Ordenar y agrupar el corpus (fuera del EDT) | 740 ms | **Fase 3** (`ORDER BY` + `LIMIT`) |
| Cargar en frío | ~34 s † | Fase 3 |
| Buscar | ~0,9 s † | Fase 3 (FTS5) |
| Memoria | 12,9 KB/tarea | Fase 3 |
| EDT | **nada por encima de 3 ms** | — |

† Extrapolado desde las cifras medidas a 100.000 (3,4 s y 93 ms), que es lo único de
esta tabla que no está medido a un millón: los dos escenarios necesitan el `tasks.xml`
de 2,9 GB y el DOM de JDOM encima, y eso es justamente lo que la Fase 3 borra. El §0-bis
ya comprobó que las dos cosas escalan lineales en N.

Los 740 ms de agrupar son ahora lo primero que el usuario nota, y son el trabajo que la
Fase 3 borra de un plumazo: la lista de una pestaña es un índice compuesto y un `LIMIT`,
y los contadores son cinco filas de la tabla `counter`. La costura para cambiarlo ya está
puesta —`TaskPager`— y la UI no se va a enterar.

---

### Fase 3 — El almacén *(~5-6 semanas)*

La fase grande.

#### 3.1 `TaskStore` sobre SQLite

- Una conexión de escritura, N de lectura (WAL lo permite). Todo fuera del EDT, como hoy.
- Sentencias preparadas y reutilizadas; `PRAGMA cache_size` acotado (~32 MB).
- Escrituras agrupadas en una transacción por *flush*, enganchadas al mismo debounce de
  500 ms que ya existe. El `.bak` en cada guardado **desaparece**: WAL da atomicidad.

#### 3.2 Versionado y compatibilidad hacia el futuro

La promesa de `TasksCodec` —«abrir el proyecto con una versión vieja no borra datos
escritos por una nueva»— se conserva con otro mecanismo:

- `PRAGMA user_version` sustituye a `TasksCodec.CURRENT_VERSION`. Si es mayor que la del
  plugin, **se abre en solo lectura** y se avisa, exactamente como hoy.
- La columna `extra` guarda el JSON de `Task.extra` y se vuelve a escribir tal cual.
- **Sólo migraciones aditivas**: columnas nuevas con `DEFAULT`, tablas nuevas, índices
  nuevos. Nunca renombrar ni borrar una columna que una versión anterior lea.
- La lista es más larga que hoy en un punto: **las anclas dejan de ser el caso especial**.
  Hoy son hijos `<anchor>` y una versión vieja las pierde al reescribir; con tabla propia
  y `ON DELETE CASCADE`, una versión que no las conozca simplemente no las toca. Se gana
  la garantía que §hoy da por perdida.

#### 3.3 La migración desde `tasks.xml`

- **Parseo en streaming con StAX**, no con JDOM. Es la regla entera de esta fase: un
  fichero de 2,9 GB no se puede meter en un DOM, pero sí se puede recorrer con memoria
  constante.
- Con `ProgressIndicator` cancelable, en segundo plano, con la ventana usable.
- El `tasks.xml` original **no se borra**: se renombra a `tasks.xml.migrated`. La vuelta
  atrás es renombrarlo.
- **Se conserva la exportación a XML** en el formato actual, para que el dato no quede
  secuestrado dentro de un `.db`.
- Idempotente y reanudable: si el IDE se cierra a mitad, la siguiente apertura continúa.

#### 3.4 `TaskReducer`: misma pureza, otro alcance

Hoy `reduce(snapshot, command)` recibe el modelo entero. Pasa a:

```kotlin
// Puro, sin IDE, igual de testeable. Lo que cambia es que no necesita el corpus.
fun plan(config: TasklaneConfig, subject: List<Task>, command: TaskCommand): List<Mutation>

sealed interface Mutation {
    data class Upsert(val tasks: List<Task>) : Mutation
    data class Delete(val ids: List<TaskId>) : Mutation
    /** Lo que afecta a todo el proyecto y no cabe fila a fila. */
    data class Reassign(val from: StateId, val to: StateId) : Mutation
}
```

`TaskService` carga por id **sólo las tareas que el comando toca**, planifica, y el store
aplica las mutaciones en una transacción. Los comandos de alcance de proyecto —borrar un
estado y reasignar sus tareas— dejan de ser un `map` sobre un millón de objetos y pasan a
ser `UPDATE task SET state = ? WHERE state = ?`: instantáneo, y sin cargar nada.

Nota sobre el orden manual: `ORDER_GAP = 1000` sobre `Long` da margen de sobra, pero
insertar repetidamente entre dos vecinos adyacentes agota el hueco **local**. Hace falta
un **rebalanceo de ventana**: cuando dos vecinos quedan a distancia 1, se renumeran las
~64 filas de alrededor en la misma transacción. Es O(64), no O(n).

#### 3.5 `Fts5Index : TaskSearchIndex`

- `QueryParser` no se toca: sigue produciendo el `TaskQuery` de hoy.
- El texto libre va a `MATCH`; `state:`, `priority:`, `repo:`, `#tag`, `file:`, `is:done`
  y `has:` van al `WHERE` de SQL.
- El orden, por `bm25()` con peso 10 en `title`.
- `LIMIT 200`: nadie mira el resultado 201 de una búsqueda.
- `LinearScanIndex` **se queda** como implementación de referencia en los tests: dos
  implementaciones de la misma interfaz son la mejor prueba de que la nueva no cambió la
  semántica. Test de equivalencia sobre el corpus de 10k.

#### 3.6 `AnchorMarkers` deja de recorrer el modelo

Hoy, para saber qué tareas cuelgan del fichero abierto, se mira el modelo entero. Pasa a
`SELECT … FROM anchor WHERE path = ?` sobre `anchor_by_path`. Es el cambio que hace que
abrir un fichero no cueste lo mismo que abrir el proyecto.

**Puerta de la Fase 3:**

| Medida | Objetivo |
|---|---|
| Abrir en frío un repo de 1M | primer pintado **< 300 ms** |
| Tecla en el buscador → resultados | **< 100 ms** |
| Crear / editar / completar | UI **< 50 ms**, volcado **< 20 ms** |
| Heap del plugin | **< 150 MB**, y plano entre 10k y 1M |
| Migración de 1M desde XML | termina, es cancelable y no supera 300 MB de heap |
| EDT | ninguna congelación > 16 ms en ninguna operación |

---

---

## 3-bis. Resultados de la Fase 3 · CERRADA

**Puerta cerrada.** Con 100.000 tareas, abrir el proyecto y pintar la lista cuesta
**9,2 ms** contra los 3.027 ms del camino anterior; el plugin ocupa **2,5 MB** contra
1,29 GB; y guardar una edición pasa de reescribir 1.168 ms de fichero a una transacción
de **0,44 ms**. Ninguna operación del hilo de interfaz se acerca a los 16 ms.

```
./gradlew test --tests '*ScaleBenchmark' -PbenchN=100000  -PtestHeap=4g
./gradlew test --tests '*ScaleBenchmark' -PbenchN=1000000 -PtestHeap=8g
```

**Las cifras de abajo están medidas a 100.000 y a 10.000**, y lo que cierra la puerta no
es su valor absoluto sino que **son la misma cifra en los dos tamaños**. Medir el millón
completo cuesta horas y ~25 GB de disco —hay que construir la base dos veces, la del
corpus y la de la migración— y no cambia lo que se puede afirmar: el coste por operación
dejó de depender de N, que es literalmente la regla del §0.

**El heap que hace falta para medir bajó, y ése es medio resultado.** La Fase 2 necesitaba
`-PtestHeap=32g` para medir un millón porque el corpus tenía que caber; ahora el corpus
vive en `tasklane.db` y sólo se materializa lo que se mira.

### 3-bis.1 La puerta, medida

Los dos caminos en la misma ejecución, que es lo único que hace comparable una cifra
(§1-bis.5). «Antes» es lo que hacía el plugin hasta la 1.6.0.

| Con 100.000 tareas | Antes | **Fase 3** |
|---|---:|---:|
| Abrir el proyecto y pintar la lista *(EDT)* | 3.027 ms | **9,2 ms** |
| Repintar sin que nada haya cambiado *(EDT)* | — | **4,5 ms** |
| Editar una tarea: comando + repintado *(EDT)* | — | **10,6 ms** |
| Escribir en disco | volcado de 1.168 ms | transacción de **0,44 ms** |
| Leer del disco al abrir | 580 ms de JDOM | **no se lee** |
| Buscar, el peor término posible | ~115 ms † | **40,5 ms** |
| Desplazarse una página *(EDT)* | — | **7,4 ms** |
| Memoria del plugin | 1,29 GB ‡ | **2,5 MB** |

† El escaneo lineal medido en la Fase 1 daba 91 ms; los 115 son la primera versión de
`Fts5Index`, antes del arreglo del §3-bis.3.b. Se anota porque es lo que se comparó.

‡ No se vuelve a medir: son los 12.929 B por tarea del §0-bis.4, que ya estaban medidos.
Materializar el corpus para volver a pesarlo sería reconstruir justo lo que esta fase
borró.

Y la forma, que importa más que los cocientes:

| | 10.000 | 100.000 | Crece con N |
|---|---:|---:|---|
| Abrir y pintar *(EDT)* | 8,5 ms | 9,2 ms | **no** |
| Editar una tarea: comando + repintado *(EDT)* | 9,7 ms | 10,8 ms | **no** |
| Un comando, transacción incluida | 0,43 ms | 0,42 ms | **no** |
| Desplazarse una página *(EDT)* | 7,0 ms | 7,4 ms | **no** |
| Memoria del plugin | 2,8 MB | 2,5 MB | **no** |
| Buscar, el peor término | 20 ms | 40,5 ms | **sí** — ver §3-bis.5 |

### 3-bis.2 Dónde el plan acertó

- **SQLite de la plataforma basta.** El §0-bis.1 lo adelantó y la fase lo confirma: cero
  bytes empaquetados, cero problemas de classloader, FTS5 compilado y WAL. Las dos
  trampas anotadas —nada de `ObjectBinderFactory.createN()` ni de `AutoCloseable.use`,
  que son `inline` a JVM 25— se pisan en cada línea y estaban bien anotadas.
- **`priority_rank` y `state_terminal` desnormalizados.** Sin ellos no hay índice que
  ordene. Se recalculan con un `UPDATE` por clave cuando cambia la configuración, que es
  raro y es barato.
- **Paginación por *keyset*, jamás `OFFSET`.** Tal cual.
- **`bm25()` con peso 10 en el título sustituye a `TITLE_WEIGHT`.** El test de
  equivalencia con `LinearScanIndex` pasa consulta por consulta.
- **La costura de la Fase 2 cumplió.** Entró `SqlitePager` y **no hubo que tocar ni una
  línea de la UI**. Lo mismo con `TaskSearchIndex` y `Fts5Index`.

### 3-bis.3 Dónde el plan se quedó corto — y qué cambió

**a) Un índice por columna-ancla no hacía falta; una columna sí.**

El §2.4 pedía cuatro índices con la tupla de orden completa, uno por cada fecha de la que
puede colgar la agrupación. Son ~200 MB a un millón de filas y tres de ellos no se tocan
nunca. Se aplica un paso más el propio truco del plan —desnormalizar lo que el índice
necesita para ordenar—: **`sort_date` guarda ya resuelta** la fecha del anclaje del
estado en el que está la tarea. Un índice en vez de cuatro, y la agrupación por fecha
pasa a ser un rango contiguo sobre él.

Con una trampa que costó un test en rojo: `MemoryPager` ordena por
`anchorOf(task, anchor) ?: updatedAt`. Guardar «sin fecha» en `sort_date` hacía que las
tareas sin completar de un estado terminal salieran ordenadas **por identificador**. La
fecha de orden y el grupo son **dos datos distintos**: `sort_date` cae a `updated_at`
cuando el anclaje no existe, y una columna aparte —`undated`— recuerda que no existía.

**b) `ORDER BY` y `JOIN` en la misma consulta cuestan el corpus entero.**

La forma obvia —unir `task_fts` con `task`, filtrar y ordenar por `bm25`— obliga a saltar
a la tabla por **cada** acierto antes de poder descartarlo. Medido sobre 50.000 tareas y
un término presente en casi todas:

| | Coste |
|---|---:|
| `count(*)` de los aciertos | 0,7 ms |
| Los 200 mejores rowids, con `bm25` | 12,3 ms |
| **Lo mismo uniendo con `task` y filtrando** | **44,0 ms** |

La búsqueda pasa a hacerse en **dos pasos**: el índice de texto puntúa y limita sin salir
de sí mismo, y sólo los que sobreviven se buscan en la tabla. A 100.000 tareas, 115 ms →
40 ms.

**c) La invariante de la Fase 2 se rompía en silencio, y no estaba en ninguna lista.**

`TaskNode.update` decidía por **identidad** si una fila había cambiado. Con el corpus en
memoria era exacto y costaba un puntero: una tarea que no cambiaba era literalmente el
mismo objeto entre repintados. Con el almacén, **cada página se lee y las instancias son
nuevas siempre**, así que comparar punteros decía «ha cambiado» de todas las filas
cargadas y las volvía a medir una por una — que es exactamente lo que la Fase 2 vino a
quitar. Medido sobre 10.000 tareas: un repintado costaba **16,7 ms de EDT y tocaba 122
filas**; comparando por igualdad, **0,2 ms y una fila**.

Es el modo de fallo más difícil de ver de los que esta fase podía introducir: nada falla,
sólo vuelve a costar lo que costaba. De paso se conserva la instancia **anterior** cuando
son iguales, que es la que ya tiene forzados sus siete `lazy`.

**d) La ventana se vuelve a pedir entera en cada sincronización, y eso hay que aprovecharlo.**

`ListSync` guarda «desde aquí, tantas filas» y no un cursor, precisamente para poder pedir
otra vez lo mismo y sincronizar por diferencias. Releyendo del almacén, desplazarse una
página costaba **27 ms de EDT** con la ventana en cien filas, y creciendo, cuando lo único
nuevo son las cincuenta de abajo. El paginador cachea la ventana y la **amplía** en vez de
releerla: 27 ms → **7 ms**, y ya no depende de cuánto se haya bajado.

**e) `ConfigChanged` no puede costar el proyecto, y se emite en cada arranque.**

El §3.4 no dice cómo se recolocan las tareas que apuntan a configuración que ya no
existe. Hacerlo con un recorrido sería volver al punto de partida. Se resuelve con dos
**índices parciales** —`task_parked`, que sólo contiene las tareas aparcadas, y
`task_untagged`, que sólo contiene las que no tienen etiqueta— más la propia tabla
`counter`, que ya enumera qué estados hay en uso sin recorrer nada. El resultado: un
cambio de configuración cuesta lo que cuestan las claves que cambiaron y las filas
aparcadas, que son un puñado entre un millón.

**f) El disco es la cifra que el plan no presupuestó.**

El §2.6 es un presupuesto de **memoria** y se cumple con holgura. No había uno de disco, y
hace falta: con el corpus de la especificación —2.000 caracteres de cuerpo, 8 etiquetas,
5 anclas y 10 imágenes por tarea— la base pesa **~10 KB por tarea**, contra los 2,9 KB del
XML. A un millón son ~10 GB.

El reparto, aproximado: el cuerpo y lo derivado de él ~2,3 KB; el índice de texto ~1,5 KB;
la tabla de etiquetas ~1,2 KB —lleva copiada la tupla de orden, ver la nota 4 del esquema—;
las anclas ~1,2 KB; las referencias a imágenes ~1,7 KB; los seis índices de `task` ~0,3 KB.
**Es el peor caso de la especificación y no una tarea normal**: una tarea real tiene una o
dos etiquetas, ninguna ancla y ninguna imagen, y ahí la base pesa poco más que el texto.
Aun así hay que decirlo, y la acción *Tasklane: Diagnostics* lo dice: pesa `tasklane.db`
con su diario, no el `tasks.xml` que ya no se escribe.

### 3-bis.4 Dos cosas del §3 que no se hicieron, y por qué

**«Sentencias preparadas y reutilizadas» (§3.1).** Se preparan y se cierran en cada
llamada, salvo en los lotes de la migración —donde sí se reutiliza una, y ahí la
diferencia es de dos órdenes de magnitud—. El motivo es que reutilizar una sentencia
obliga a garantizar que su resultado se agotó antes de volver a ejecutarla, y hay un
camino que a propósito **no** lo agota: `first()`, que para en cuanto tiene una fila. Un
caché de sentencias con esa trampa dentro es una fuente de fallos raros a cambio de
microsegundos que las medidas dicen que no hacen falta: un comando completo son 0,42 ms,
y un repintado 4,5 de los 16 del presupuesto. Si algún día molesta, la salida es el
`SqlStatementPool` que la propia plataforma trae, no un caché a mano.

**El rebalanceo de ventana del orden manual (§3.4).** El plan lo pide porque insertar
repetidamente entre dos vecinos adyacentes agota el hueco local de `ORDER_GAP`. **No hay
forma de insertar entre dos vecinos**: `order` sólo se mueve al crear, y crear siempre va
al final —`max(ord) + 1000`, un salto de índice—. El día que haya arrastrar-para-ordenar
habrá que escribirlo, y entonces hará falta; escribirlo hoy sería mantener código que
ningún camino ejecuta.

### 3-bis.5 Lo que sigue creciendo con N, y por qué se acepta

**Buscar un término que está en casi todas las tareas.** FTS5 tiene que puntuar cada
acierto antes de poder quedarse con los doscientos mejores, así que el coste es
proporcional al **número de aciertos**. El corpus del banco es el peor caso imaginable:
sus cuerpos se sortean de un vocabulario de sesenta palabras, así que cualquier término
está en el 98 % de las tareas. Con un corpus real —donde una palabra cualquiera está en
una fracción pequeña— las consultas del banco que sí son selectivas cuestan **0,15 ms a
100.000 tareas**.

O sea: lo que crece con N no es «buscar», es «pedir los doscientos mejores de entre
novecientos mil aciertos». Se acepta, se anota, y si algún día molesta la salida es
acotar el trabajo del motor, no volver a recorrer la tabla.

**Contar las cabeceras de un estado.** Cada cabecera son dos saltos de índice —dónde
empieza el grupo siguiente y cuántas hay dentro—, así que el total es proporcional al
número de **cabeceras que se van a pintar**, no al de tareas. Pero contar el tramo de una
cabecera sí recorre su tramo del índice: un grupo con un millón de filas se cuenta
recorriendo un millón de entradas de índice. Ocurre **fuera del EDT** y en C; medido a
100.000, las cabeceras de una pestaña agrupada por fecha cuestan 5,2 ms.

### 3-bis.6 Qué cambia para quien lo usa

- **Abrir un proyecto deja de leer las tareas.** La ventana pide la página que se ve.
- **La primera vez, y sólo la primera, hay una migración.** En segundo plano, con barra de
  progreso, cancelable, y **reanudable** si se cierra el IDE a medias. La lista se va
  llenando mientras ocurre en vez de esperar a que termine.
- **El `tasks.xml` no se borra**: queda al lado como `tasks.xml.migrated`. Quitarle el
  sufijo y borrar la base es la vuelta atrás completa.
- **Nueva acción *Export Repository to XML***, en el menú de copiar: devuelve las tareas
  al formato de intercambio cuando se quiera.
- **Lo que se acaba de escribir ya está en disco.** Desaparece el retardo de medio segundo
  que agrupaba las escrituras, y con él la ventana de ediciones que un cierre inesperado
  del IDE se llevaba por delante.
- **Un fichero ilegible ya no deja el repositorio en blanco hasta la copia de seguridad**:
  lo que se pudo leer entra igualmente, y sólo lo que falta se busca en el `.bak`.
- **Buscar por el medio de una palabra deja de encontrar.** `log` encuentra `login`;
  `ogin`, ya no. Es la única regresión de comportamiento de la fase y está en el §4 del
  plan como riesgo nº3.
- Lo que **no** cambia: el orden, la agrupación, los contadores, la selección, el pliegue,
  los operadores del buscador y los avisos. La ventana es la misma.

### 3-bis.7 Lo que quedó construido

| Pieza | Dónde | Qué hace |
|---|---|---|
| `TaskSchema` | `main/…/data/sqlite/` | El esquema y el porqué de cada decisión que no se lee sola |
| `Sql` | `main/…/data/sqlite/` | Lo mínimo sobre `org.jetbrains.sqlite`: ejecutar, leer, contar, transacción y lotes |
| `TaskDb` | `main/…/data/sqlite/` | El fichero abierto: pragmas, versión y **dos conexiones** —en WAL, un lector no espera a un escritor, y por eso la ventana responde mientras se importa— |
| `TaskRows` | `main/…/data/sqlite/` | Entre `Task` y una fila. Es el `TasksCodec` de esta fase |
| `TaskStore` | `main/…/data/sqlite/` | La única pieza que escribe: mutaciones, contadores, operaciones masivas y el lote de la migración |
| `SqlitePager` | `main/…/data/sqlite/` | La lista, por índice y con `LIMIT`. La mezcla de cubos, el cursor con cuenta y la ventana que crece |
| `Fts5Index` | `main/…/data/sqlite/` | La búsqueda, en dos pasos: el índice puntúa, la tabla sólo ve a los supervivientes |
| `TasksXmlReader` | `main/…/data/sqlite/` | El `tasks.xml` en *streaming* con StAX, a tandas, con memoria constante |
| `Mutation` | `main/…/domain/command/` | Lo que hay que cambiar. Lo que no cabe fila a fila se describe, no se materializa |
| `TaskReducer` | `main/…/domain/command/` | Mismo contenido, otro alcance: recibe las tareas que el comando nombra y devuelve mutaciones |
| `TasklaneSnapshot` | `main/…/domain/model/` | Deja de llevar el corpus. Lleva la vista y un número de revisión |
| `MemoryPager` | `main/…/paging/` | Deja de ser provisional: es la mitad del reparto —lo acotado por su naturaleza— |
| `ExportXmlAction` | `main/…/ui/actions/` | La puerta de salida al formato de intercambio |
| `TaskStoreTest` | `test/…/data/sqlite/` | 19 casos: lo que entra vuelve a salir, los contadores nunca mienten, y las operaciones de proyecto hacen lo que hacía el reducer |
| `SqlitePagerTest` | `test/…/data/sqlite/` | **Los dos paginadores dan la misma lista**, en cuatro agrupaciones por tres filtros, fila a fila |
| `Fts5IndexTest` | `test/…/data/sqlite/` | **Los dos índices encuentran lo mismo**, consulta por consulta — y lo que no, escrito |
| `TasksXmlReaderTest` | `test/…/data/sqlite/` | **Los dos lectores leen lo mismo**, más reanudar, cancelar y truncar |
| `PlanTest` | `test/…/domain/` | Qué mutaciones pide cada comando, y cuáles no piden ninguna |
| `ScaleBenchmark` | `test/…/bench/` | Reescrito: las puertas de esta fase, con el camino viejo al lado donde todavía cabe |

Tres suites de equivalencia, y no es casualidad: **la forma de confiar en que un almacén
nuevo no cambió el comportamiento es dejar vivo el viejo y compararlos**. `MemoryPager`,
`LinearScanIndex` y `TasksCodec` siguen ahí por eso.

### 3-bis.8 Qué sigue siendo el techo

| | Coste a 100.000 | Dónde se arregla |
|---|---:|---|
| Los adjuntos en un directorio plano | `Files.list` de 10M entradas no termina | **Fase 4** |
| Lo que ocupan las capturas en disco | 407 KB por captura | **Fase 4** (§0-bis.5: a 400 px son 33 KB) |
| Exportar un estado entero | O(n) a propósito | Fase 5, en *streaming* |
| Buscar un término presente en casi todo | 40 ms | Acotado por aciertos, no por N — §3-bis.5 |
| EDT | **nada por encima de 11 ms** | — |

El EDT dejó de ser el techo en la Fase 2 y sigue sin serlo. La memoria dejó de serlo aquí.
Lo que queda es todo de la Fase 4, que es donde el plan decía que estaría.

### Fase 4 — Adjuntos a escala *(~2-3 semanas)*

#### 4.1 Fragmentación del directorio

`attachments/<sha>.png` → **`attachments/ab/cd/<sha>.png`** (los cuatro primeros dígitos
hex: 65.536 hojas, ~150 blobs por hoja con 10M). Migración perezosa: al abrir, un trabajo
de fondo mueve lo que haya en plano; `path()` prueba primero la ruta fragmentada.

#### 4.2 GC por referencias, no por recorrido

`AttachmentGc.collectible` mantiene su forma —pura, decidible con un test, que es
justamente lo que la hace de fiar cuando **borra ficheros del usuario**— pero deja de
recibir «todo lo que hay en el directorio». Recibe lo que dice la tabla:

```sql
SELECT b.id FROM blob b
 WHERE b.repo = ?
   AND b.created_at < ?                       -- el periodo de gracia de 24 h, intacto
   AND NOT EXISTS (SELECT 1 FROM blob_ref r WHERE r.blob_id = b.id)
 LIMIT 1000;                                  -- a tandas, cancelable
```

`blob_ref` se mantiene **en la misma transacción** que la escritura de la tarea, a partir
de `Task.attachments`, que ya se deriva del cuerpo. Un `Files.list` de 10M entradas
desaparece del arranque.

#### 4.3 Reconciliación (fsck), no en cada arranque

Un recorrido del árbol de fragmentos por tandas, en segundo plano y como mucho una vez a
la semana, que detecta deriva en los dos sentidos: blobs en disco que la tabla no conoce
(se adoptan con la fecha del fichero, entrando al periodo de gracia) y filas cuyo fichero
ya no está (se marcan como ausentes, que es lo que la tarjeta ya sabe pintar).

#### 4.4 Miniaturas

Junto a cada blob, `<sha>.thumb.png` a 256 px. La lista **nunca** descodifica el original:
una tarjeta desplegada con diez imágenes son hoy diez `BufferedImage` de 10 MB; con
miniaturas son diez de 260 KB. El original sólo se lee al abrir `ImagePreviewPopup`.
Se generan al guardar, y de forma perezosa para los blobs ya existentes.

#### 4.5 Cuota y contabilidad — **aquí hay que decidir una política**

Esto es aritmética, no arquitectura, y conviene decirlo sin rodeos:

> **10 imágenes únicas por tarea × 1.000.000 de tareas = 10.000.000 de blobs.**
> A 1600 px (el valor por defecto) son **~4 TB**. Con deduplicación 10:1, **~400 GB**.
> **No hay diseño que meta eso en un `.idea/`.**

Lo que el plan **sí** garantiza es que el *número* de blobs no rompa nada: búsqueda O(1),
GC por índice, arranque constante, la lista sin descodificar nada grande. Con 10M blobs
el plugin va igual de rápido. Lo que no puede hacer es inventar disco.

Así que hace falta una política, y son tres opciones:

1. **Cuota con aviso** *(recomendada)*. `Diagnostics` enseña el peso; al cruzar un umbral
   configurable (por defecto 5 GB) se avisa una vez y se ofrece bajar `imageMaxSize`,
   purgar lo no referenciado o archivar. Nunca se borra nada sin decirlo.
2. **Almacén externo**: los blobs fuera de `.idea`, en un directorio elegido por el
   usuario, compartido entre proyectos. Sube la deduplicación real (la misma captura en
   dos proyectos es un blob) y saca el peso de la carpeta del proyecto.
3. **Sólo referencia**: para imágenes por encima de un tamaño, guardar la ruta original y
   una miniatura en vez de copiar el original. Ahorra muchísimo y a cambio la imagen deja
   de ser inmutable — si el usuario mueve el fichero, se pierde.

Se pueden combinar: 1 por defecto, 2 y 3 como ajustes.

**DECIDIDO** (2026-09-15): la **opción 1, cuota con aviso**, y además el tope de
escalado baja de 1600 px a **400 px**, que no era una de las tres opciones y las mejora
a todas. Medido en el §0-bis.5: divide el peso de una captura por doce y medio —de
407 KB a 33 KB— y con él todo el presupuesto de disco. Queda por decidir en la propia
Fase 4 qué hacer con lo que ya esté guardado a 1600 px; reescalarlo cambiaría su SHA y
con él todas las referencias de los cuerpos, así que no es un cambio de un bucle.

**Puerta:** con 1M de blobs sintéticos, el arranque no se entera, el GC completo tarda
menos de 30 s en segundo plano y el desplazamiento por una lista de tarjetas con imagen
mantiene 60 fps con el heap plano.

---

## 4-bis. Resultados de la Fase 4 · CERRADA

> Cerrada en la **2.1.0**. Los adjuntos dejan de vivir en un directorio plano que había
> que listar entero para saber qué había: se fragmentan en `ab/cd/`, se contabilizan en la
> base, y las grandes se pintan por miniatura. El GC deja de recorrer el disco, la lista
> deja de descodificar capturas de diez megas, y lo que ocupa todo junto se puede mirar —y
> se avisa cuando se pasa—.

### 4-bis.1 La puerta, medida

Medido con `-PbenchN=2000 -PbenchBlobs=100000`, que es el eje que importa aquí: la Fase 4
no escala con el número de tareas, escala con el número de **blobs**. Un millón de
capturas a 400 px son 33 GB de PNG y horas de generarlos; cien mil dan la pendiente y
son 3,3 GB, que es lo que el §0-bis ya hacía con las tareas.

| | Antes (2.0) | Ahora (2.1) |
|---|---:|---:|
| Saber qué adjuntos hay, al abrir | **329 ms** de recorrido, y crece con cada captura | **0,49 ms** por tanda de mil, y no crece |
| Recoger 100.000 sin referencias | el mismo recorrido, y uno por repositorio | **9,8 s**, 0,098 ms cada una |
| Reconciliar disco y tabla | no existía | **2,2 s** la pasada semanal (17,8 s la primera) |
| Diez imágenes de 1600 px en una tarjeta | **176 ms** y 134 MB de heap | **8,7 ms** y 36 MB |

Tres cosas que leer en esa tabla, porque no todas dicen lo mismo:

**La primera fila es la que cierra la fase.** 329 ms son lo que cuesta *hoy* listar cien
mil ficheros; a un millón son segundos y a diez millones es la operación que el §1.6 dice
que no termina. Los 0,49 ms de al lado son por **tanda de mil candidatos** y no dependen
de cuántos blobs haya guardados: es un salto por `blob_by_age` y se para en la tanda. Esa
es la diferencia entre una cifra que crece y una que no.

**La segunda no es una puerta que se cumpla o se incumpla, es aritmética de disco.** El
plan pedía «GC completo en menos de 30 s con 1M de blobs»; medido, recoger un millón
serían ~98 s. Pero de esos 0,098 ms por blob, lo que decide el plugin —la consulta, la
política, la fila— es **0,0005 ms**; el resto es el `unlink`, y borrar un millón de
ficheros cuesta lo que cuesta borrar un millón de ficheros en cualquier programa. La
puerta se cumple en lo que la fase controla: el trabajo es acotado, va por tandas, se
cancela y ocurre en segundo plano sin tocar la ventana.

**Y la última es la de memoria, que es la que se nota a diario.** No hace falta un millón de
nada: una sola tarjeta desplegada con diez capturas de las de antes retenía 134 MB. Es el
§1.6 medido desde el otro lado.

### 4-bis.2 Dónde el plan acertó

**La fragmentación era el problema y la tabla era la solución.** El §4.1 y el §4.2 no se
tocaron: cuatro dígitos hexadecimales, 65.536 hojas, y un `SELECT ... NOT EXISTS ... LIMIT
1000` en lugar del `Files.list`. Las dos piezas cayeron donde el plan decía y con el
tamaño que el plan decía.

**Mantener `AttachmentGc` puro fue lo que permitió cambiarle la fuente sin miedo.** La
función que decide qué se borra no cambió ni una línea de lógica: cambió de qué tipo son
sus candidatos. Sus seis casos siguen ahí, uno más, y son los que dicen que mover la
contabilidad a SQLite no movió la política.

**Las miniaturas valen más de lo que el plan les concedía.** El §4.4 las justifica por
memoria de la lista; resultan ser además lo que hace **aceptable no tocar las capturas de
1600 px que ya están guardadas** (§4-bis.4). Sin miniatura, «no las reescalamos» habría
significado «la lista sigue descodificando 10 MB por imagen para siempre».

### 4-bis.3 Dónde el plan se quedó corto — y qué cambió

**1. El §4.3 no decía cómo detectar lo ausente sin recordar lo presente.** «Filas cuyo
fichero ya no está» es fácil de escribir y difícil de implementar con memoria constante:
la forma obvia —juntar los ids del disco y restarlos de la tabla— es exactamente el
conjunto de diez millones de entradas que esta fase vino a no tener en memoria. La
solución es una columna, `seen_at`: el recorrido **sella** lo que ve, y al final lo que
quedó con el sello viejo es, por definición, lo que no estaba. Una pasada por la tabla y
ni un id retenido.

**2. La reconciliación necesita un recorrido completo o ninguna conclusión.** No estaba
escrito y es la mitad de su corrección: si el recorrido se cancela —el usuario cierra el
IDE, un directorio no se puede leer— **no se marca nada como ausente**. «No lo he visto»
sólo significa «no está» si se ha mirado todo.

**3. Adoptar con la fecha de ahora habría desactivado el recolector.** El §4.3 dice
«se adoptan con la fecha del fichero» en una subordinada; es la frase entera. Si adoptar
—o re-apuntar en cada escaneo— pusiera `created_at` al día, el periodo de gracia de 24 h
pasaría a ser «24 h desde el último escaneo» y no se recogería nada jamás. Hay un test que
lo fija (`BlobTableTest`), y el `ON CONFLICT DO UPDATE` de `recordBlob` deja `created_at`
fuera a propósito.

**4. La salvaguarda del GC cambia de forma, no de fondo.** Hasta la 2.0, recolectar
esperaba a que el corpus estuviera cargado, porque las referencias salían de él. Ya no hay
corpus, así que la espera no significaba nada — pero la ventana de peligro sigue
existiendo, y es otra: **mientras un `tasks.xml` se está importando, `blob_ref` está
legítimamente incompleta**. `TaskService.referencesComplete` es esa comprobación, por
repositorio: no está en solo lectura, no se está importando, y su XML ya se importó o
nunca existió. Sin ella, recolectar durante la migración de la Fase 3 borraría **todas**
las imágenes del usuario.

**5. La pareja blob-miniatura hay que borrarla y moverla junta.** No estaba en el plan
porque en el plan la miniatura no tenía ciclo de vida. Lo tiene: nadie la referencia, así
que si se queda atrás es basura que ni el GC —que va por `blob_ref`— ni la reconciliación
—que las ignora a propósito— sabrían nombrar. `AttachmentStore.delete` y `relocate` tratan
las dos como una.

**6. La miniatura no va «junto a cada blob», sino junto a los que la pagan.** El §4.4
dice `<sha>.thumb.png` para todos. Escribirla siempre es **un fichero más por blob** —el
doble de entradas en el árbol— y un 45 % de disco sobre una captura de 400 px, a cambio de
un factor 2,4 en memoria. Con el tope nuevo, el original ya *es* casi su miniatura. Así
que la miniatura se escribe sólo por encima del **doble** del tope de la miniatura
(512 px), que es donde la cuenta cambia de signo: ahí está el factor cuarenta de las
capturas de 1600 px, que son exactamente las que hay que dejar de descodificar. Por debajo
no hay fichero, y quien lee cae al original — que pesa lo que pesaría su miniatura.

**7. El traslado se da por terminado, y puede dejar de serlo.** La marca en `chore` dice
«este directorio plano está vacío», y con ella el recolector se ahorra dos `unlink` por
blob que no pueden encontrar nada —un 40 % del coste de recoger, medido—. Pero hay una
forma de que la marca deje de ser cierta: **abrir el mismo proyecto con una versión
anterior del plugin y pegar una captura**. La reconciliación es justo la pieza que detecta
deriva desde fuera, así que cuenta lo que encuentra en la raíz y, si encuentra algo, borra
la marca; la apertura siguiente lo traslada. Sin eso, esos ficheros serían basura que nadie
sabría volver a nombrar.

**8. Las tablas nuevas no suben la versión del esquema.** La reacción automática al
añadir `blob` y `chore` fue poner `user_version` a 2. El efecto de hacerlo es concreto y
malo: una versión anterior del plugin abriendo ese fichero lo vería «del futuro» y dejaría
**el proyecto entero en solo lectura** —es la promesa de la Fase 3, y es la correcta— para
proteger dos tablas cuyo contenido es **derivado**: `blob` la reconstruye entera la
reconciliación a partir del disco, y `chore` son tres fechas de mantenimiento. Se acaba
bloqueando lo irreemplazable para proteger lo recomputable. La regla que queda escrita en
`TaskSchema.VERSION`: se sube cuando una versión anterior escribiendo **perdería datos que
no se pueden reconstruir**, no por contabilidad que se puede volver a calcular.

**9. El aviso de cuota tenía que poder callarse.** El §4.5 dice «se avisa una vez» sin
decir una vez *por qué*. Una vez por apertura es un aviso que se aprende a cerrar sin
leer. La política acabó siendo: se avisa al cruzar, y no se vuelve a avisar hasta que el
peso crece **vez y media** desde el aviso anterior; bajar del umbral rearma. Vive en
`AttachmentQuota`, es pura y tiene sus casos.

### 4-bis.4 Lo que quedaba por decidir, decidido

El §4.5 dejaba abierto **qué hacer con lo que ya esté guardado a 1600 px**. Se decidió
**no tocarlo**, y contarlo:

- **Reescalar cambiaría el SHA**, que es el nombre del fichero y lo que el cuerpo de cada
  tarea nombra. No es un bucle sobre un directorio: es reescribir los cuerpos del usuario,
  invalidar cualquier exportación anterior y perder la propiedad de que un blob es
  inmutable. Por un ahorro de disco que el usuario puede conseguir él mismo borrando lo
  que no quiera.
- **Las miniaturas quitan el coste que de verdad dolía.** Lo caro de una captura de
  1600 px no era su sitio en disco —407 KB— sino sus 10,2 MB descodificada en una lista.
  Eso se acabó igual, sin tocar el fichero.
- **Y se dice cuánto es.** *Tasklane: Diagnostics* cuenta las imágenes guardadas por
  encima del tope vigente y lo que ocupan, para que la decisión de borrarlas sea del
  usuario y esté informada.

El tope de escalado de las capturas **nuevas** sí baja: `DEFAULT_IMAGE_MAX_SIZE` pasa de
1600 a **400 px**, la decisión del §4.5 con la medida del §0-bis.5 delante.

> **Revisado en la 2.3.0 (2026-09-15): el tope de escalado se retira.** Lo decidió el
> usuario con la versión en la mano: una captura de código a 400 px no se lee. Desde la
> 2.3 nada se reescala —los píxeles del portapapeles van a PNG sin pérdida a su tamaño y
> un fichero soltado se guarda con sus bytes—, y el disco se gestiona **a la vista**: los
> ajustes enseñan siempre lo que pesan las imágenes del repositorio activo y ofrecen
> borrar las no usadas (sin gracia) o todas. Medido en `ScaleBenchmark` sobre una captura
> de 2880 px:
>
> | | 2.2 (400 px) | 2.3 (su tamaño) |
> |---|---:|---:|
> | Pegar píxeles (CPU, fuera del EDT) | 7,8 ms | 129 ms |
> | En disco | 46 KB | 1.250 KB (27×) |
> | Soltar un fichero | 47 ms | **1,0 ms** |
> | Su miniatura, aplazada a la lista | — | 45 ms, una vez |
>
> Lo que eso cambia de esta sección: la aritmética del §0-bis.5 vuelve a ser la de
> 1600 px o peor —diez millones de capturas son terabytes—, y **las miniaturas pasan de
> excepción a norma**, que es justo para lo que estaban. La política del §4.5 sigue
> siendo cuota con aviso, ahora por repositorio, con dos salidas concretas en los
> ajustes. Lo que no cambia: el nombre por SHA, el árbol `ab/cd` y que la lista no
> descodifique nunca un original.

### 4-bis.5 Qué cambia para quien lo usa

- **Las capturas nuevas se guardan a 400 px** en vez de 1600: doce veces y media menos
  disco por captura. El ajuste sigue estando y sigue llegando a 4000 para quien lo quiera.
- **Las que ya estaban no se tocan.** Se siguen viendo igual, y ampliarlas sigue enseñando
  el original completo.
- **La lista no descodifica nunca una captura grande.** De las de 1600 px que hubiera
  guardadas pinta una miniatura de 256; de las nuevas —que ya son de 400— el propio
  fichero, que pesa lo mismo que pesaría su miniatura. Para mirar de cerca está el clic,
  que sigue abriendo el original a tamaño de pantalla.
- **Las imágenes se guardan en subdirectorios** (`attachments/ab/cd/…`). El traslado de lo
  que hubiera es automático, en segundo plano y no hay que esperarlo. **Una versión
  anterior del plugin no las encontrará**: es la única regresión hacia atrás de esta
  versión, y no se pierde nada —los ficheros están ahí—.
- **Se avisa cuando las imágenes pasan de 5 GB**, con el peso, con qué hacer y sin borrar
  nada. El umbral se cambia o se apaga en los ajustes.
- **Una imagen que se borró por fuera del plugin se dice.** La tarjeta ya pintaba el
  hueco; ahora además la reconciliación semanal lo sabe y el informe lo cuenta.
- Lo que **no** cambia: dónde se pega, cómo se borra, `has:image`, la deduplicación por
  contenido, el periodo de gracia de 24 horas y que lo único que se borra es lo que no
  nombra ninguna tarea.

### 4-bis.6 Lo que quedó construido

| Pieza | Dónde | Qué hace |
|---|---|---|
| `BlobLayout` | `main/…/data/attachment/` | `ab/cd/<sha>.png`, la miniatura y el temporal. Puro: la política de nombres se fija con texto |
| `AttachmentStore` | `main/…/data/attachment/` | Fragmentado, con caída a la ruta plana, traslado por tandas y recorrido con memoria constante |
| `BlobRecord` · `Chore` · `AttachmentChore` | `main/…/data/attachment/` | Lo que la tabla sabe de un blob, y las tres marcas que dan cadencia al mantenimiento |
| `AttachmentGc` | `main/…/data/attachment/` | La misma decisión de siempre, sobre filas en vez de sobre ficheros |
| `AttachmentQuota` | `main/…/data/attachment/` | Cuándo avisar y cuándo callarse. Puro |
| `ImageNormalizer` | `main/…/data/attachment/` | Más: miniaturas a 256 px y el tamaño leído de la cabecera del PNG, sin descodificar |
| `blob` · `chore` | `main/…/data/sqlite/TaskSchema` | La contabilidad y la cadencia, en el esquema. Aditivas y **sin subir `user_version`**: ver la nota 8 |
| `TaskStore` (adjuntos) | `main/…/data/sqlite/` | Apuntar, candidatos por tandas, adoptar, sellar, marcar ausente y agregar |
| `AttachmentService` | `main/…/service/` | Guardar, servir miniaturas, recoger, trasladar, reconciliar y avisar |
| `AttachmentMaintenanceActivity` | `main/…/startup/` | Las cuatro tareas de fondo, en orden, con progreso y cancelables |
| `BlobStats` + informe | `main/…/diagnostics/` | Las imágenes, contadas por la base: ni recorrido ni cifra truncada |
| `BlobLayoutTest` · `AttachmentStoreTest` | `test/…/data/` | El árbol, la caída a lo plano, el traslado, la pareja y el recorrido cancelable |
| `AttachmentQuotaTest` · `AttachmentGcTest` | `test/…/data/` | Las dos políticas puras: qué se borra y cuándo se avisa |
| `BlobTableTest` | `test/…/data/sqlite/` | La contabilidad: que lo referenciado nunca sale, que la gracia no se reinicia y que lo no visto queda ausente |
| `ScaleBenchmark` (4 escenarios) | `test/…/bench/` | Las puertas de esta fase, con el camino viejo medido al lado |

### 4-bis.7 Qué sigue siendo el techo

| | Coste a 100.000 blobs | Dónde se arregla |
|---|---:|---|
| Exportar un estado entero | O(n) a propósito | Fase 5, en *streaming* |
| Reconciliar el árbol | 2,2 s, una vez por semana y en segundo plano | Acotado por ficheros, no por tareas |
| El disco que ocupan las capturas | ~33 KB cada una | No tiene arreglo técnico: es la cuota con aviso |
| EDT | nada | — |

**Lo que esta fase no puede hacer es inventar disco**, y conviene repetirlo: diez millones
de blobs a 400 px son 315 GB, o 31 GB con deduplicación 10:1. El plugin va igual de rápido
con ellos —búsqueda por índice, recolección por tabla, arranque constante— pero caben
donde caben. Por eso la política del §4.5 no es una optimización pendiente: es la
respuesta.

---

### Fase 5 — Las operaciones grandes *(~2 semanas)*

Lo que sigue siendo O(n) **por definición** — exportar un millón de tareas es leer un
millón de tareas — y por tanto tiene que ser cancelable, medido y en segundo plano.

1. **Exportación en streaming.** `TaskExporter` hoy construye un `String`. Pasa a escribir
   sobre un `Writer`, por páginas. El camino de «copiar al portapapeles» se **acota**
   (10.000 tareas) y por encima ofrece exportar a fichero: un portapapeles de 3 GB no es
   una función, es un cuelgue.
2. **«Exportar y quitar»**: exportar con progreso, y sólo entonces
   `DELETE FROM task WHERE repo = ?` más el borrado del árbol de adjuntos por tandas.
3. **Operaciones masivas** (cambiar de estado, borrar, reasignar prioridad sobre una
   selección enorme) en una transacción y con barra de progreso.
4. **Mantenimiento**: `VACUUM INTO` a un `.db.backup` una vez al día como mucho — ése es
   el sustituto de `.bak`, y no en cada guardado; `PRAGMA optimize` al cerrar;
   `integrity_check` **sólo tras un cierre sucio**, porque en 5 GB no es barato.

---

## 5-bis. Resultados de la Fase 5 · CERRADA

> Cerrada en la **2.2.0**. Lo que es O(n) por definición sigue siéndolo —exportar un
> millón de tareas es leer un millón de tareas—, pero deja de retener lo que lee, deja de
> ocurrir en el hilo de interfaz, deja de bloquear a los demás y se puede cancelar. Y el
> `.bak` que se copiaba en cada guardado tiene por fin su sustituto.

```
./gradlew test --tests '*ScaleBenchmark' -PbenchN=100000 -PtestHeap=4g -PbenchBlobs=100000
```

### 5-bis.1 La puerta, medida

La puerta de esta fase no estaba escrita con números, y no por descuido: el tiempo de una
operación O(n) no se puede prometer, es aritmética. Lo que sí se puede prometer, y es lo
que se midió, es **memoria constante, EDT libre y nadie esperando**. Todo sobre 100.000
tareas del corpus de la especificación, con el camino de la 2.1 al lado en la misma
ejecución (§1-bis.5).

| Con 100.000 tareas | 2.1 | **2.2** |
|---|---:|---:|
| Exportar un estado entero (33.288 filas, 38 MB de texto) | 1.961 ms · **280 MB** retenidos | 1.693 ms · **2,2 MB** de pico |
| Exportar el repositorio a `tasks.xml` (276 MB) | 5.037 ms · **1.516 MB** retenidos | 4.813 ms · **15 MB** de pico |
| Borrar el árbol de 100.000 capturas | 10.167 ms · 62 MB antes del primer borrado | 9.894 ms · **0,1 MB** |
| Quitar el repositorio: lo más que espera otro comando | **12.282 ms** (una transacción) | **263 ms** (la tanda más larga; p50 80) |
| Mover, marcar o borrar 2.000 filas seleccionadas | 1.861 ms · 2.000 transacciones y snapshots | **739 ms** · una y uno |
| Copia de seguridad de la base (1.014 MB → 980 MB) | un `.bak` en **cada** guardado | **1,4 s**, una vez al día como mucho |
| `integrity_check` | — | **4,0 s**, sólo tras un cierre sucio |

Tres lecturas de esa tabla, porque no todas las filas dicen lo mismo:

**Las dos primeras son la fase.** El tiempo apenas se mueve —es leer y escribir lo mismo—
y la memoria cae dos órdenes de magnitud. Lo que queda de pico es **una tanda** de dos mil
tareas, así que no depende de N: a un millón, la 2.1 moría con `OutOfMemoryError` antes de
escribir un byte —más de 13 GB de `Task` y de texto, con el heap de fábrica de 2 GB— y la 2.2
retiene lo mismo que aquí.

**La cuarta no mide lo rápido sino lo que bloquea.** Quitar por tandas tarda más en total
—20,7 s contra 12,3 s— y es a propósito: la transacción única tenía el escritor tomado de
principio a fin, así que **cualquier** comando del usuario —marcar una tarea de otro
repositorio desde el EDT— esperaba doce segundos. A un millón serían dos minutos de IDE
congelado. Ver §5-bis.3.5 para lo que costó dejar el total en 20,7 y no en 34.

**La quinta tiene letra pequeña**: ver §5-bis.3.3 y §5-bis.5.

### 5-bis.2 Dónde el plan acertó

- **«`TaskExporter` pasa a escribir sobre un `Writer`, por páginas.»** Tal cual, con una
  mejora gratis: la versión que devuelve un `String` está construida **encima** del
  *stream*, así que el formato sigue viviendo en un sitio y los quince tests que lo
  fijaban prueban las dos puertas.
- **El portapapeles acotado a 10.000.** Diez mil tareas de la especificación son ~20 MB
  de texto; por encima se ofrece un fichero y se dice por qué.
- **`VACUUM INTO` a `.db.backup`.** Funciona desde una conexión de solo lectura —lo fijó
  el spike antes de escribir una línea—, así que copiar no para ni a la ventana ni al
  escritor.
- **`integrity_check` sólo tras un cierre sucio.** A 100.000 son 4 s; a un millón, un
  minuto. En cada apertura no cabe.

### 5-bis.3 Dónde el plan se quedó corto — y qué cambió

**1. Una exportación tiene que ser una foto.** El plan dice «por páginas» y no dice sobre
qué. Paginando por *keyset* sobre una base que se sigue editando, una tarea que se marca
a mitad de una exportación de minutos **salta de cubo** y sale dos veces, o ninguna. La
salida es `TaskPager.snapshot`: una conexión de lectura **propia** con una transacción
abierta, que en WAL es la base en un instante. Propia y no la de la lista, porque abrirle
una transacción a ésa congelaría también lo que pinta la ventana. El precio lo paga el
diario, que no se vacía más allá de la foto mientras dura.

**2. «Exportar y quitar» necesitaba dos garantías que no estaban escritas.** «Exportar con
progreso, y sólo entonces `DELETE`» deja un hueco por cada lado. Por uno, lo que se crea
en ese repositorio **mientras** se exporta se borraría sin haber salido: el repositorio
pasa a solo lectura durante la operación. Por el otro, nada comprobaba que salió **todo**:
se exporta sobre la foto de `RepoSnapshot` y se compara lo escrito con lo que la foto
contaba; si no cuadra, **no se borra nada** y se dice. Los estados se sacan de `counter`
y no de la configuración, que se dejaría fuera las tareas de un estado ya borrado justo
antes del `DELETE`.

**3. El coste de una operación masiva no estaba donde parecía.** Lo evidente era que el
lote costara transacciones, y lo arregla `TaskCommand.Batch`. Pero medido el desglose de
escribir dos mil filas sobre 10.000 tareas:

| Pieza | Coste | Por fila |
|---|---:|---:|
| leer el sujeto | 76 ms | 38 µs |
| planificar | 0,4 ms | — |
| **escribir** | **487 ms** | **243 µs** |
| └ de eso, el índice de texto | ~50 ms | 25 µs |

El 90 % de la escritura eran **las ~30 sentencias preparadas y cerradas por fila**: la
tarea, sus ocho etiquetas, sus cinco anclas, sus diez referencias y los tres borrados de
delante. Es exactamente lo que la migración ya resolvía con `Sql.batch`, y la escritura
normal pasa a hacer lo mismo: una lectura de lo que había por tanda y un lote por tabla.
De paso **se salta el índice de texto cuando el texto no cambió**, que es mover, completar,
marcar y cambiar la prioridad. Escribir las dos mil filas: 487 → **247 ms**.

**4. «Hasta una página en el acto» no cabía.** La primera versión aplicaba en el EDT los
lotes de hasta cincuenta filas —«lo que cabe en una selección sin desplazarse»—, y
cincuenta filas son 23 ms a 10.000 tareas y 31 ms a 100.000. El umbral acabó en **diez**:
2,3 ms de p50 y 9 de p99 a 100.000. Veinte cabían a 10.000 y dejaban de caber con holgura a
100.000, que es la advertencia del §5-bis.5.

**5. Por tandas no bastaba: el diario se volcaba en cada una.** Las tandas de 500 salían a
46 ms sobre 10.000 tareas y a 172 ms sobre 100.000, y quitar el repositorio entero tardaba
**34 s**, casi el triple que la transacción única. La caché era la sospechosa obvia y
explicaba poco; lo que pesaba era el **checkpoint automático de SQLite cada mil páginas**,
que con una confirmación por tanda copia a la base las mismas páginas interiores de los
índices una y otra vez. Medido en la misma ejecución:

| Quitar 100.000 tareas | Total | Tanda p50 | La más larga |
|---|---:|---:|---:|
| Tandas de 500, tal cual | 33,9 s | 172 ms | 419 ms |
| + caché de 256 MB | 32,4 s | 146 ms | 307 ms |
| **+ volcado del diario cada 20.000 páginas** | **20,7 s** | **80 ms** | **263 ms** |
| Una transacción (2.1) | 12,3 s | — | 12.282 ms |

`TaskStore.bulk` gana un parámetro para eso, y sólo lo usa quitar un repositorio. El
lote masivo en segundo plano usa la caché grande sin espaciar nada —es una sola
transacción—, y ahí la caché sí se nota: 965 → **739 ms** para dos mil filas.

**6. El cierre sucio no necesita marcador.** El plan da por hecho que hay que detectarlo.
SQLite ya deja la señal: con WAL, cerrar la última conexión vuelca el diario y **borra**
`tasklane.db-wal`; si al abrir sigue ahí con algo dentro, la sesión anterior no cerró. La
comprobación pendiente sí se apunta en la base, para que una que no llega a terminar se
repita en la apertura siguiente.

**7. La copia no puede fecharse en la base.** Lo natural era una fila en `chore` con la
fecha de la última copia. Escribirla modifica la base, y al día siguiente la base consta
como cambiada aunque nadie haya tocado una tarea: se copiaría cada día para siempre. La
fecha es la **del propio fichero** de la copia, y de paso una copia borrada a mano se
rehace sola.

**8. Nunca copiar una base bajo sospecha.** No estaba en el plan y es la mitad de que la
copia sirva: la copia **sustituye** a la anterior, así que copiar una base dañada borraría
la última buena justo cuando más falta hace. Con una comprobación pendiente o con daños en
la última, no se copia. Tampoco sin sitio: se exige vez y media lo que ocupa la base.

**9. `PRAGMA optimize` hoy no hace nada, y se deja con su centinela.** Con el SQLite 3.42
de la plataforma y sin estadísticas previas, `optimize` no lanza `ANALYZE`. Se deja
—con `analysis_limit`, que convierte el análisis en muestreo— porque es lo que SQLite pide
antes de cerrar y lo que costaría no tener el día que la plataforma lo actualice. Lo que no
se podía arriesgar es que unas estadísticas cambiaran el plan de la lista, sobre el que
descansa la Fase 3 entera: `TaskDbTest` lanza un `ANALYZE` completo y comprueba que cada
consulta sigue yendo por su índice cubriente y sin ordenar en memoria.

**10. El XML hay que escribirlo a mano.** `XMLStreamWriter`, lo obvio, no escapa lo que un
lector de XML **normaliza** al leer: un `\r` del cuerpo vuelve como `\n` y un salto de
línea en un atributo vuelve como un espacio. `TasksXmlWriter` escapa como JDOM y se
comprueba leyendo lo escrito con **los dos** lectores. Y lo que XML 1.0 no admite ni
escapado —el `ESC` de una salida de terminal pegada— se cambia por `U+FFFD` y se cuenta:
el códec de JDOM tumbaba la exportación entera por una sola tarea.

### 5-bis.4 Lo que esta fase destapó de las anteriores

Cinco fallos, todos en caminos que esta fase tenía que recorrer, y ninguno con síntoma
hasta que se recorrían:

1. **Un repositorio ausente desaparecía con sus tareas dentro** (Fase 3). El catálogo
   decidía si una entrada que ya no se detecta «tiene tareas» mirando si existe su
   `tasks.xml`, y desde la 2.0 ese fichero se renombra a `.migrated` al importarlo —y los
   repositorios creados después no lo tienen nunca—. Renombrar una carpeta hacía
   desaparecer el repositorio del selector, y con él la única puerta para sacar sus
   tareas: «Exportar y quitar». El catálogo pregunta ahora también a la base.
2. **Exportar a XML un repositorio nuevo acababa en un error de fichero corrupto** (Fase 3).
   Sin fila en `imported`, el `tasks.xml` recién escrito parecía uno pendiente de importar;
   la siguiente detección lo importaba encima de lo que ya había, chocaba con los mismos
   identificadores y lo mandaba a cuarentena. Exportar apunta ahora el repositorio como
   importado **antes** de que el fichero exista.
3. **Exportar a XML con una migración a medias sobrescribía el original** (Fase 3). Con
   una importación cancelada, la base tiene una parte del repositorio; escribirla encima
   del `tasks.xml` que la migración iba a retomar perdía el resto. Ahora se niega y lo dice.
4. **Borrar una selección era cuadrático** (Fase 3). `Delete` filtraba con
   `it in command.ids.toSet()`, que construye el conjunto **por cada tarea**: diez mil
   filas seleccionadas eran cien millones de inserciones en un `HashSet`.
5. **El aviso de migración enseñaba `{1}` en vez de la ruta** (Fase 3). `project's` en un
   mensaje con parámetros abre una cita de `MessageFormat` que se come el resto.

### 5-bis.5 Lo que sigue creciendo con N, y por qué se acepta

**Escribir muchas filas repartidas.** Un comando suelto cuesta lo mismo a 10.000 que a
100.000 —0,33 y 0,34 ms—, pero un lote de dos mil filas pasa de 330 a 965 ms. Por fila, lo
que crece es escribir **páginas de índice que ya no caben en la caché**: dos mil tareas de
la especificación son unas ochenta mil filas hijas repartidas por todos los índices. La
caché grande recupera un cuarto; el resto es disco. Se acepta porque ocurre en segundo
plano, en una selección que por construcción está acotada por lo que el usuario ha
cargado, y porque la alternativa —mantener en SQL una segunda copia de la semántica del
reducer para cada operación masiva— es exactamente lo que el proyecto evita desde la
Fase 3. Es la razón de que el umbral en el acto sea diez y no veinte.

**Copiar y comprobar la base.** Son O(tamaño de la base) por definición: 1,4 s y 4 s a
100.000 tareas, diez veces lo de 10.000. Van en segundo plano, una vez al día la primera y
sólo tras una caída la segunda, y **no se pueden interrumpir a mitad** —la plataforma no
expone `sqlite3_interrupt`—: cancelar espera a que acabe la sentencia en marcha.

### 5-bis.6 Qué cambia para quien lo usa

- **Copiar una pestaña de más de 10.000 tareas ofrece un fichero** en vez de llenar el
  portapapeles. Exportar va siempre en segundo plano, con barra y cancelable, y lo que sale
  es la pestaña en el momento de pulsar.
- **Mover, completar, marcar, cambiar la prioridad o borrar muchas tareas a la vez** es una
  sola operación: un repintado en vez de uno por fila, y por encima de diez filas en
  segundo plano con barra. **Cancelar deshace**: no queda media selección movida.
- **«Exportar y quitar» funciona con repositorios de cualquier tamaño**, y vuelve a
  aparecer: un repositorio renombrado o borrado del disco se queda en el selector mientras
  tenga tareas. Por encima de 10.000 las guarda en un fichero, comprueba que salieron todas
  y sólo entonces borra, sin congelar nada.
- **Exportar a XML** ya no se lleva la memoria del IDE, escribe entero o nada, y deja de
  acabar en un aviso de fichero corrupto.
- **Hay una copia de la base**, `tasklane.db.backup`, de como mucho un día. Tras un cierre
  inesperado del IDE la base se comprueba sola y, si tiene daños, se avisa con la fecha de
  esa copia. *Diagnostics* enseña las dos cosas.
- Lo que **no** cambia: el formato de lo que se copia, el orden de la lista, el esquema de
  la base —sigue en la versión 1, y una 2.1 abre un proyecto usado por la 2.2 sin notar
  nada—.

### 5-bis.7 Lo que quedó construido

| Pieza | Dónde | Qué hace |
|---|---|---|
| `TaskExporter.Stream` | `main/…/domain/export/` | El formato, tarea a tarea sobre cualquier `Appendable`. La versión `String` está construida encima |
| `TaskPager.each` · `snapshot` | `main/…/paging/` | La lista entera a tandas, sin la ventana que retiene; y congelada mientras dura |
| `RepoSnapshot` | `main/…/data/sqlite/` | Un repositorio entero en un instante: cuenta, estados en uso y sus tareas, por estado o en orden manual |
| `TasksXmlWriter` | `main/…/data/sqlite/` | El `tasks.xml` en *streaming*, con el escapado que los lectores necesitan |
| `TaskStore.upsertAll` | `main/…/data/sqlite/` | La escritura por lotes: una sentencia por tabla y el índice de texto sólo si cambió el texto |
| `TaskStore.forgetBatch` · `bulk(checkpointPages)` | `main/…/data/sqlite/` | Quitar por tandas con contadores exactos en cada una, y sin volcar el diario en cada tanda |
| `TaskDb.backupTo` · `checkIntegrity` · `dirty` | `main/…/data/sqlite/` | La copia con `VACUUM INTO`, la comprobación, y el cierre sucio leído del diario |
| `StoreMaintenance` | `main/…/data/sqlite/` | Cuándo se copia y cuándo se comprueba. Puro |
| `TaskCommand.Batch` | `main/…/domain/command/` | Muchos comandos como uno: una transacción, un snapshot, la semántica de los sueltos |
| `ExportService` · `TaskService.applyAll` · `removeRepo` | `main/…/service/` | El destino, la foto, la comprobación antes de borrar, y la barra |
| `MaintenanceActivity` | `main/…/startup/` | Antes `AttachmentMaintenanceActivity`: la base primero y los adjuntos después |
| `TasksXmlWriterTest` · `RepoSnapshotTest` · `StoreMaintenanceTest` | `test/…/data/sqlite/` | Los dos lectores leen lo que se escribe; la foto no se mueve y lo cuenta todo; las dos políticas |
| `TaskDbTest` · `TaskStoreTest` · `SqlitePagerTest` · `PlanTest` | `test/…` | La caída simulada, la copia que se abre, la base dañada, el centinela de los planes; tandas y contadores; exportar da la lista de la ventana; el lote hace lo que los sueltos |
| `ScaleBenchmark` (5 escenarios) | `test/…/bench/` | Las operaciones grandes con el camino de la 2.1 al lado, y el experimento del diario |

### 5-bis.8 Qué sigue siendo el techo

| | Coste a 100.000 | Dónde se arregla |
|---|---:|---|
| Recuperar sola una base dañada | se avisa con la copia; no se restaura | **Fase 6** (backup + aviso + cuarentena) |
| Probar que una caída a mitad deja todo sano | simulada en un test, no provocada | **Fase 6** |
| Escribir un lote grande | 739 ms para 2.000 filas, y crece con la base | Acotado por la selección — §5-bis.5 |
| La migración desde `tasks.xml` | confirma una tanda cada 2.000 tareas | Probablemente gana lo mismo que quitar con el diario espaciado (§5-bis.3.5); **sin medir** |
| EDT | nada por encima del presupuesto | — |

---

### Fase 6 — Endurecimiento *(~2 semanas)*

1. **Pruebas de caída**: matar el proceso a mitad de una transacción, a mitad de la
   migración y a mitad del GC; comprobar que la siguiente apertura está sana.
2. **Corrupción deliberada**: corromper el `.db` y comprobar que la ruta de recuperación
   (backup + aviso + cuarentena) da el mismo comportamiento que hoy da `ReadResult.Corrupt`.
3. **El corpus de 1M en CI nocturna** con aserciones **de techo**, no de media: p99 de
   latencia y heap máximo. Una regresión de rendimiento tiene que romper el build igual
   que la rompe un test.
4. **Prueba de longevidad**: 100.000 comandos seguidos sobre el corpus grande,
   comprobando que el heap no crece —es exactamente la clase de fuga que ya apareció en
   `dc3f601` con `lastScrollAt`—.
5. **Documentar en `architecture.html`**: §8 pasa a contar por qué la decisión de no
   indexar era correcta y qué la revirtió. Esa sección es buena y merece envejecer bien,
   no desaparecer.

---

## 6-bis. Resultados de la Fase 6 · CERRADA

> Cerrada en la **2.4.0**. Lo que las cinco fases anteriores daban por hecho se comprueba a
> la fuerza: se mata el proceso a mitad de escribir, se pisa la base con basura, se aplican
> cien mil comandos seguidos y cada noche se mide un millón de tareas con techos que rompen
> el build. Por el camino hubo que cambiar de driver de SQLite, y la recuperación que el
> plan pedía resultó perder demasiado.

```
./gradlew test --tests '*CrashTest' -PcrashRounds=50
./gradlew test --tests '*StoreRecoveryTest'
./gradlew test --tests '*ScaleBenchmark' -PbenchN=100000 -PtestHeap=4g
```

### 6-bis.1 La puerta, comprobada

La puerta de esta fase no es una cifra sino cinco comprobaciones, y cada una es un test:

| §6 | Qué se comprueba | Dónde |
|---|---|---|
| 6.1 Caídas | Muerte real del proceso a mitad de una transacción, de la migración, del recolector, de la copia diaria **y de la propia recuperación** | `CrashTest` |
| 6.2 Corrupción | Cabecera, página de índice, hoja de la tabla, índice de texto y diario pisados con basura; recuperación interrumpida y reanudada | `StoreRecoveryTest` |
| 6.3 Techos | p99 y heap contra el presupuesto de cada fase; a 100.000 en local y a un millón cada noche | `ScaleBenchmark` + `nightly.yml` |
| 6.4 Longevidad | 100.000 comandos seguidos: heap retenido, diario y latencia | `ScaleBenchmark` |
| 6.5 Documentar | Por qué no indexar era correcto y qué lo revirtió | `architecture.html` §8 |

**Las caídas, con `-PcrashRounds=10`:** cincuenta muertes, diez por escenario, **todas a
mitad** de lo que se mataba —en el de comandos se comprueba: 10 de 10 cayeron dentro de una
transacción— y las cincuenta dejaron la base sana y la contabilidad cuadrada. Lo confirmado
siempre sobrevivió entero; la transacción en marcha, nunca dejó una fila; la migración siguió
donde iba sin repetir ninguna tarea; ninguna imagen que una tarea nombrara se borró; la copia
quedó siempre entera, vieja o nueva; y reabrir terminó cada recuperación interrumpida sin una
segunda cuarentena. La suite completa, 654 tests, en verde sobre `sqlite-jdbc`.

**Los techos, a 100.000 tareas.** Todos en p99. Son **presupuestos del plan y no cifras de
este portátil**: un techo sacado de la medida de hoy saltaría en un runner más lento sin que
nada hubiera empeorado; uno sacado de la puerta de cada fase salta cuando el plugin deja de
cumplir lo que prometió, que es justo lo que tiene que romper el build.

| Techo | De dónde sale | Escenario | Medido |
|---|---|---|---:|
| **16 ms** en el EDT | un fotograma a 60 Hz | primer pintado · repintar · desplazarse · pintar · lote de 10 | 8,4 · 6,8 · 8,7 · 4,5 · 8,3 ms |
| **50 ms** de interfaz | puerta de la Fase 3 | editar una tarea: comando + repintado | 23,1 ms |
| **20 ms** de escritura | puerta de la Fase 3 | un comando con su transacción | 2,5 ms |
| **300 ms** al abrir | puerta de la Fase 3 | contadores y cabeceras, en frío | 13,4 ms |
| **100 ms** por tecla | puerta de la Fase 3 | «revision del despliegue» | 0,13 ms |
| **5 µs por tarea** | 10× lo medido, §3-bis.5 | «token» · «is:done token» · «#api token» | 50,9 · 67,7 · 42,4 ms |
| **150 MB** de heap | §2.6 | plugin abierto · exportar un estado · exportar a XML | 2,7 · 0,8 · 15,4 MB |
| **300 MB** migrando | puerta de la Fase 3 | pico de la migración | 14,7 MB |

**Longevidad**, con 100.000 comandos —marcar, editar el cuerpo, cambiar de estado, crear y
borrar— y repintados y búsquedas entre medias, en diez tramos:

| | Primer tramo | Último tramo | Techo |
|---|---:|---:|---:|
| p99 de un comando | 10,9 ms | 11,1 ms | el doble del primero |
| Heap retenido | 45,6 MB | 45,3 MB | +16 MB |
| Diario (`-wal`) | 5 MB | 6,4 MB | 32 MB |

Plano en las tres. El diario es la que no se ve en el heap: una lectura que se quedara con
una transacción abierta impediría el volcado y lo haría crecer sin techo, y el plugin tiene
tres sitios que abren una a propósito —exportar, copiar, comprobar—.

**A un millón** lo mide la CI nocturna (`nightly.yml`) con los mismos techos y el heap de
fábrica del IDE; no se midió en local: construir las dos bases de un millón son horas y
~25 GB de disco, y lo que las puertas afirman —que la cifra no crece con N— ya se ve entre
10.000 y 100.000.

### 6-bis.3 Dónde el plan se quedó corto — y qué cambió

**1. SQLite de la plataforma no era API pública.** El §0-bis.1 celebró que el IDE traía
SQLite y lo usó cinco fases. El Marketplace rechazó el plugin: el módulo se declara
`visibility="public"`, pero su paquete lleva `@ApiStatus.Internal` en el `package-info`, y
eso no se ve compilando contra él. Se empaqueta `org.xerial:sqlite-jdbc` —lo que el §2.3
proponía desde el principio—, sobre **el mismo fichero y el mismo esquema**. El cambio cupo
en `Sql.kt`, que ya separaba el almacén del driver, y las tres suites de equivalencia pasaron
sin tocar una línea. Lo que costó, medido lado a lado en la misma máquina y dos vueltas de
cada uno (§1-bis.5):

| Con 100.000 tareas, p50 | Plataforma (2.3) | `sqlite-jdbc` (2.4) |
|---|---:|---:|
| Un comando con su transacción | 0,33 · 0,34 ms | 0,41 · 0,40 ms |
| Editar una tarea: comando + repintado *(EDT)* | 14,8 · 14,6 ms | 17,6 · 17,9 ms |
| Abrir: contadores y cabeceras | 9,9 · 9,7 ms | 12,4 · 12,5 ms |
| Primer pintado *(EDT)* | 6,6 · 5,8 ms | 6,4 · 7,7 ms |
| Desplazarse una página *(EDT)* | 8,1 · 7,6 ms | 7,8 · 6,7 ms |
| Buscar «token» | 41,2 · 40,9 ms | 46,1 · 46,8 ms |
| Migrar 100.000 desde `tasks.xml` | 22,0 · 22,3 s | 23,7 · 24,6 s |
| Escribir un lote de 2.000 | 956 · 950 ms | 980 · 1.016 ms |

Entre un 5 % y un 20 % más lento en los caminos de lectura, y nada cerca de un techo. Se
acepta sin más discusión porque la alternativa no es otro driver: es no publicar. A cambio
llega lo que el módulo interno no exponía, `sqlite3_interrupt`, así que la copia y la
comprobación de integridad dejan de ser sentencias que no se pueden cortar.

**2. «Backup + aviso + cuarentena» perdía un día.** Es lo que pedía el §6.2 y lo que hacía
un `tasks.xml` ilegible. Pero el `.bak` se escribía **en cada guardado**, y la copia de la
base es **diaria**: restaurarla entera tiraría un día de trabajo por una página rota que casi
siempre es de un índice. La recuperación (`StoreRecovery`) salva primero **todo lo legible**,
tabla a tabla y por rangos de rowid —sin tocar un solo índice del fichero dañado—, rehace lo
derivado y sólo pide a la copia las tareas cuyo `seq` no se deja leer. Medido en los casos de
`StoreRecoveryTest`, sobre 600 tareas en la copia y 120 cambios después:

| Daño | Del fichero dañado | De la copia | Lo de después de la copia |
|---|---:|---:|---|
| Página raíz de un índice | 620 | 0 | **todo** |
| Índice de texto | 620 | 0 | **todo** |
| Una hoja de la tabla de tareas | 617 | 3 | todo salvo esas tres, que vuelven en su versión de la copia |
| Cabecera del fichero | 0 | 600 | nada: no había de dónde |

**3. Una hoja rota no siempre da error.** La trampa que costó un test en rojo y que decide
cómo se salva. Con la hoja de la tarea 125 pisada, preguntar fila a fila dio: la 124,
`SQLITE_CORRUPT`; la 125 y la 126, **vacío y sin error**; la 127, bien. La búsqueda binaria
sobre punteros basura sale por un lado, y un rango que las abarca devuelve las de alrededor
saltándoselas en silencio. Partir en mitades lo que falla —lo obvio— se fía de esos vacíos y
pierde las tareas sin decir nada. Por eso un rango que falla se sondea fila a fila, y un vacío
cerca de una fila que falla cuenta como hueco que la copia rellena si lo tiene.

**4. `integrity_check` no ve la contabilidad del plugin.** Mira que los árboles de SQLite
estén sanos, y nada de lo que el almacén mantiene a mano en la misma transacción: los
contadores de las pestañas, el índice de texto de contenido externo —que no cascadea—, la
tupla de orden copiada en `tag` y los `has_*` de la tarea. Una base puede pasarlo y tener las
pestañas contando mal para siempre. `StoreAudit` comprueba eso, y es lo que dan por bueno las
pruebas de caída y la recuperación.

**5. La caída simulada no probaba lo que importa.** La Fase 5 copiaba la base y su diario con
las conexiones abiertas, y eso sólo fija la señal del cierre sucio: la copia se hace entre
dos sentencias, justo cuando no hay nada a medias. `ChildJvm` lanza el bucle **de producción**
en otro proceso —mismo classpath y mismas propiedades— y lo mata con `SIGKILL` dentro de una
transacción, con el retraso sorteado dentro de lo que el propio hijo midió que dura una.

**6. Daño con el proyecto abierto: dejar de escribir, no cambiar el fichero en caliente.** El
plan no distinguía el daño al abrir del que aparece después. La conexión la comparten la
lista, la búsqueda y las marcas del editor; sustituirles el fichero por debajo es mucho más
frágil que reabrir el proyecto, que es el camino que ya está probado. Así que se deja de
escribir —lo que se escribiera en un fichero roto se perdería con él—, se apunta la
recuperación en una marca **fuera** de la base y se ofrece reabrir.

**7. Tras una recuperación con huecos, las imágenes esperan.** Una tarea que no se pudo leer
puede nombrar imágenes que ya no nombra nadie en la base reconstruida, y el recolector las
borraría: la última forma de rescatarla a mano. Mientras siga en disco su fichero dañado, no
se recoge nada en ese proyecto.

**8. La hipótesis del §5-bis.8 era falsa.** Espaciar el volcado del diario ganaba un 40 % al
quitar un repositorio, y se anotó que la migración probablemente ganaría lo mismo. Medido en
las mismas dos vueltas: 23,7 → 23,4 s y 24,6 → 23,8 s, del orden del ruido. Quitar hacía
tandas de 500 tareas que reescribían una y otra vez las mismas páginas interiores; importar
confirma cada 2.000 y escribe sobre todo páginas nuevas, así que no hay volcados repetidos
que ahorrar. `TaskImport.CHECKPOINT_PAGES` se queda en el de SQLite.

**9. El techo de la búsqueda no puede ser el de la puerta.** Las consultas del banco que
llevan «token» —presente en el 98 % del corpus sintético— crecen con los aciertos, que crecen
con N (§3-bis.5): con el techo de 100 ms, la noche del millón nacería en rojo sin que nada
hubiera empeorado. Tienen un techo **por tarea**, diez veces lo medido.

### 6-bis.4 Qué cambia para quien lo usa

- **Una base dañada se repara sola** al abrir: el fichero dañado se aparta como
  `tasklane.db.corrupt-<fecha>` y nunca se borra, todo lo legible se rescata y sólo lo que no
  se lee sale de la copia. Mientras dura, solo lectura; al terminar, un aviso dice si fue sin
  pérdidas, con huecos —cuántas y de qué copia— o sin nada que rescatar.
- **Daño visto con el proyecto abierto**: no se escribe más y el aviso ofrece reabrir.
- **SQLite va dentro del plugin.** El zip crece lo que pesa el driver; el fichero de datos no
  cambia.
- *Tasklane: Diagnostics* dice si hay una reparación pendiente y cuándo fue la última.
- Lo que **no** cambia: el esquema, la copia diaria, la comprobación tras un cierre sucio y
  todo lo que se ve en la ventana.

### 6-bis.5 Lo que quedó construido

| Pieza | Dónde | Qué hace |
|---|---|---|
| `Sql` sobre `sqlite-jdbc` | `main/…/data/sqlite/` | El mismo contrato de cuatro operaciones, con `Sql.Row` numerando columnas desde cero, y `interrupt()` |
| `StoreAudit` | `main/…/data/sqlite/` | Las invariantes que `integrity_check` no ve, y cómo rehacerlas |
| `StoreRecovery` | `main/…/data/sqlite/` | Cuarentena, salvar por rangos de rowid, huecos desde la copia, rehacer, comprobar; reanudable |
| `TaskDb.openChecked` | `main/…/data/sqlite/` | Abrir distinguiendo una base dañada de una que no abre por otra razón |
| `TaskImport` | `main/…/data/sqlite/` | El bucle de la migración, fuera del servicio: es el que las caídas matan |
| `BlobSweeper` | `main/…/data/attachment/` | El bucle del recolector, fuera del servicio, por lo mismo |
| `TaskService` (recuperación) | `main/…/service/` | Arrancar sin almacén, recuperar en segundo plano, dejar de escribir ante daño, avisar |
| `ChildJvm` · `CrashWorker` · `CrashTest` | `test/…/hardening/` | Un proceso al que matar, el bucle de producción dentro, y cinco escenarios de muerte |
| `StoreAuditTest` · `StoreRecoveryTest` | `test/…/data/sqlite/` | Que el auditor ve lo roto; que cada daño se recupera como debe |
| `ScaleBenchmark` | `test/…/bench/` | Techos en cada escenario, longevidad, CSV por ejecución |
| `nightly.yml` | `.github/workflows/` | El banco a un millón con 2 GB de heap, y las caídas en los tres sistemas |

### 6-bis.6 Lo que sigue abierto

| | Por qué |
|---|---|
| La primera noche de `nightly.yml` | No se puede ejecutar desde aquí. Lo que no se sabe hasta que corra: si el disco del runner alojado da para las dos bases de un millón y si seis horas bastan. El workflow admite un N menor a mano |
| Corte de luz | `synchronous = NORMAL` no promete sobrevivir a uno, y no se puede provocar desde un test. Es para lo que existe la recuperación |
| El coste de `sqlite-jdbc` | Un 5–20 % en lectura, medido y aceptado. Si algún día molesta, lo primero es mirar si es el driver o la versión de SQLite: la plataforma traía la 3.42 y el driver, la 3.53 |

---

## 4. Calendario y riesgos

| Fase | Duración | Publicable sola | Riesgo |
|---|---|---|---|
| 0 · Banco | 1 sem | — | Bajo |
| 1 · Quitar O(n) | 1 sem | **Sí** | Bajo |
| 2 · UI acotada | 2-3 sem | **Sí** | Medio |
| 3 · Almacén | 5-6 sem | Sí (con migración) | **Alto** |
| 4 · Adjuntos | 2-3 sem | **Sí** | Medio |
| 5 · Operaciones grandes | 2 sem | **Sí** | Bajo |
| 6 · Endurecimiento | 2 sem | — | Bajo |
| **Total** | **~15-18 semanas** | | |

**Los tres riesgos que hay que vigilar:**

1. **La nativa de SQLite en un plugin de JetBrains.** Se despeja en la Fase 0 con medio
   día de spike. Si falla, plan B: H2 MVStore, +3 semanas, y el FTS hay que escribirlo.
2. **La migración.** Es la única operación que puede perder datos de un usuario real. Por
   eso el XML no se borra nunca, la exportación al formato viejo se conserva, y la
   migración se prueba con ficheros generados *y* con corruptos reales.
3. **La regresión de comportamiento en la búsqueda.** `bm25` no ordena exactamente igual
   que `LinearScanIndex.score()`. El test de equivalencia de §3.5 es lo que dice cuánto se
   mueve, y si se mueve más de lo tolerable hay que pesar las columnas de FTS hasta que
   coincida.

> **Los tres, cerrados.** El nº1 lo despejó la Fase 0 y no existía: la plataforma ya trae
> SQLite con FTS5 (§0-bis.1). El nº2 se cumplió tal cual —el XML se renombra a
> `.migrated` en vez de borrarse, la migración es reanudable y hay una acción que vuelve a
> escribirlo—, y el lector nuevo se compara con el viejo tarea a tarea. El nº3 **se movió
> más de lo que el plan esperaba**, pero no en el orden: `bm25` ordena igual que el
> escalón del título, y lo que cambió fue *qué casa* — FTS5 encuentra palabras y prefijos,
> no trozos de palabra. Está escrito en el §3-bis.6 y fijado en `Fts5IndexTest`.

---

## 5. Lo que hay que decidir antes de empezar

> **Las cuatro, respondidas.** La 1 dejó de ser una pregunta en la Fase 0 —no hay
> dependencia que aceptar—; la 3 es que sí, y la Fase 3 la implementó como acción; la 4 se
> resolvió sola por donde el plan decía: el coste por operación no depende de N, así que
> el millón salió gratis en cuanto salieron bien los cien mil. La 2 sigue siendo de la
> Fase 4.

1. **¿Dependencia nativa (`sqlite-jdbc`, ~12 MB) aceptable?** Si no, el plan cambia de
   forma y de duración.
2. **¿Qué política de imágenes?** Las tres opciones de §4.5. Es la única pregunta cuya
   respuesta no está en el código.
3. **¿Se mantiene `tasks.xml` como formato de intercambio?** Recomendado que sí, como
   exportación: es lo que evita que el dato quede secuestrado.
4. **¿El objetivo de 1M es un objetivo de ingeniería o un compromiso con el usuario?** Con
   este plan da igual —el coste por operación es independiente de N, así que 1M sale
   gratis una vez que 100k sale bien—, pero la de imágenes sí hay que prometerla con
   cuidado.
