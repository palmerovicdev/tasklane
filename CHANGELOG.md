# Changelog

Todas las versiones publicables del plugin. El formato sigue
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y el versionado, semver:
hasta la `1.0.0` la versión media era **la fase cerrada** (fase N → `0.N.0`); a partir
de ahí manda semver sobre lo publicado.

> Al subir la versión hay que tocar tres sitios: `pluginVersion` en `gradle.properties`,
> `changeNotes` en `build.gradle.kts` —que es lo que sale en la ficha del Marketplace y
> en el diálogo de actualización del IDE— y este fichero.

## [2.5.0] — IntelliJ IDEA 2026.1.5 en adelante

Menor aunque sube el mínimo de plataforma: quien siga en un IDE anterior no pierde nada, se
queda en la 2.4.0. No hay nada nuevo que usar; lo que cambia es contra qué IDE se promete
funcionar.

### Cambiado
- **El IDE mínimo pasa de 2025.2 a 2026.1.5** (`sinceBuild = 261.27258.48`, antes `252`).
  Quien siga en 2025.2, 2025.3 o 2026.1.0–2026.1.4 se queda en la 2.4.0.
- CI compila, prueba y verifica contra **IntelliJ IDEA 2026.1.5** (`IU`) en vez de la
  Community 2025.2: desde la 2025.3 no hay Community que descargar. El Plugin Verifier fija
  los extremos en 2026.1.5 y 2026.2.

### Corregido
- Los tres avisos del Plugin Verifier del Marketplace sobre la 2.4.0:
  - `ReadAction.compute` (deprecada) → `ReadAction.computeBlocking`, la misma read action
    síncrona, al saltar a un ancla de código.
  - `DynamicBundle(String)` (deprecado) → `TasklaneBundle` **delega** en un
    `DynamicBundle(Class, String)` en vez de heredar.
  - `SimpleListCellRenderer.create` (marcada para eliminarse) → subclase con `customize`,
    como ya hacían los otros tres desplegables, en el de la marca del editor de los ajustes.

### Compatibilidad
- Sin cambio de formato: el esquema sigue en la versión 1, y la 2.4.0 abre un proyecto usado
  por la 2.5.0 y al revés.

## [2.4.0] — Endurecimiento

La **Fase 6** del plan de escala (`docs/plan-escala.md`), la última: comprobar a la fuerza
lo que las cinco anteriores daban por hecho —que una caída no rompe nada, que una base
dañada se recupera y que el rendimiento no se degrada con el uso ni en silencio— y dejarlo
vigilado cada noche.

### Añadido
- **Una base dañada se repara sola.** Hasta la 2.3 se detectaba y se avisaba. Ahora el
  fichero dañado se aparta como `tasklane.db.corrupt-<fecha>` —nunca se borra—, se rescata
  **todo lo que todavía se deja leer**, y sólo lo que no se lee sale de la copia diaria.
  Una página rota de un índice, que es el daño más común, se repara **sin perder nada**; una
  hoja rota de la tabla pierde sólo las tareas de esa hoja, que vuelven en su versión de la
  copia. Mientras dura se ve todo en solo lectura, y al terminar un aviso dice cuál de los
  casos fue. Si la recuperación se interrumpe, la apertura siguiente la termina.
- **Daño visto con el proyecto abierto** —la comprobación tras un cierre sucio, o un error
  de base dañada al guardar—: se deja de escribir, para no perder más dentro de un fichero
  roto, y el aviso ofrece reabrir el proyecto para repararla.
- *Tasklane: Diagnostics* dice si hay una reparación pendiente y cuándo fue la última.

### Cambiado
- **SQLite va empaquetado** (`org.xerial:sqlite-jdbc`) en vez de usar el que trae el IDE: el
  de la plataforma es API interna y el Marketplace rechaza el plugin por usarlo. **Mismo
  fichero y mismo esquema**: nada que migrar.
- Tras una recuperación con huecos, las imágenes que ya no nombra ninguna tarea **no se
  borran** mientras siga en disco el fichero dañado: las tareas que no se pudieron leer
  pueden nombrarlas.

### Pruebas
- **Pruebas de caída de verdad**: un proceso aparte al que se mata a mitad de una
  transacción, de la migración de un `tasks.xml`, del recolector de imágenes, de la copia
  diaria y de la propia recuperación. Se comprueba la base y la contabilidad que el plugin
  lleva a mano —contadores, índice de texto, etiquetas—, no sólo `integrity_check`.
- **Corrupción deliberada**: cabecera, página de índice, hoja de la tabla, índice de texto
  y diario pisados con basura.
- **Techos de rendimiento que rompen el build**: el banco de escala afirma el p99 y el heap
  contra el presupuesto de cada fase, y una prueba de longevidad aplica 100.000 comandos
  seguidos vigilando heap, diario y latencia.
- **CI nocturna** (`nightly.yml`): el banco sobre un millón de tareas con el heap de fábrica
  del IDE, y las pruebas de caída con treinta muertes por escenario en los tres sistemas.

### Compatibilidad
- Sin cambio de formato: el esquema sigue en la versión 1, y la 2.3.0 abre un proyecto usado
  por la 2.4.0 y al revés. Los ficheros nuevos —la cuarentena y la marca de reparación— van
  al lado de la base y una versión anterior los ignora.

## [2.3.0] — Un día por grupo, y un repositorio que se puede vaciar

Una iteración pedida de una vez: siete cosas de uso diario, ninguna del plan de escala.
Todo lo que actúa sobre tareas lo hace **sobre el repositorio activo**, como el resto del
plugin.

