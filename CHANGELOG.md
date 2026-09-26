# Changelog

Todas las versiones publicables del plugin. El formato sigue
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y el versionado, semver:
hasta la `1.0.0` la versión media era **la fase cerrada** (fase N → `0.N.0`); a partir
de ahí manda semver sobre lo publicado.

> Al subir la versión hay que tocar tres sitios: `pluginVersion` en `gradle.properties`,
> `changeNotes` en `build.gradle.kts` —que es lo que sale en la ficha del Marketplace y
> en el diálogo de actualización del IDE— y este fichero.

## [2.19.0]

Menor sin cambio de formato: pegar y soltar en la lista. Es la P27 de
[`docs/roadmap.md`](docs/roadmap.md).

### Añadido
- **`⌘V` sobre la lista crea tareas** con lo que haya en el portapapeles, en el estado que se
  está mirando —en el tablero, en esa columna—:
  - **Texto: una tarea por línea**, sin la viñeta (`-`, `*`, `+`, `•`), la numeración (`1.`,
    `2)`) ni la casilla (`[ ]`, `[x]`) de delante, y sin las líneas vacías o de sólo rayas. Es
    copiar la lista de una reunión o de un chat y tenerla. Una URL es una línea más: la tarea
    nace con su enlace.
  - **Una captura**: una tarea con ella.
  - **Ficheros copiados**: lo mismo que al soltarlos.
- **Soltar en la lista** lo que se arrastra desde el Finder, la vista del proyecto, el
  navegador o el editor: una imagen da una tarea con la captura; un fichero de texto o de
  código, una tarea anclada a él, con su nombre de título; y un texto, una tarea por línea. La
  lista se recuadra mientras hay encima algo que se puede soltar.
- **Sin diálogo y sin preguntar**, salvo si son más de diez: lo creado queda seleccionado
  —`Enter` y se le escribe el título— y un `⌘Z` lo quita entero. Con más de diez líneas se
  pregunta si una tarea por línea o **una sola con todo el texto**, que es lo que se quería al
  pegar un párrafo o un log.

### Detalles
- Pegar lee lo mismo y en el mismo orden que el cuerpo del diálogo: primero ficheros, luego
  una imagen y por último texto. Unas celdas copiadas de una hoja de cálculo que traigan
  también su imagen se pegan como imagen, igual que en el diálogo.
- Las carpetas y los ficheros binarios que no son imagen se saltan sin avisar, como en la
  franja de adjuntar del diálogo.
- `⌘V` sólo se queda con la pulsación con el foco en la lista, algo que sirva en el
  portapapeles y un repositorio que se pueda escribir; si no, sigue siendo el de siempre.
- **Formato:** ninguno.

## [2.18.0]

Menor sin cambio de formato: las capturas, también para el agente. Es la P24 de
[`docs/roadmap.md`](docs/roadmap.md).

### Añadido
- **El agente ve las capturas.** `tasklane_get_task` trae `images`: por cada imagen del cuerpo,
  su referencia —`tasklane:<sha>`, la que aparece en el cuerpo— y **la ruta de su fichero**, o
  `missing: true` si ya no está. El servidor MCP del IDE sólo devuelve texto, pero Claude Code,
  Junie y los demás abren imágenes del disco. `tasklane_list_tasks` dice cuántas tiene cada
  tarea, y su consulta admite `has:image`.
- **El agente deja capturas.** `tasklane_create_task` y `tasklane_update_task` aceptan
  `images`: rutas absolutas o relativas a la raíz del proyecto. Se guardan como una imagen
  soltada en el diálogo —tal cual, en el repositorio de la tarea— y van al final del cuerpo,
  una por línea. Al actualizar se añaden: las que la tarea ya tenía siguen.

### Detalles
- Una captura que el cuerpo ya nombra no se repite: un agente que repite la llamada porque no
  vio la respuesta no la duplica.
- Una ruta que no existe, una carpeta o un fichero que no es una imagen fallan con un error que
  lo dice. Las imágenes se guardan lo último: si un estado, una prioridad o un ancla no
  encajan, no queda ningún fichero suelto.
- Las descripciones de las herramientas le piden al agente que conserve las referencias
  `![](tasklane:…)` cuando reescribe el cuerpo.
- **Formato:** ninguno.

## [2.17.1]

Qué estados salen en la tool window, en el tablero y en la barra de estado, elegido en la tabla
de estados de los ajustes.

### Añadido
- **Tres casillas más por estado en la tabla de *States***: *Tool window*, *Board* y *Status
  bar*. Quitar un estado de la ventana le quita su pestaña; del tablero, su columna. De
  fábrica, todos salen en los dos sitios, y un estado nuevo también, sin que haya que marcarlo.
