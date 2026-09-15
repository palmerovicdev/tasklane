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

---

## 5. Lo que hay que decidir antes de empezar

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