### Añadido
- **Guardar un repositorio en un fichero**: *Export ▸ Save Repository As ▸ CSV… /
  Markdown… / Plain Text…*. Todas las tareas del repositorio activo, estado a estado y en
  el orden de la lista. El CSV es una fila por tarea —título, descripción, estado,
  prioridad, etiquetas, vencimiento, marcada, anclas, enlaces, fechas e id—, con BOM para
  que Excel no rompa los acentos. En segundo plano, cancelable y escrito entero o nada.
- **Vaciar un repositorio**: *Export ▸ Delete All Tasks in "…"…*. Separado de guardar y
  sin obligar a exportar antes; la pregunta dice cuántas son y recuerda que se pueden
  guardar. El repositorio se queda en la lista, vacío, y las capturas que ya nadie nombra
  las recoge el mantenimiento de siempre.
- **Arrastrar filas en los ajustes**, por el asa de su izquierda, en las tablas de
  prioridades y de estados. Las flechas siguen, con sus atajos.

### Cambiado
- **Agrupar por fecha es un grupo por día.** Sólo «Today» se queda sin fecha; debajo, una
  cabecera por cada día que tenga alguna tarea —«Sep 14», «Sep 10, 2025»— en vez de
  «Yesterday», «This week» y meses enteros para los años anteriores. Las cabeceras siguen
  saliendo de saltos de índice: cuestan lo que los días distintos, no lo que las tareas.
- **Las prioridades, de la más alta a la más baja en los ajustes**, que es como salen en
  la ventana. La configuración guardada no cambia.
- **Los enlaces del cuerpo de una tarjeta se pulsan** donde están, con el color de enlace
  sobre el gris. El contador de enlaces sigue abriendo la lista de todos.
- **El ancla de código y la prioridad no se caen de la tarjeta.** Si `Auth.kt:42` no cabe
  entero se queda su icono, que sigue llevando al código; la prioridad se queda en su punto
  de color, que sigue abriendo la lista. Lo garantiza el reparto de la línea hasta el
  ancho mínimo de la ventana.
- **Los iconos de desplegar, marcar y menú de la tarjeta, más juntos.**
- **El resalte del ratón sobre una tarjeta lo pinta el árbol**, como el de la selección:
  la tarjeta bajo el ratón deja de pintar su fondo encima. Antes se apagaba el del árbol, y
  eso sólo se podía hacer con API interna.

### Corregido
- **Pulsar un ancla de código no llevaba al código** en los IDE recientes: lanzaba *Read
  access is allowed from inside read-action only*.
- **Cambiar el color de una prioridad en los ajustes no hacía nada.** El selector se abría
  como editor de celda y la tabla daba la edición por terminada al perder el foco, antes
  de que se eligiera ningún color.
- **Lo que el verificador del Marketplace marcaba.** Se usaba API interna
  (`RenderingUtil.setHoverPaintingDisabled`); un `KeymapUtil::getShortcutText` se compilaba
  leyendo `KeymapUtil.INSTANCE`, que no existe en la 2026.1 y habría sido un
  `NoSuchFieldError` al abrir la ventana; y constaban usos de API deprecada
  —`ToolWindowFactory.isApplicable`, `isDoNotActivateOnStart` y `getCheckbox()`— que no
  estaban en el código: los dos primeros los escribía Kotlin como puentes hacia los métodos
  por defecto de la interfaz, y se compila ya sin ellos (`jvmDefault = NO_COMPATIBILITY`).

### Imágenes
- **Las imágenes se guardan a su tamaño.** Se retira el tope de 400 px de la 2.1: una
  captura de código a 400 px no se lee. Una captura pegada se guarda como PNG sin pérdida y
  un fichero soltado, elegido o copiado, **con sus bytes tal cual**. Medido sobre una
  captura de 2880 px: pegar pasa de 8 ms a 129 ms de CPU —fuera del hilo de interfaz— y de
  46 KB a 1,25 MB; soltar un fichero pasa de 47 ms a **1 ms**, porque ya no se descodifica.
  Desaparece el ajuste *Scale pasted images down to*.
- **Los ajustes dicen siempre cuánto pesan las imágenes del repositorio activo**, y traen
  dos botones, también del repositorio activo: *Delete Unused Images* borra ya, sin las 24
  horas de gracia, las que no nombra ninguna tarea —antes repasa el directorio, así que
  también se lleva lo que hubiera en disco sin apuntar—; *Delete All Images…*, tras
  confirmarlo, vacía el directorio y sus filas en la base sin tocar las tareas, que pintan
  «Image not found».
- **El aviso de cuota es por repositorio**: dice cuál pasa del umbral, y lleva a los
  ajustes donde se ve y se limpia.
- **El diálogo de una tarea no descodifica originales en el hilo de interfaz**: pinta sus
  vistas previas desde una copia de 1280 px, y ampliar una imagen la carga en segundo
  plano. Con capturas a su tamaño, un original son 33 MB descodificado.
- *Tasklane: Diagnostics* deja de contar imágenes «por encima del tope», y el peso de
  imágenes cuenta sólo lo que está en disco.

### Compatibilidad
- Sin cambio de formato: el esquema de la base sigue en la versión 1 y la 2.2.0 abre un
  proyecto usado por la 2.3.0 como si nada. Las imágenes siguen en
  `attachments/ab/cd/<sha>.png` —un fichero soltado conserva su formato dentro de ese
  nombre, y todo lo que las lee lo reconoce por el contenido—, y el atributo
  `imageMaxSize` de un `tasklane.xml` anterior se ignora al leerlo.

## [2.2.0] — Las operaciones grandes

La **Fase 5** del plan de escala (`docs/plan-escala.md`): lo que es O(n) por definición.
Exportar un millón de tareas sigue siendo leer un millón de tareas, pero deja de retener
lo que lee, deja de ocurrir en el hilo de interfaz, deja de bloquear a los demás y se
puede cancelar. Y el `.bak` que se copiaba en cada guardado tiene por fin su sustituto.