- Si no queda ningún estado en la ventana o en el tablero, lo dicen, con un enlace a los
  ajustes.

### Cambiado
- **Los estados que cuenta la barra de estado se eligen en la columna *Status bar*** de la
  tabla de estados, y no en una fila de casillas aparte que repetía los nombres. Lo de las
  vencidas sigue en su grupo. Lo ya elegido se conserva tal cual.
- `⌥←/→` y `⇧⌥←/→` saltan los estados que no se ven: la pestaña o la columna de al lado es la
  de al lado de verdad. *Move To ▸* sigue ofreciendo todos.

### Detalles
- Las tres casillas son **tuyas**, no del equipo: van a `workspace.xml`, como antes lo que
  contaba la barra de estado, y no a `tasklane.xml`. Se guardan los estados **quitados**.
- Una tarea de un estado sin pestaña no se puede enseñar en la ventana: pulsar su marca en el
  editor abre la ventana sin ella.
- **Formato:** ninguno.

## [2.17.0]

Menor sin cambio de formato: el tablero, en una pestaña del editor. Es la P19 de
[`docs/roadmap.md`](docs/roadmap.md).

### Añadido
- **Los estados en columnas, lado a lado, en una pestaña del editor**: *Open Board*, en la
  barra de la ventana y en *Tools*. La ventana sigue siendo la herramienta del día a día, a su
  ancho estrecho; el tablero es para planificar, con el ancho del editor.
- **Cada columna es la lista de la ventana**: las mismas tarjetas, el mismo menú, los mismos
  atajos y el mismo `⌘Z`, con su agrupación y su orden. Su cabecera dice cuántas tiene, y trae
  `+` para crear en ese estado y *Group By*.
- **Arrastrar una tarjeta a otra columna** la cambia de estado, por el asa ⋮⋮ —la del orden
  manual, que en el tablero sale siempre—. Si es parte de una selección, se va la selección
  entera. En una columna a mano cae entre las dos tarjetas donde se suelta; en las demás, la
  columna entera se recuadra y la tarea va donde la ordene. Cerca de los bordes, el tablero y
  la columna se desplazan solos.
- **Lo movido queda seleccionado en su columna nueva**, con el foco, también con *Move To ▸* y
  `⇧⌥←/→`: en una columna que se ordena sola es la forma de ver dónde ha caído, y un segundo
  `⇧⌥→` lo sigue llevando. `⌥←/→` pasa de columna.
- **Arriba, el buscador, el repositorio y el filtro de la ventana**, los mismos: buscar en el
  tablero es buscar también en la ventana.
- Una pestaña por proyecto: *Open Board* con el tablero abierto va a él.

### Cambiado
- Soltar en otra columna es **un** paso de `⌘Z` aunque cambie de estado y de sitio a la vez:
  lo devuelve a su columna y a su sitio, y la barra de estado dice *Undone: move 2 tasks to
  Doing*, no *reorder*.

### Detalles
- Por dentro, un lote de comandos puede llevar ahora *colocar* además de cambiar: se aplica
  detrás de todo lo demás, porque las vecinas se buscan en el estado en que la tarea ha
  quedado.
- **Formato:** ninguno. El tablero no guarda nada propio: agrupar y ordenar una columna es
  configurar el estado, como en la ventana.

## [2.16.0]

Menor sin cambio de formato: deshacer todo, no sólo el borrado. Es la P15 de
[`docs/roadmap.md`](docs/roadmap.md).

### Añadido
- **`⌘Z` en la lista deshace lo último que se hizo**, sea lo que sea: completar, mover de
  estado, cambiar la prioridad, marcar, reordenar, pulsar una casilla, editar en el
  diálogo, crear —también desde el editor, `⌘⌥R` o un TODO— o borrar. Sobre una tarea o
  sobre una selección entera, que se deshace de una vez. Se guardan los últimos 100 pasos
  o 10.000 tareas entre todos, lo que llegue antes.
- **`⌘⇧Z` lo rehace.** Hacer algo nuevo tira lo que quedaba por rehacer, como en un
  editor.
- **La barra de estado dice qué se deshizo**: *Undone: move 3 tasks to Done*. Hace falta
  porque lo que vuelve puede no estar a la vista: deshacer un *Move To* trae las tareas de
  vuelta desde otra pestaña. Si ya no quedaba nada que deshacer —las tareas cambiaron
  después—, lo dice también.

### Cambiado
- **Cada repositorio tiene su historia.** `⌘Z` deshace lo último del repositorio que se
  está mirando, nunca algo de otro que no se ve; lo de los demás espera a que se vuelva a
  ellos. Lo hecho buscando en todos se deshace desde el repositorio donde se hizo y desde
  el de las tareas.