### Añadido
- **Exportar a un fichero.** Copiar una pestaña de más de 10.000 tareas ofrece guardarlas
  en un `.md` o `.txt` en vez de llenar el portapapeles. Se escribe a un temporal que sólo
  sustituye al destino cuando está entero.
- **Una copia de seguridad de la base al día**, como mucho: `tasklane.db.backup`, en
  segundo plano, sólo si hubo cambios y si cabe. Sustituye al `.bak` de cada guardado.
- **Comprobación de la base tras un cierre inesperado del IDE**, en segundo plano. Si
  encuentra daños lo dice, con la fecha de la última copia, y deja de copiar encima de
  ella. *Tasklane: Diagnostics* enseña la copia y la última comprobación.

### Cambiado
- **Exportar va en *streaming*.** Con 100.000 tareas, copiar un estado de 33.000 filas
  retenía **280 MB**; ahora **2,2 MB**. Exportar el repositorio a `tasks.xml` retenía
  **1,5 GB**; ahora **15 MB**. Y lo que sale es la pestaña **en el momento de pulsar**,
  aunque se siga editando mientras se escribe.
- **Las operaciones sobre muchas tareas a la vez** —mover, completar, marcar, cambiar la
  prioridad, borrar— son **una** operación y **un** repintado, no uno por fila. Por encima
  de diez filas van en segundo plano con barra, y **cancelar deshace**. Marcar 2.000 tareas
  pasa de 1,9 s a 0,74 s.
- **Escribir una tarea es más barato**: una sentencia por tabla en vez de treinta por
  tarea, y el índice de búsqueda sólo se toca si cambió el texto.
- **«Exportar y quitar» funciona a cualquier tamaño.** Ya no lee en el hilo de interfaz:
  pone el repositorio en solo lectura, exporta —al portapapeles, o a un fichero por encima
  de 10.000—, **comprueba que salieron todas** y sólo entonces borra, por tandas. Con
  100.000 tareas, lo más que espera cualquier otro comando mientras tanto pasa de
  **12,3 s a 263 ms**.

### Corregido
- **Un repositorio renombrado o borrado del disco desaparecía del selector con sus
  tareas dentro** desde la 2.0.0: el catálogo miraba el `tasks.xml`, que la migración
  renombra. Vuelve a quedarse, marcado como ausente, mientras tenga tareas.
- **Exportar a XML un repositorio creado después de la migración** dejaba un fichero que
  se intentaba importar encima de lo que ya había y acababa en cuarentena con un aviso de
  fichero corrupto.
- **Exportar a XML con una migración a medias** escribía encima del `tasks.xml` original.
  Ahora espera a que termine y lo dice.
- **Borrar una selección grande** era cuadrático en el número de filas.
- **El aviso de la migración** enseñaba `{1}` en lugar de la ruta del fichero archivado.
- **Exportar a XML** ya no falla entero por un carácter que XML no admite —la salida de
  una terminal pegada—: se sustituye por `U+FFFD` y se dice cuántos.

### Compatibilidad
- **Nada cambia de formato.** El esquema de la base sigue en la versión 1 y no gana tablas;
  lo nuevo es un fichero al lado —la copia— y dos marcas en la tabla de mantenimiento que
  ya existía. Una 2.1.0 abre un proyecto usado por la 2.2.0 sin notar nada.

## [2.1.0] — Las imágenes dejan de pesar

La **Fase 4** del plan de escala (`docs/plan-escala.md`): los adjuntos. Hasta ahora todas
las capturas de un repositorio vivían en un único directorio que había que **listar
entero** al abrir el proyecto para saber cuáles sobraban, y la lista descodificaba la
imagen original para pintar una vista previa del tamaño de un sello. Las dos cosas se
acabaron.

**Las capturas nuevas se guardan a 400 px** en vez de 1600 —doce veces y media menos
disco por captura—, **las que ya estaban no se tocan** y se siguen viendo igual, y ahora
*Tasklane: Diagnostics* dice cuántas son y cuánto ocupan por si se quieren revisar.

### Añadido
- **Miniaturas.** La lista **nunca** descodifica una captura grande: de las de 1600 px
  pinta una miniatura de 256 px, que se crea sola la primera vez y en segundo plano. Una
  tarjeta con diez de ellas ocupaba **134 MB** de memoria y tardaba **176 ms** en abrirse;
  ahora son **36 MB** y **8,7 ms**. Para mirar de cerca, el clic sigue abriendo el
  original a tamaño de pantalla.
- **Aviso de cuota.** Cuando las imágenes del proyecto pasan de 5 GB se avisa una vez, con
  el peso y con qué hacer al respecto. **No se borra nada**: el umbral se sube, se baja o
  se apaga en *Settings → Tools → Tasklane → Images*.
- **Reconciliación semanal**, en segundo plano: adopta las imágenes que aparezcan en el
  directorio sin que el plugin lo sepa, y marca como ausentes las filas cuyo fichero se
  borró por fuera. Detecta la deriva en los dos sentidos sin tener que recorrer nada en
  cada apertura.
- **El informe de diagnóstico cuenta las imágenes desde la base**: la cifra es exacta y ya
  no viene con un «+» de «he dejado de contar a las 200.000». Dice además cuántas están
  guardadas por encima del tope de escalado de hoy, cuántas faltan del disco y cuánto se
  lleva la cuota.

### Cambiado
- **El tope de escalado por defecto baja de 1600 px a 400 px.** Medido sobre PNG
  calibrados contra una captura real: 407 KB por captura a 1600, 33 KB a 400. Afecta sólo
  a lo que se pegue a partir de ahora, y el ajuste llega hasta 4000 px para quien lo
  quiera.
- **Las imágenes se guardan en subdirectorios** —`attachments/ab/cd/<sha>.png`— en vez de
  todas en el mismo. Con diez millones de ficheros, un único directorio no es lento: es
  intratable. El traslado de lo que hubiera es automático, por tandas, en segundo plano, y
  nada deja de verse mientras ocurre.
- **Saber qué imágenes sobran deja de mirar el disco.** Las lleva la base: con 100.000
  capturas guardadas, eso pasa de un recorrido del directorio de **329 ms en cada
  apertura** a una consulta de **0,49 ms** — y, al revés que el recorrido, no crece con lo
  que haya guardado.
- **Recolectar va por tandas y se puede cancelar**, con el mismo periodo de gracia de 24
  horas de siempre y con una salvaguarda nueva: no se recolecta un repositorio mientras su
  `tasks.xml` se está importando.

### Compatibilidad
- La base gana dos tablas y **no sube de versión de esquema**, a propósito: subirla
  dejaría en solo lectura cualquier proyecto que luego se abriera con la 2.0.0 —o sea,
  bloquearía editar tareas para proteger dos tablas de contabilidad que se reconstruyen
  solas—. Las tablas se crean al abrir; no hay migración que esperar.
- **Una versión anterior del plugin no encontrará las imágenes ya trasladadas** y las
  pintará como ausentes. No se pierde nada —los ficheros están en sus subdirectorios—, las
  tareas se siguen leyendo y editando con normalidad, y al volver a la 2.1 todo aparece.

## [2.0.0] — Las tareas se mudan a una base de datos

La **Fase 3** del plan de escala (`docs/plan-escala.md`): las tareas dejan de vivir en
un `tasks.xml` que se leía entero al abrir y se reescribía entero al guardar, y pasan a
una base SQLite local. El cambio no se ve —la ventana, el orden, los grupos y los
contadores son los mismos— y se nota en todo: un proyecto con cien mil tareas abre en
**9 ms** en vez de tres segundos, y el plugin ocupa **2,5 MB de memoria** en vez de
1,3 GB.

**Es una versión mayor porque cambia el formato de los datos.** La migración es
automática, ocurre una vez y en segundo plano, y **el `tasks.xml` no se borra**: se deja
al lado como `tasks.xml.migrated`. Volver a la 1.6.0 es quitarle ese sufijo. Y hay una
puerta de salida permanente en *Copiar → Export Repository to XML*, que devuelve las
tareas al formato de intercambio cuando se quiera.

### Añadido
- **Las tareas viven en `.idea/tasklane/tasklane.db`**, una base SQLite —la que ya trae
  el propio IDE— con índices, paginación y búsqueda de texto completo. Un fichero por
  proyecto, con una columna que dice de qué repositorio es cada tarea.
- **La migración desde `tasks.xml`** va en segundo plano, con barra de progreso, se
  puede cancelar y **se reanuda** si se cierra el IDE a medias. La lista se va llenando
  mientras ocurre en vez de esperar a que termine.
- **Nueva acción *Export Repository to XML***, en el menú de copiar: escribe las tareas
  del repositorio activo de vuelta a `tasks.xml`, en el formato de siempre. El dato no
  queda secuestrado dentro de la base.

### Cambiado
- **Abrir un proyecto ya no lee las tareas.** Antes se cargaba el fichero entero antes
  de pintar nada; ahora la ventana pide **la página que se ve**. Con 100.000 tareas,
  abrir y pintar pasa de **3.027 ms a 9,2 ms**.
- **La memoria del plugin deja de crecer con el proyecto.** Medido: **2,5 MB** con
  100.000 tareas, contra los 1,3 GB que costaba tener el modelo vivo en memoria. Y es la
  misma cifra con diez mil que con un millón.
- **Guardar es una transacción, no un volcado.** Editar una tarea escribía el fichero
  completo —1.168 ms con 100.000 tareas— detrás de un retardo de medio segundo. Ahora
  son **0,44 ms** y sin retardo: lo que se acaba de escribir ya está en disco, así que
  un cierre inesperado del IDE no se lleva por delante lo último que se tecleó.
- **Buscar usa un índice de texto en vez de recorrer las tareas.** Con 100.000, la peor
  consulta posible —una palabra que está en casi todas— pasa de **115 ms a 40 ms**, y
  las normales son instantáneas.
- **Borrar un estado con tareas dentro es inmediato**, por muchas que tenga: deja de ser
  una pasada por todas para pasar a ser una sola instrucción a la base.
- **Marcar las líneas del editor** ya no recorre el proyecto entero cada vez que cambia
  algo: pregunta por el fichero abierto.

### Corregido
- **Un `tasks.xml` ilegible ya no dejaba el repositorio en blanco hasta la copia de
  seguridad.** Ahora lo que se pudo leer del fichero entra igualmente, y sólo lo que
  falta se busca en el `.bak`.
- Un repositorio muy grande podía abrir bien y **morirse al mirarlo**, porque pintar la
  lista por primera vez materializaba el texto de todas las tareas. Ya no hay nada que
  materializar.

## [1.6.0] — La lista deja de pesar lo que pesa el proyecto

La **Fase 2** del plan de escala (`docs/plan-escala.md`): la ventana carga la lista a
páginas y repinta sólo lo que cambió. Con un millón de tareas ninguna operación de la
interfaz pasa de 3 ms; antes abrir la ventana costaba diez segundos con el IDE
congelado. Nada de esto cambia el orden, los grupos ni los contadores: cambia cuánto
cuesta enseñarlos.

### Añadido
- **La lista carga a páginas.** Al final de un grupo hay una fila que dice cuántas
  tareas quedan; llegar a ella desplazándose, pulsarla o darle a `Enter` trae las
  siguientes. Es el mismo gesto que *Find in Files*.
- **Los grupos grandes empiezan plegados**, con su número en la cabecera —y los de
  abajo también, cuando los de arriba ya llenan la pantalla—. Abrir uno carga su primera
  página en ese momento. Lo que el usuario abre o cierra manda siempre.