- **Deshacer devuelve lo que se cambió y nada más**, campo a campo: si entretanto un
  agente le cambió el cuerpo a la tarea que se completó, deshacer la reabre y el cuerpo
  del agente se queda. Y devuelve también las fechas —la de edición y la de cierre—, así
  que una tarea vuelve a su grupo de fecha.
- El *Undo* del aviso de borrar deshace **ese** borrado aunque después se hayan hecho
  otras cosas. Antes sólo valía mientras fuera lo último.
- Lo que hace un agente por las herramientas MCP **no entra** en la pila: `⌘Z` es del
  usuario, y deshacer lo propio no se lleva lo del agente.

### Detalles
- El límite de 10.000 tareas del borrado vale ahora para cualquier gesto: por encima, el
  aviso dice que no hay vuelta y `⌘Z` no deshace lo de antes de ese repositorio. Vaciar o
  quitar un repositorio olvida su historia.
- Reordenar se deshace volviendo a poner la tarea **entre las mismas dos vecinas**, no con
  su número de orden de antes: colocarla puede haber reespaciado el estado entero.
- **Formato:** ninguno. La pila vive en memoria y se pierde al cerrar el proyecto.

## [2.15.0]

Menor: anclar un bloque de código, no sólo una línea. Es la P12 de
[`docs/roadmap.md`](docs/roadmap.md).

### Añadido
- **Un ancla puede abarcar un bloque.** Con varias líneas seleccionadas, *New Tasklane
  Task from Here* ancla la tarea de la primera a la última, y la ficha dice
  `Login.kt:42-58`. En el diálogo y por MCP se escribe igual —`src/Login.kt:42-58`—, y un
  enlace de GitHub con rango, `#L42-L58`, ya no pierde el final.
- **El editor tiñe el bloque entero** con un poco del color de la prioridad, debajo del
  icono o la pastilla de siempre, mientras la tarea siga abierta. El tinte se rehace con el
  tema y deja ver la línea del cursor.
- **La tarjeta desplegada enseña el código** del bloque, tal como está hoy en el fichero y
  no como estaba al anotarlo, con la ficha encima para ir a él. Sigue al editor: si se
  cambia el código, la tarjeta lo ve. De un bloque largo se enseñan 40 líneas y se dice
  cuántas quedan.
- **El tooltip de la ficha lleva el código**, también el de un ancla de una línea: pasar el
  ratón por `Login.kt:42` ya dice de qué trozo habla la nota.

### Cambiado
- Seleccionar varias líneas y crear la tarea desde ahí ya **no copia el código al cuerpo**:
  su primera línea acababa siendo el título. Ahora se ancla el bloque, la tarjeta lo
  enseña y el cuerpo queda para la nota. Una selección dentro de una línea se sigue
  ofreciendo como título.

### Detalles
- El bloque se guarda como la primera línea más **cuántas le siguen**, y se reencuentra
  por la primera: si el código de encima crece, baja entero. Lo que se escriba dentro del
  bloque no se sigue —su final se queda donde estaba—.
- `tasklane_get_task` da `endLine` en los bloques, contada desde donde está hoy la
  primera línea, y el CSV escribe `ruta:42-58`.
- **Formato:** una columna nueva, `anchor.span`, que se añade al abrir la base la primera
  vez; el esquema **sigue en la versión 1**. Una versión anterior abre la base y edita las
  tareas como siempre, pero si guarda una tarea con un bloque, el bloque se queda en su
  primera línea. En `tasks.xml` el largo va en el atributo `span`, que una versión anterior
  ignora. Recuperar una base desde una copia de antes de la columna trae sus anclas igual.

## [2.14.0]

Menor sin cambio de formato: los recuentos en la barra de estado. Es la P4 de
[`docs/roadmap.md`](docs/roadmap.md).

### Añadido
- **La lista, en la barra de estado.** *ToDo 3 · Doing 1 · 2 overdue* del repositorio
  activo, con lo vencido **en rojo**. Cuenta como las pestañas con el filtro *All tasks*
  —el archivo de lo terminado se aplica; el filtro de vista, no—, y cambia al escribir, al
  cambiar de repositorio y **en el momento en que algo vence**, sin esperar a nada.
- **Qué cuenta, a elegir** en *Settings → Tools → Tasklane → Status bar*: una casilla por
  estado —siguen en vivo a la tabla de estados de encima— y otra para las vencidas, que
  sólo salen cuando las hay. De fábrica, los estados no terminales y las vencidas, y un
  estado nuevo entra solo mientras no se haya tocado la elección. Es de cada uno y va a
  `workspace.xml`.