### Cambiado
- **Repintar la lista deja de depender del tamaño del proyecto.** Con 100.000 tareas,
  abrir la ventana pasa de **1.179 ms a 4,1 ms** y repintarla tras completar una tarea
  de **1.194 ms a 0,31 ms**. Con un millón, de **10,2 s a 0,61 ms**. El árbol ya no se
  reconstruye en cada cambio: se pone al día fila a fila, y las que siguen igual
  conservan su altura ya medida.
- **Enseñar una tarea desde una marca del editor** ya no tiene que cargar todo lo que
  tiene delante: la lista se abre a su altura y anuncia lo que queda por encima.
- **Contar las tareas de las pestañas y repartirlas por estado es una sola pasada**, no
  dos.

### Corregido
- **Cada repintado recorría el proyecto entero para olvidar las tarjetas desplegadas que
  ya no existían**, en el hilo de la interfaz. Con listas grandes esa limpieza costaba
  más que el propio repintado, y no hacía falta: los identificadores no se reciclan.
- **Copiar un estado o un grupo al portapapeles copiaba lo que estuviera pintado.** Ahora
  vuelve a pedir la lista completa, que es lo que el usuario espera de «copiar este
  estado» aunque la lista se cargue a trozos.

### Interno
- Nueva costura `TaskPager` entre la ventana y quien tenga los datos: la UI pide páginas
  y agregados, nunca listas. En la Fase 3 se cambia la implementación —SQLite con
  índices, `LIMIT` y FTS5— y la ventana no se entera. Es la misma jugada que ya funcionó
  con `TaskSearchIndex`.
- El trabajo de la lista (`ListSync`, `TreeSync`, `GroupBudget`, `InMemoryPager`) vive
  fuera del panel y no necesita arrancar un IDE: 58 casos nuevos lo cubren, incluida la
  lista de punta a punta.
- El banco de escala mide ahora código de producción en vez de una copia suya, y trae
  tres escenarios nuevos —primer pintado, repintado y desplazamiento— cada uno con el
  camino viejo medido al lado.

## [1.5.0] — Las listas grandes dejan de pesar

Dos fases del plan de escala (`docs/plan-escala.md`): el banco de pruebas que convierte
«va lento» en una cifra, y el primer recorte de trabajo por comando. Nada de esto cambia
lo que la ventana enseña; cambia lo que cuesta enseñarlo.

### Añadido
- **Acción *Tasklane: Diagnostics*.** Cuántas tareas hay, cuánto ocupan en disco, cuántas
  imágenes y de qué peso, cuánto ahorra la deduplicación, y las latencias de la última
  hora por operación (cargar, comando, guardar, buscar, agrupar, repintar). Se copia con
  un botón, porque su destino natural es un issue.

  Avisa explícitamente cuando el p99 del repintado se pasa de 16 ms: es el único número
  del informe con un techo duro, porque ocurre en el hilo de la interfaz.
- **Banco de escala** (`ScaleBenchmark`, con corpus sintético reproducible). No se
  ejecuta en el build normal; se lanza con `-PbenchN=<tareas>`. Es lo que cierra las
  puertas de las fases del plan con cifras medidas en vez de con estimaciones.

### Cambiado
- **Un comando cuesta ocho veces menos.** Con 100.000 tareas, de 4,14 ms a 0,51 ms. El
  reducer devuelve ahora qué repositorios tocó y qué tareas aparcó, en vez de que el
  servicio lo deduzca recorriendo el modelo entero detrás de cada pulsación.
- **Guardar el diálogo de edición manda un comando, no seis.** Cambiar cuerpo, estado,
  prioridad, etiquetas, vencimiento y anclas enviaba seis comandos seguidos, y cada uno
  producía un estado nuevo: seis copias de la lista y hasta seis repintados del árbol por
  un solo clic en *Guardar*. Editar una tarea sobre 100.000 pasa de 26 ms a 6 ms.
- **Buscar el siguiente hueco de orden y buscar una tarea por su id pasan a ser
  instantáneos**, en vez de recorrer la lista entera cada vez.
- **Escribir en el buscador deja de reconstruir el índice de tareas vivas en cada
  pulsación.** Sólo se reconstruye cuando hay algo que podar.

### Corregido
- **Las cachés de imágenes se acotan por memoria, no por número de entradas.** Una
  captura de 1600 px descodificada ocupa 10,2 MB, y se guardaban dieciséis: hasta 164 MB
  de memoria con veinte tareas en la lista. Ahora el tope son 64 MB de imágenes
  descodificadas y 16 MB de escaladas, contados de verdad.
- Borrar una tarea que ya no existía reconstruía la lista igualmente, con su repintado y
  su escritura a disco detrás.
- Aceptar el diálogo de edición sin cambiar nada movía la fecha de modificación de la
  tarea.

### Interno
- Los tests se ejecutan en CI —en Linux, Windows y macOS, y contra la versión mínima de
  plataforma soportada—, cosa que hasta ahora no ocurría en ningún sitio.
- El almacén de la Fase 3 usará el SQLite que **ya trae la plataforma**
  (`org.jetbrains.sqlite`, con FTS5 compilado), así que no habrá que empaquetar ninguna
  dependencia nativa. Ver `docs/plan-escala.md` §0-bis.1.

## [1.4.1] — La marca se lee, y lo copiado lleva fecha

Tres cosas de las que se usan a diario y estorbaban en silencio: una marca inline pegada
al código, un tooltip que contaba la tarea menos la mitad que importa, y una exportación
por fechas con la fecha puesta en un texto que caduca.

### Añadido
- **El tooltip de la pastilla enseña las capturas de la tarea.** Media tarea es una
  imagen pegada —el error que se vio, el diseño que hay que copiar—, y hasta ahora el
  tooltip decía el título, el estado y la prioridad justo en el caso en el que mirar la
  captura **ya era la respuesta**: había que abrir la ventana para ver lo único que hacía
  falta. Salen escaladas, hasta dos por tooltip, gastadas por orden de prioridad para que
  las de la tarea que manda se vean seguro.

  Sólo en la pastilla inline, y no es un olvido: su tooltip se construye al pasar el
  ratón y en un hilo de fondo, así que puede ir al disco. El del margen lo calcula la
  plataforma para **todas** las anclas del fichero de una vez y en el EDT, donde leer
  imágenes congelaría el editor.
- **Un grupo de fecha se copia como el parte de su día.** En Markdown, la cabecera es la
  fecha en ISO y las tareas van numeradas:

  ```markdown
  ## 2026-09-11

  1. Arreglar el login
  2. Revisar el PR de facturación
     hay que avisar a soporte
  ```

  Es lo que se pega en un diario de trabajo o en un informe semanal. «Done · Hoy» dejaba
  de ser verdad al día siguiente —y lo exportado se guarda—, y la casilla `- [x]` sobra
  cuando el grupo entero ya significa «esto se hizo ese día». Sólo con los grupos que son
  **un día concreto**: «esta semana», los meses y «sin fecha» no tienen fecha que poner,
  así que conservan la cabecera de la pestaña y su casilla en vez de inventarse una. En
  texto plano no cambia nada: ahí el destino es un correo, no un documento.

### Corregido
- **La pastilla inline deja aire a los dos lados.** Iba pegada al carácter de antes y al
  de después, así que `websi`·`TODO`·`te` se leía como una sola palabra y el código
  parecía entrar y salir de la marca. El hueco es **del inlay**, no del fichero: se
  reserva al medir y se salta al pintar, que es justo lo que un inlay existe para poder
  hacer sin tocar el documento.

### Interno
- El HTML del tooltip de la pastilla se compone **fuera del EDT** y se vuelve a él sólo a
  enseñar el globo, repitiendo las comprobaciones de que el ratón sigue encima y el
  editor sigue abierto. Sin eso, leer y medir los blobs congelaría el editor mientras se
  mueve el ratón por él.

## [1.4.0] — La tarjeta, entera y en su sitio

Lo que salió de usar la `1.3.0` con la tool window estrecha: distintivos que no se veían,
tarjetas asomando por fuera del panel y botones que no hacían lo que se ve debajo del
ratón. Todo lo de esta versión es la misma historia contada por sitios distintos —la fila
se medía de una forma y se pintaba de otra— más el remate de la prioridad.

### Añadido
- **El distintivo de prioridad va en todas las tarjetas**, también en las de la
  prioridad de fábrica. Mientras sólo se leía, ésa se callaba —sería la misma palabra
  repetida en toda la lista, y el color ya lo lleva la franja—; desde la `1.3.0` el
  distintivo **es el botón** que cambia la prioridad, así que callarlo escondía el
  control justo en las tarjetas que nadie ha tocado, que son las que más se cambian.
- **El punto de color también se pulsa.** Es más pequeño que la palabra y es a donde se
  apunta —el color es lo que identifica una prioridad—, y era la mitad del distintivo
  que no respondía.
- **El submenú *Priority* lleva icono**: dos barras de color apiladas, el de «ordenar
  por severidad» de la plataforma, que es lo más parecido a «niveles» que hay en
  `AllIcons`. Era la única entrada del menú sin icono y su fila se leía hundida.
- **La ventana no baja de 300 px de ancho.** Por debajo, el título se parte en líneas de
  dos palabras y los distintivos empiezan a caerse por la derecha; de ahí para arriba la
  tarjeta se defiende sola.

### Corregido
- **La tarjeta ya no se sale de la tool window.** Con la ventana estrecha y unas cuantas
  etiquetas, la fila pedía más ancho del que se veía —la línea de distintivos pide el de
  todos los suyos aunque luego deje fuera los que no caben—, y el árbol crece hasta la
  fila más ancha: la plataforma daba por recortadas todas las tarjetas y al pasar el
  ratón sacaba media tarjeta flotando por fuera del panel, con los botones de la derecha
  dentro. Acercarse a pulsarlos la cerraba antes de llegar. Ahora la fila nunca pide más
  de lo que se ve y ese trozo flotante está apagado.
- **La línea de distintivos ya no se cae de la tarjeta.** Era el otro lado de lo mismo:
  el árbol se quedaba más ancho que el hueco y dejaba de seguirlo, así que la tarjeta se
  medía a un ancho y se pintaba a otro. El título se partía en una línea más de las que
  se habían medido y lo que sobraba por abajo —prioridad, fecha, etiquetas— se quedaba
  fuera del alto de la fila: había tarjetas con distintivos y tarjetas sin ellos, y nada
  explicaba la diferencia. Ahora la ventana no deja que las dos medidas se separen, y si
  aun así una fila viniera corta, la línea de abajo se queda con su sitio y lo que se va
  es el renglón de texto que sobra —que ya venía recortado, y que se lee entero
  desplegando la tarjeta—.
- **Una palabra sin espacios ya no descoloca los botones de la fila.** Un identificador
  largo o una URL sin acortar no tiene por dónde partirse, así que se quedaba entera y la
  fila pasaba a medir lo que ella. Los botones de la derecha se colocan contra esa
  medida, y dejaban de caer donde se ven: pulsar el marcador plegaba la tarjeta. Ahora
  esas líneas se cortan con puntos suspensivos, como ya hacía la descripción.

## [1.3.0] — Tarjetas que se leen y se tocan

Tres cosas que la tarjeta pedía desde el rediseño: que su texto se pueda coger, que se
pueda leer entera sin abrir la tarea, y que la prioridad se cambie desde donde se lee.