- **Cada cuenta se pulsa.** Un estado abre la ventana en su pestaña; las vencidas llevan a
  esas tareas, como el *Show* del aviso; el icono o cualquier otro sitio abre Tasklane.
- Se enseña o se esconde con el clic derecho sobre la barra, como los widgets del IDE.
  Escondido no cuenta nada.

### Detalles
- Un índice parcial nuevo, `task_due`, con sólo lo abierto que tiene fecha: la cuenta
  cuesta lo que hay por vencer y no lo que hay en el repositorio. Se crea al abrir el
  proyecto la primera vez; el esquema sigue en la versión 1 y una versión anterior lo
  ignora. De paso, el filtro *Overdue* y el aviso de vencimientos ya no ordenan en memoria.

## [2.13.0]

Menor sin cambio de formato: las anclas sobreviven a renombrar y mover ficheros. Es la
P1 de [`docs/roadmap.md`](docs/roadmap.md).

### Añadido
- **Las anclas se van con su fichero.** Renombrar o mover un fichero desde el IDE —a
  mano, con una refactorización o renombrando la clase que le da nombre— reescribe las
  anclas que apuntaban a él. Con un **directorio** pasa lo mismo con todo lo que tiene
  dentro. Vale para las tareas de cualquier repositorio, y **no toca su fecha**: mover
  un fichero no es editar la tarea, así que ninguna salta al grupo de hoy.
- **Anclas rotas a la vista.** Si el fichero desaparece de otra forma —un `mv` o un `rm`
  en el terminal, un `git checkout` que se lo lleva—, el distintivo sale **tachado y con
  el icono de aviso**, en el mismo sitio y con el mismo ancho, y el tooltip dice por qué.
  También en el diálogo de la tarea, que es donde se quita. Si el fichero vuelve, el
  ancla se arregla sola.
- **`has:broken-anchor`** (o `has:broken`) en el buscador, en `⇧⇧` y en
  `tasklane_list_tasks`: las tareas con alguna ancla rota.

### Detalles
- Se comprueba al abrir el proyecto —el disco cambia con el IDE cerrado—, al terminar de
  importar o de recuperar la base, y después sólo lo que toca cada cambio del sistema de
  ficheros y cada comando. Qué anclas están rotas **no se guarda** en la base: es un dato
  del disco, no de la tarea.
- Lo que se renombra desde fuera del IDE no se sigue: llega como un borrado y una
  creación sin nada que los una, y ahí lo honesto es marcar el ancla como rota, no
  adivinar adónde fue.

## [2.12.0]

Menor sin cambio de formato: Tasklane para agentes de IA. Es la P16 de
[`docs/roadmap.md`](docs/roadmap.md).

### Añadido
- **Las tareas, como herramientas MCP.** Con el servidor MCP del IDE encendido
  (*Settings → Tools → MCP Server*), un agente —Claude Code, Junie, AI Assistant o
  cualquier cliente MCP— tiene siete herramientas, todas con el prefijo `tasklane_`:
  `list_repositories`, `list_tasks`, `get_task`, `create_task`, `update_task`,
  `complete_task` y `set_checklist_item`.
  - `list_tasks` sin consulta devuelve lo abierto estado por estado y en el orden de la
    lista; con consulta, lo mismo que el buscador, operadores incluidos. `repository:
    "all"` busca en todos.
  - `get_task` da el cuerpo entero, las casillas numeradas y las anclas **en la línea de
    hoy**, con `anchoredAtLine` si el código se movió y `missing` si el fichero ya no está.
  - `complete_task` y `set_checklist_item` son **idempotentes**: un agente que repite la
    llamada no reabre ni desmarca lo que ya hizo.
  - Los nombres de estado, prioridad y repositorio se aceptan como en el buscador —sin
    mayúsculas ni acentos, por id o por un prefijo único—, y un nombre que no existe falla
    diciendo cuáles hay.
  - **No hay herramienta de borrar**, a propósito: borrar se queda con el usuario y su
    *Undo*.
  - Antes de leer o crear un ancla se refresca **ese fichero** desde disco: un agente edita
    desde fuera del IDE y pregunta enseguida, y el vigilante de disco tarda unos segundos.
- Dependencia **opcional** del plugin *MCP Server* (`tasklane-mcp.xml`), como la de Git:
  sin él no se carga nada y Tasklane no abre ningún puerto.

## [2.11.2]

Sin cambio de formato: el asa de reordenar, mejor colocada.

### Cambiado
- **El asa ⋮⋮ va a media altura** del hueco que dejan el marcador y el menú, y **separada
  4 px del borde derecho**. En la 2.11.1 iba pegada abajo y al borde, a la altura de la
  línea de distintivos, y se leía como uno más de ellos.

## [2.11.1]

Sin cambio de formato: un asa para reordenar y casillas más grandes.

### Cambiado
- **El asa de reordenar pasa a la esquina de abajo a la derecha de la tarjeta**, bajo el
  marcador y el menú: un hueco que la columna de controles dejaba siempre en blanco. Es el
  mismo icono ⋮⋮ del arrastre de filas de *Settings*, sale bajo el ratón como el menú, y
  el cursor de mover lo anuncia. No hace crecer ninguna tarjeta: la columna sigue midiendo
  lo que miden sus controles. Sustituye al arrastre por la franja de prioridad de la
  2.11.0, que eran ocho píxeles sin nada que dijera que se podían arrastrar.
- **Las casillas de las listas de comprobación son más grandes.** Hasta ahora eran el
  glifo `☐` de la fuente, que en macOS sale bastante más pequeño que una letra; ahora se
  dibujan (`CheckBoxIcon`, 12 px), y las marcadas van rellenas en azul con su visto, como
  las casillas del IDE. Al copiar la línea siguen saliendo como `☐` / `☑`.

## [2.11.0]

Menor sin cambio de formato: orden manual por estado, y vuelven las listas de
comprobación.

### Añadido
- **Orden manual.** *Group By ▸ Manual Order* pone un estado a mano: la lista deja de
  ordenarse por prioridad y fecha y sigue `Task.order`, el campo que se guardaba desde la
  Fase 1 sin que nada lo leyera.
  - Se reordena **arrastrando la tarjeta por su franja de color** —con cursor de mover al
    pasar por encima; el resto de la tarjeta sigue seleccionando texto— o con `⌘⇧↑/↓`
    (*Move Up* / *Move Down*, también en el menú contextual y asignables en el Keymap).
  - Al pasar a mano **la lista no se mueve**: el orden se siembra con el que se estaba
    viendo.
  - Lo marcado sigue arriba y no se cruza; lo nuevo entra arriba del todo; la agrupación
    sigue valiendo y se ordena dentro de cada grupo. Buscando no se arrastra: manda la
    relevancia.
  - Es del estado y va a `tasklane.xml`, como la agrupación.
- **Listas de comprobación.** `- [ ] algo` / `- [x] algo` —con `-`, `*`, `+` o `1.`— se
  pintan en la tarjeta como casillas, **también en el título**, sin la marca. Un clic
  escribe o quita la `x` en el cuerpo; lo marcado sale tachado y en gris. La línea de
  distintivos lleva `☑ 2/5`. En el diálogo, el botón *Checklist* convierte en casillas
  las líneas seleccionadas (una viñeta `- algo` pasa a `- [ ] algo`). Lo que va entre
  vallas de código no cuenta.
  Ya estuvieron hasta la `1.0.0` y se quitaron porque una tarea cuyo cuerpo **era** la
  lista enseñaba `- [ ]` en crudo en el título; eso es justo lo que ahora se pinta como
  casilla.

### Cambiado
- `SqlitePager` y `MemoryPager` ordenan un estado a mano por `ord` (y el desempate de
  siempre), con los mismos resultados en las cuatro agrupaciones: lo fija un test.
- Índice nuevo `task_manual`, que se crea **una vez** al abrir una base existente. Con
  cientos de miles de tareas esa primera apertura tarda unos segundos más.
- `Mutation.Place` pone el `ord` entre dos vecinas en el almacén y, si no queda hueco,
  reespacia el estado; `Mutation.SeedOrder` siembra el orden al pasar a mano.
- `TaskCommand.ToggleCheck` vuelve a comprobar que en esa posición sigue habiendo una
  casilla antes de escribir.

## [2.10.0]

Menor sin cambio de formato: las tareas salen en *Search Everywhere* y lo terminado se
puede archivar.

### Añadido
- **Tareas en *Search Everywhere*.** `⇧⇧`, escribir, y las tareas salen en la pestaña
  *All* —detrás de clases, ficheros y símbolos— y en una propia, *Tasklane*. Busca con el
  mismo índice, la misma sintaxis (`p:high`, `#api`, `file:Auth`) y el mismo alcance que
  el buscador de la ventana, para que las dos no respondan cosas distintas. Cada fila
  lleva el punto de su prioridad, el título —tachado si está cerrada— y en gris el
  estado, y el repositorio si hay más de uno. `Enter` abre la tool window con la tarea
  seleccionada. Con la consulta vacía no enseña nada.