### Añadido
- **El texto de la tarjeta se selecciona y se copia.** Se arrastra con el ratón por
  encima, `⌘C` lo copia y `Escape` lo suelta. La selección se queda dentro de **una**
  tarjeta: arrastrar fuera se pega a su principio o a su final, en vez de llevarse media
  lista por delante. El cursor pasa a ser una I sobre el texto, que es la única pista de
  que el gesto existe.
- **Un botón despliega la tarjeta** y enseña todo lo que escondía: el título sin el tope
  de tres líneas y **todas** las líneas del cuerpo, no sólo la primera recortada. Volver
  a pulsarlo la deja como estaba. Aparece sólo cuando hay algo escondido de verdad —una
  tarjeta que ya se ve entera no lo enseña— y, una vez desplegada, se queda a la vista
  aunque el ratón se vaya, porque si no no habría forma evidente de volver a plegarla.
- **Las capturas se ven en la tarjeta desplegada**, escaladas y con el mismo borde y el
  mismo marcador de carga que en el diálogo: son la misma imagen vista en dos sitios, y
  si la lista la enseñara de otra forma abrir la tarea parecería enseñar otra. Cada una
  va detrás de la línea que la nombra, igual que el inlay del editor, y un clic la
  amplía. Plegada la tarjeta siguen sin verse —una captura dentro de una fila de tres
  líneas dejaría dos tareas por pantalla—: ahí está el contador «1 img», que desaparece
  cuando las imágenes ya están a la vista.
- **La prioridad se cambia desde la propia tarjeta**: un clic en su distintivo abre la
  lista de prioridades con sus colores. Actúa sobre la tarjeta pulsada, como el
  marcador, sin tener que seleccionarla antes.
- **Submenú *Priority*** en el menú contextual, al lado de *Move To*, para cambiar la de
  toda la selección de una vez.

### Cambiado
- El cuerpo de una tarea se deriva ahora como **bloques en orden** (`Task.detailBlocks`),
  texto e imágenes mezclados, y no como dos listas separadas. Es lo que permite pintar
  cada captura donde se escribió; `detailLines` y `description` salen de ahí.
- Lo pulsable de una fila —enlace, ancla y ahora prioridad— se resuelve en **una sola
  consulta** al renderer y no en una por tipo. Resolverlo obliga a montar y medir la
  fila, y eso corre en cada píxel que recorre el ratón.
- El despliegue de una tarjeta se recuerda por tarea mientras dure la sesión, así que
  una búsqueda que la esconda un rato no lo deshace. No se persiste: desplegar es mirar
  algo un momento, no configurar la lista.

## [1.2.1]

### Corregido
- **El hint de la pastilla del editor desaparecía en menos de un segundo**, con el ratón
  todavía encima. Lo pintaba `IdeTooltipManager`, que vigila el ratón de toda la interfaz
  y esconde el tooltip en cuanto llega un movimiento que no reconoce como «dentro» — y un
  inlay no es un componente Swing, así que no lo reconoce nunca: se iba al primer píxel de
  movimiento. Ahora es un `Balloon` sin caducidad que se esconde sólo cuando lo decimos
  nosotros: al salir de la pastilla, al salir del editor, al pulsar, al desplazar el editor
  o al recolocarse las marcas.
- El hint aparece con el retardo de tooltip del IDE, pero con tope de medio segundo: el de
  fábrica pasa del segundo, y sobre una marca que se ha ido a buscar a propósito eso se lee
  como que no hay nada que enseñar.

## [1.2.0] — Y el código apunta a las tareas

La otra mitad de las anclas. Desde la `1.1.0` la tarea sabía ir al código; el código no
sabía nada de la tarea, así que una nota sólo aparecía si uno se acordaba de ir a
buscarla.

### Añadido
- **Las líneas ancladas se marcan en el editor**, con el logo de Tasklane en el color de
  su prioridad. El ratón encima abre un tooltip con el título, el estado, la prioridad, el
  vencimiento y las etiquetas; un clic abre la tool window con esa tarea seleccionada —y
  cambia de repositorio si la tarea es de otro—.
- **Sólo se marca lo que sigue abierto.** Una tarea en un estado terminal es historia, no
  una nota sobre el código; marcarla convertiría el margen en un cementerio y a la semana
  nadie miraría ninguna.
- **Dos formas, y se elige** en *Settings → Tools → Tasklane → Code anchors*: un icono en
  el margen —lo de fábrica, no toca ni un píxel del código— o una pastilla dentro del
  texto, en el carácter exacto, que es la única que enseña de qué parte de la línea
  hablaba la nota. Se puede apagar del todo. Es un ajuste de la persona: va a
  `workspace.xml` y no se comparte con el equipo.
- La pastilla lleva **la palabra**, no sólo el icono: `✓ TODO`. Un icono suelto entre
  código se lee como un carácter raro y hay que pasar el ratón para saber qué es. Es el
  nombre del estado en mayúsculas —la convención de los marcadores de código—, así que
  con la configuración de fábrica sale `TODO` y una tarea en *Doing* dice `DOING`.
- **El ancla guarda también la columna.** El cursor vuelve al carácter exacto, no al
  principio de la línea. Sólo se escribe cuando no es cero, así que un fichero de tareas
  de la `1.1.0` se lee igual y no engorda.
- Varias tareas en la misma línea comparten una marca: el logo entero —dos renglones— con
  el color de la de más prioridad, y el tooltip las lista.

### Cambiado
- Enseñar una tarea en la ventana (`TaskService.revealTasks`) ya no depende de que esté a
  la vista: si es de esta pestaña pero la esconden la búsqueda o el filtro, se quitan; y
  si aún no ha llegado al árbol —porque acaba de cambiar el repositorio activo— se
  reintenta tras el siguiente repintado. La acción de la notificación de remapeo se
  beneficia igual.