- **Archivar lo terminado.** *Settings → Tools → Tasklane → Completed tasks*: esconder
  lo que se cerró hace más de N días (un mes al marcar la casilla; hasta diez años). De
  fábrica no se esconde nada.
  - Sólo en los estados **terminales** y sólo en la lista: buscar sigue encontrándolo
    todo.
  - La lista, las cabeceras de grupo y el contador de la pestaña cuentan lo mismo: lo
    que se ve.
  - **El pie de la lista dice cuántas se esconden**, con *Show* para verlas hasta cerrar
    el proyecto y *Hide them* para volver. Ir a una tarea archivada —`⇧⇧`, una marca del
    editor— las enseña también.
  - Una tarea terminal sin fecha de cierre se ve siempre.
  - Es preferencia de cada uno (`workspace.xml`).

### Cambiado
- `SqlitePager` recibe el corte del archivo y lo aplica a las siete consultas de la
  lista, sobre `completed_at`, que ya estaba en la cola de los tres índices: no hay
  índice nuevo ni salto a la tabla. Los contadores de los estados terminales dejan de
  salir de `counter` mientras haya archivo, y se cuentan con la misma condición.

## [2.9.0]

Menor sin cambio de formato: borrar se puede deshacer y los vencimientos avisan.

### Añadido
- **Deshacer un borrado.** Borrar sigue sin preguntar, pero ahora sale un aviso
  *«3 tasks deleted from backend — Undo»*, y `⌘Z` con el foco en la lista devuelve lo
  último que se borró. Las tareas vuelven **tal como estaban** —el mismo id, las mismas
  fechas, el mismo sitio en su grupo, sus imágenes— y no como copias nuevas, que se
  irían al grupo de hoy. Vale para la selección y para borrar un grupo entero, hasta
  10.000 tareas; por encima el aviso dice que no se puede deshacer. Sólo se deshace lo
  último, como en un editor: un borrado nuevo retira el aviso del anterior.
  Las imágenes no corren peligro: la limpieza de adjuntos sólo pasa al abrir el
  proyecto.