## [1.1.0] — Las tareas apuntan al código

Primera versión posterior al plan de fases. Manda semver.

### Añadido
- **Anclas de código.** Una tarea puede apuntar a un `fichero:línea`. *New Tasklane Task
  from Here*, en el menú contextual del editor, crea la tarea con el sitio ya puesto y,
  si hay algo seleccionado, con ese texto como cuerpo. La tarjeta enseña un distintivo
  `Auth.kt:42` que vuelve ahí con un clic.
- El ancla **sobrevive a que le editen el fichero por encima**: junto al número de línea
  se guarda el texto de esa línea, y al abrirla se busca hacia fuera desde donde estaba.
  Un import de más no la rompe.
- Dos operadores de búsqueda: `file:` —por un trozo de la ruta anclada— y `has:code`. La
  ruta entra además en el texto libre, así que escribir `AuthService` encuentra lo que
  apunta a ese fichero aunque la nota lo llame de otra manera.
- **Mover de estado sin el diálogo**: submenú *Move To ▸* en el menú contextual y en el
  `⋮` de la fila, y `⇧⌥←/→` para mover la selección una pestaña. Era lo más repetido de
  una lista con estados y costaba abrir un modal.
- `⌥←/→` vuelve a cambiar de pestaña de estado. Es la deuda que quedó anotada en
  `docs/plan-rediseno.md` (R3) al bajar los estados del `ContentManager` al panel, pagada
  como decía: con un atajo local en la lista.

### Corregido
- **Las cabeceras de grupo no se plegaban.** Ni con el clic ni con `←`. El
  `collapsePath` de `com.intellij.ui.treeStructure.Tree` pliega recursivamente cuando el
  ajuste avanzado `ide.tree.collapse.recursively` está puesto —viene puesto— y ese camino
  descarta en silencio los nodos de profundidad cero, que es lo que son las cabeceras en
  un árbol sin manecillas. El panel pliega ahora con `setExpandedState`, que es lo que
  llama el `collapsePath` de `JTree` sin ese recorte.
- Plegar un grupo **se recuerda de verdad**. Restaurar la selección después de repintar
  volvía a abrir el grupo recién plegado —`JTree` despliega los ancestros de lo que se
  selecciona— y encima borraba el recuerdo, porque el evento llegaba como si fuera un
  gesto del usuario.
- El doble clic sobre una cabecera ya no dispara el gesto dos veces ni compite con el
  plegado por clic de la plataforma.

### Cambiado
- La ficha y el layout de las etiquetas se comparten con el campo de anclas
  (`Chip`, `ChipsLayout`), y el clic de la fila —enlaces y anclas— vive en un solo
  oyente (`RowClicks`) en vez de en uno por cosa.

## [1.0.0] — Las ocho fases

Cierra la **Fase 7** (robustez y pulido) y con ella el plan de ocho fases. Primera
versión pensada para publicarse.

### Añadido
- Las tarjetas pintan el **Markdown** del cuerpo en vez de enseñar las marcas: negrita,
  cursiva, `código` y tachado, en el título y en la línea de descripción.
- Las **cabeceras de grupo** llevan chevrón y se pliegan con un clic. Antes se podían
  plegar sólo con `←`/`→` y nada lo anunciaba.
- **Navegación por teclado** por la fila de estados: entra en el recorrido del tabulador
  y se activa con Espacio o Intro.
- **Nombres accesibles** para el árbol, el buscador y los campos del diálogo, que ahora
  van asociados a su etiqueta.

### Cambiado
- Los avisos de lectura de ficheros salen del `TasklaneBundle` como el resto de la
  interfaz. Eran las últimas cadenas escritas a mano en el código, y en otro idioma.
- El desplegable de **agrupar por** lleva icono propio: `AllIcons.Actions.GroupBy` se
  dibuja como un ojo en la interfaz nueva y nadie reconocía el botón.
- `verifyPlugin` fija los dos extremos declarados —2025.2 y 2026.2— en vez de
  conformarse con lo que recomiende el verificador.

### Corregido
- El ratón vale sobre la **tarjeta entera**. El marcador y el menú `⋮` sólo aparecían
  pasando por encima de las letras: el árbol daba la fila por acabada donde acaba el
  texto, aunque la tarjeta se pinte hasta el borde.
- El visto del desplegable de agrupar se quedaba en la opción anterior aunque la lista
  sí se reagrupara.

### Quitado
- La **lista de comprobación** (`- [ ]`) sale de la barra de formato y de la tarjeta.
  No se renderizaba en la fila —se veían los corchetes en crudo— y no compensaba
  arreglarla: el cuerpo sigue admitiendo el texto, simplemente no se cuenta.

## [0.7.1]

- La fila pinta el énfasis de Markdown (`InlineMarkdown`).
- El ratón responde sobre toda la tarjeta (`RowHit`).

## [0.7.0]

Abre la Fase 7 con el pulido de uso: los iconos propios de la barra de formato y
`Escape` cerrando el diálogo también con el cursor dentro del cuerpo.

## [0.6.0] — [0.6.10]

Fase 6 (imágenes) y el **rediseño a tarjetas** completo, con su propia numeración y su
propio plan en [`docs/plan-rediseno.md`](docs/plan-rediseno.md): filas como tarjeta,
estados en una fila con su recuento, filtro de vista, adjuntos por arrastre, etiquetas
como fichas, calendario para el vencimiento y un solo sitio donde crear tareas.

## [0.1.0] — [0.5.0]

Las cinco primeras fases: dominio y persistencia, estados y prioridades configurables,
multi-repositorio, teclado y búsqueda, enlaces y exportación. El detalle de cada una
está en [`docs/architecture.html`](docs/architecture.html) §13.