- **Aviso de vencimientos.** Un aviso dice cuántas tareas del **repositorio activo**
  están vencidas y cuántas vencen hoy —*«2 overdue · 1 due today in backend»*—, con
  *Show* para ir a ellas. Sale al abrir el proyecto, al cambiar de repositorio y cada
  media hora, y sólo si hay alguna de la que no se haya avisado ya hoy; el aviso nuevo
  retira el anterior. Se apaga desde el propio aviso (*Don't Remind Me*) o en
  *Settings → Tools → Tasklane → Due dates*, y es preferencia de cada uno
  (`workspace.xml`).

### Cambiado
- `TaskCommand.Restore`: devuelve tareas borradas con su id; las que ya existen se
  dejan como están, así que deshacer dos veces no duplica. Si entretanto se quitó su
  estado o su prioridad, vuelven aparcadas en la de por defecto, como hace la
  renormalización.

## [2.8.0]

Menor sin cambio de formato: un ancla de código se puede escribir, los TODO del código
pasan a Tasklane y el cuerpo entiende bloques de código.

### Añadido
- **Bloques de código entre vallas.** Lo que va entre dos líneas ```` ``` ```` se pinta
  en la tarjeta como un bloque: con la fuente del editor, sobre el mismo fondo que el
  `código` en línea, una línea de tarjeta por línea de código y **sin interpretar
  nada** de dentro —un `*` de un puntero ya no abre una cursiva—. Las líneas largas se
  recortan en vez de partirse, porque partir una línea de código inventa dos que no
  existen. Una valla sin cerrar llega hasta el final, como en CommonMark, y el título
  de la tarea nunca es una valla.
- En el diálogo, el botón de **código** envuelve en una valla las líneas enteras cuando
  la selección cruza más de una; con un trozo de una línea sigue poniendo `` ` ``.
- Copiar una tarea copia el código entre vallas **literal** y sin las vallas.
- **`Alt+Enter` sobre un comentario TODO → *Move TODO to Tasklane*.** Abre el diálogo
  de tarea nueva con el texto del TODO, su palabra clave como etiqueta (`#todo`,
  `#fixme`) y el ancla en esa línea; al crear, **quita el comentario** del código y la
  marca de la tarea ocupa su sitio. `⌘Z` en el editor lo devuelve. Si el comentario
  lleva algo más que el TODO —un KDoc con una línea de TODO dentro—, la entrada se
  llama *Create Tasklane task from TODO* y el comentario se queda. Usa los patrones de
  TODO del propio IDE (*Settings | Editor | TODO*).
- ***Import TODO Comments…*** (menú *Tools* y menú *Export* de la ventana): todos los
  TODO del proyecto en una lista para elegir, y cada uno pasa a ser una tarea anclada a
  su línea, en una sola operación. No toca el código. Los que ya tienen su tarea salen
  desmarcados, así que importar dos veces no duplica.
- **Autocompletado de rutas** en el campo *Code*: se escribe `deploy` y ofrece
  `plans/website_deployment_live_activity_implementation.md`; luego se añade `:28`.
- **El diálogo de la tarea acepta un sitio del código escrito a mano.** En la fila
  *Code* se escribe `plans/website_deployment_live_activity_implementation.md:28` —la
  ruta relativa a la carpeta del proyecto y la línea— y `Intro` la convierte en la
  misma ancla que saldría de crear la tarea desde el editor con el cursor ahí: con su
  marca en el margen, su tooltip, su clic de vuelta y el texto de la línea guardado
  para reencontrarla cuando el fichero cambie. Sirve para el caso en que el sitio ya
  viene escrito —una traza, un chat, el plan que acaba de dejar un agente— y abrir el
  fichero sólo para volver a señalarlo era un rodeo.
- Acepta también `ruta:línea:columna`, `ruta` a secas (el fichero por su principio),
  `ruta#L28` como se copia de GitHub y `ruta(28)` como lo escriben algunas trazas; una
  ruta absoluta de dentro del proyecto se guarda relativa.
- Una ruta escrita y sin confirmar **cuenta** al aceptar el diálogo, como las
  etiquetas. Si no existe, es una carpeta o la línea se sale del fichero, el diálogo se
  para y lo dice junto al campo, en vez de guardar un ancla rota.
- El fichero se busca refrescando el disco: un fichero que otro programa acaba de
  escribir y el IDE aún no ha visto se encuentra igual.

### Cambiado
- `TaskCommand.CreateMany`: varias tareas nuevas en una transacción y un repintado. Lo
  estrena la importación de TODO.
- **La fila *Code* se ve siempre**, también sin anclas: además de enseñarlas, ahora es
  donde se escriben.

### Corregido
- **Crear una tarea desde la tool window o con `⌘⌥R` ya no pierde sus anclas.** Esos
  dos caminos no pasaban las anclas del diálogo al crear; hasta ahora no importaba
  porque ahí no se podía poner ninguna.

## [2.6.3]

Parche sin cambio de formato: el desplazamiento de la lista y el alto de las tarjetas.

### Corregido
- **La lista ya no se dibuja con las alturas del ancho anterior.** El árbol guarda la
  altura de cada fila y el renderer envuelve el título contra el ancho que hay en ese
  momento; al cambiar de ancho había que tirarle las medidas viejas, y eso se hacía
  desde un `componentResized`, que **no es una llamada sino un evento encolado**. Entre
  encolarlo y atenderlo, Swing termina de repartir el sitio y pinta: esa pasada salía
  con el ancho nuevo y las alturas viejas —tarjetas a las que les falta el último
  renglón— y, peor, el `JScrollPane` decidía con esas mismas alturas si hacía falta
  barra y hasta dónde llega la lista, así que la lista se anunciaba más corta de lo que
  es y la última tarjeta se quedaba cortada contra el borde sin forma de bajar más.
  Ahora las medidas se tiran en el `setBounds` del propio árbol, dentro del reparto de
  sitio y antes de pintar. Y el ancho cambia solo, sin tocar la tool window: el árbol
  mide exactamente lo que se ve, así que **aparecer la barra de desplazamiento ya lo
  estrecha**.
- **Desplazarse ya no remide la lista entera.** Aquel `componentResized` también
  saltaba cuando lo que cambiaba era el **alto** del árbol, que es lo que pasa cada vez
  que entra una página, se despliega un grupo o llega una captura. Remedir mueve las
  filas de arriba, y el hueco visible se guarda en píxeles: la lista daba un salto justo
  mientras se bajaba por ella. Ahora sólo el ancho remide, y cuando hay que remedir por
  otra cosa —una captura que llega, una tarjeta que se despliega— se apunta qué fila
  está asomando y se vuelve a dejar donde estaba.
- **Bajar por un grupo ya no cierra los de abajo.** El presupuesto de filas del primer
  pintado descuenta lo que cada grupo lleva cargado, así que pedir páginas en el grupo
  por el que uno se desplaza lo agotaba y plegaba a los demás en el siguiente
  repintado: dos «cargar más» bastaban, y media lista desaparecía por debajo. Lo que el
  presupuesto abre se queda abierto; cerrarlo sigue siendo cosa de quien lo pulsa.

## [2.6.2]

Parche sin cambio de formato: copiar deja de mentir.

### Corregido
- **«N tareas copiadas» ya sólo se dice cuando el texto está de verdad en el
  portapapeles.** Copiar acababa en `CopyPasteManager`, y ahí un portapapeles ocupado
  —un gestor de historial, un menú cerrándose— sale por un `IllegalStateException` que
  la plataforma **intenta una sola vez**, manda a `LOG.debug` y se traga: la llamada
  vuelve como si nada. Hasta la 2.6.1 lo siguiente era anunciar la copia, así que el
  aviso salía, el portapapeles seguía con lo de antes y no quedaba rastro de por qué.
  Ahora se escribe, **se relee del portapapeles del sistema** —no del propio
  `CopyPasteManager`, que devuelve lo que él mismo se apuntó—, se reintenta con una
  pausa por medio y, si aun así no entra, se dice con un aviso de error en vez de
  cantar una copia que no existe. Vale para las tres copias de la pestaña: el estado,
  el grupo de fecha y la selección.
- **«Exportar y quitar» ya no borra un repositorio cuya copia no ha podido comprobar.**
  Era el mismo fallo con la peor consecuencia posible: el texto se daba por copiado y
  las tareas se borraban detrás. Si el portapapeles rechaza lo exportado, el
  repositorio se queda donde estaba.
- **Una copia que se rompe a mitad ahora se explica.** Ese camino no tenía
  `onThrowable`, así que un error leyendo las tareas se quedaba en el log del IDE y en
  la ventana no pasaba nada.

## [2.6.1]

Menor sin cambio de formato: mirar una captura deja de ser un vistazo y pasa a poder
quedarse delante mientras se escribe código.

### Añadido
- **La ventana de una captura ampliada se puede fijar.** La chincheta de su cabecera la
  deja **encima del editor** y deja de cerrarse al pulsar fuera, así que se puede
  programar con la imagen delante —el error que hay que reproducir, el diseño que hay
  que copiar—. Fijada se cierra con su ✕ o con Esc estando encima de ella; suelta sigue
  yéndose al primer clic fuera, como hasta ahora. Vale en los tres sitios desde los que
  se amplía: el editor del cuerpo de la tarea, la vista previa de la tarjeta desplegada
  y el contador de capturas de la fila.
- **El contador de capturas de la fila (`1 img`) se pulsa y se mira.** Pulsarlo amplía
  las imágenes de la tarea sin desplegar la tarjeta ni abrir el diálogo; pararse encima
  enseña **la captura**, no una frase. Hasta la 2.5 ese icono se veía pulsable y no
  hacía nada.

### Corregido
- **La chincheta y la ✕ de la ventana ampliada ya no quedan pegadas a la esquina.**
  El botón que se cuelga de la cabecera de un popup entra sin ningún margen propio —a
  diferencia del botón de fábrica de la plataforma, que sí lo trae—, y con uno solo no
  se notaba; con los dos juntos —fijado— quedaban encajados contra la curva de la
  ventana.
- **La imagen de una tarjeta desplegada ya no desaparece a ratos.** El árbol guarda el
  alto de cada fila una vez y el contenido se monta contra el ancho de cada momento:
  aparecer o desaparecer la barra de desplazamiento dejaba la tarjeta pidiendo unos
  píxeles más de los que tenía, y la línea que sobraba —la vista previa entera— se caía.
  Quedaba una tarjeta alta y vacía sin nada que explicara la diferencia. Ahora la vista
  previa **encoge** esos píxeles en vez de irse.

### Compatibilidad
- Sin cambio de formato: el esquema sigue en la versión 1, y la 2.5.2 abre un proyecto
  usado por la 2.6.1 y al revés.

## [2.5.2]

Parche sin cambio de formato: mejoras de uso diario y correcciones de contraste en el editor.

### Añadido
- **Copiar el texto completo de una tarea con un clic**, sin metadatos de la tarjeta ni marcas
  Markdown.
- **Borrar desde el menú contextual todas las tareas de un grupo** seleccionado.

### Corregido
- El buscador ya no revierte ni borra caracteres cuando se escribe rápido y hay varios paneles
  de estado abiertos.
- El texto del editor de tareas conserva contraste y fondo correctos en temas oscuros del IDE.

### Compatibilidad
- Sin cambio de formato: el esquema sigue en la versión 1, y la 2.5.1 abre un proyecto usado
  por la 2.5.2 y al revés.

## [2.5.1]

Parche sin cambio de formato: capturas de la ficha (`docs/screenshots/`, README y
descripción del Marketplace) rehechas sobre la interfaz actual —ventana, tarjeta
desplegada, diálogos, menús contextuales y de exportación, marca en el editor y
ajustes—.

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
