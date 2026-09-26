# Roadmap — próximas adiciones

Lo que se ha propuesto añadir a Tasklane después de la `1.0.0`, con su estado. No es un
compromiso: es la lista de la que se elige cada iteración, y el registro de lo que ya se
ofreció para no volver a ofrecerlo como si fuera nuevo.

Cada iteración elegida sale como **versión media** (ver la regla de versiones en el
README). Las propuestas se numeran con `P` y no se renumeran nunca.

| Estado        | Qué significa                                                                 |
|---------------|-------------------------------------------------------------------------------|
| ✅ Hecha      | Publicada; se indica la versión                                               |
| 🟡 Propuesta  | Ofrecida y todavía sin decidir                                                |
| 👾 Para hacer | Para hacer, se van haciendo por orden en que aparezcan en el archivo          |
| ⏸️ No aceptada | Ofrecida y no elegida. No está descartada: puede volver, pero no como novedad |

---

## Hechas

Propuestas de revisiones anteriores que se eligieron:

|    | Qué                                                     | Versión  |
|----|---------------------------------------------------------|----------|
| ✅ | Anclas de código y *Move To ▸* sin diálogo              | `1.1.0`  |
| ✅ | El código enseña sus tareas (margen o pastilla)         | `1.2.0`  |
| ✅ | Borrado con *Undo* y aviso de vencimientos              | `2.9.0`  |
| ✅ | Tareas en *Search Everywhere* y archivo de lo terminado | `2.10.0` |
| ✅ | Orden manual por estado y listas de comprobación        | `2.11.0` |
| ✅ | P16 · Tasklane para agentes de IA (herramientas MCP)    | `2.12.0` |
| ✅ | P1 · Anclas que sobreviven a renombrar y mover ficheros | `2.13.0` |
| ✅ | P4 · Widget en la barra de estado                       | `2.14.0` |

---

## Ronda del 2026-09-25

Salieron de revisar el código después de la `2.11.2`. No se eligió ninguna.

### P1 · Anclas que sobreviven a renombrar y mover ficheros ✅ `2.13.0`

`CodeAnchor` guarda la ruta como texto y no hay ningún `BulkFileListener`: `AnchorResolver`
reencuentra la línea dentro del fichero, pero renombrar o mover el fichero deja el ancla
rota sin avisar. Reescribir la ruta en `VFileMoveEvent` / `VFilePropertyChange`, y pintar
como rota el ancla cuyo fichero ya no existe, con filtro `has:broken-anchor`.

### P2 · Integración con commits ⏸️

Un `CheckinHandler` que ofrezca cerrar las tareas ancladas a los ficheros del commit;
guardar el hash en la tarea cerrada, con un clic que abre el commit en el log; opcional,
avisar al hacer commit sobre un fichero con tareas abiertas.

### P3 · Tareas por rama ⏸️

Guardar la rama en la que se creó la tarea, operador `branch:` y un filtro «sólo esta
rama» que sigue al checkout. `git4idea` ya es dependencia.

### P4 · Widget en la barra de estado ✅ `2.14.0`

«3 abiertas · 1 vencida» del repositorio activo; un clic abre la ventana, y cambia de
color si hay algo vencido. Hoy no hay ningún `StatusBarWidget`.

Salió como «ToDo 3 · Doing 1 · 2 overdue»: se elige qué estados cuenta y si cuenta las
vencidas (en rojo, sólo si las hay), y cada cuenta abre su pestaña o sus tareas.

### P5 · Compartir tareas (importar XML) 👾

Mínimo: *Import XML…* en el menú *Export*, fusionando por id —`TasksXmlReader` ya existe y
no tiene acción—. Ambicioso: tareas marcadas como compartidas que van a un fichero
versionado, `.idea/tasklane-shared.xml`, pensado para que Git lo fusione bien.

### P6 · Tareas recurrentes ⏸️

«Cada lunes», «cada 2 semanas»: al completarla se crea la siguiente con el vencimiento
nuevo. Se apoya en el aviso de vencimientos.

### P7 · Enlazar tareas entre sí ⏸️

*Bloqueada por* / *relacionada con*, con distintivo en la tarjeta; la bloqueada sale
atenuada hasta que se cierra la otra.

---

## Ronda del 2026-09-25 (segunda)

Propuestas justo después de la primera. Tampoco se eligió ninguna.

### P8 · Posponer una tarea ⏸️

*Snooze ▸ Mañana / Lunes / Elegir fecha…*: la tarea desaparece de la lista hasta esa
fecha y vuelve sola, arriba y con un aviso. Es distinto del vencimiento —«no me la
enseñes todavía» frente a «tiene que estar hecha»— y es lo que evita que la lista de
abiertas crezca con cosas que hoy no se pueden hacer. Operador `is:snoozed` para verlas.

### P9 · Vistas guardadas ⏸️

Guardar una búsqueda con operadores —`p:high #backend is:overdue`— con un nombre, y que
aparezca en el desplegable del filtro junto a *All / Open / Overdue / Bookmarked*. El
lenguaje de consulta ya existe (`QueryParser`); lo que falta es no tener que reescribirlo.
Por proyecto, en `tasklane.xml`, para que el equipo comparta las vistas.

### P10 · Referencias a issues como enlaces ⏸️

Que `#123` o `PROJ-45` en el título o el cuerpo se pinten como enlace pulsable. Sin
configuración propia: se leen las reglas de *Settings → Version Control → Issue
Navigation* del IDE (`IssueNavigationConfiguration`), que el proyecto seguramente ya
tiene. Encaja con los enlaces de un clic que ya pinta la tarjeta.

### P11 · Duplicar y plantillas de tarea ⏸️

*Duplicate* en el menú contextual —misma prioridad, etiquetas y anclas, casillas
desmarcadas—, y plantillas por proyecto en `tasklane.xml` («Bug»: pasos, esperado,
obtenido, checklist) elegibles desde la flecha de *New Task*.

### P12 · Anclar un rango, no una línea 👾

Con una selección en el editor, el ancla guarda de la línea X a la Y: el editor resalta
el bloque entero, el tooltip enseña el fragmento y la tarjeta desplegada lo pinta como
bloque de código. Hoy `CodeAnchor` sólo tiene `line` y `column`.

### P13 · Historial de la tarea ⏸️

Registrar cuándo se creó, cuándo cambió de estado y de prioridad, y cuándo se cerró;
enseñarlo al pie de la tarjeta desplegada. De ahí sale gratis «cuánto lleva en *Doing*»
y un aviso para lo que lleva semanas parado. Hoy sólo hay `createdAt`, `updatedAt` y
`completedAt`.

### P14 · Resumen para la daily ⏸️

Una acción que copia al portapapeles lo cerrado desde la última jornada, lo que está en
curso y lo vencido, en Markdown y agrupado por repositorio. Ahora se puede hacer
copiando grupos de fecha uno a uno.

### P15 · Deshacer todo, no sólo el borrado 👾

Completar, mover de estado, cambiar la prioridad, marcar o reordenar —sobre todo en
selecciones grandes— se deshacen con `⌘Z` igual que el borrado. Todo pasa ya por
`TaskCommand` / `TaskReducer`, que es el sitio natural para guardar la inversa.

---

## Ronda del 2026-09-25 (tercera)

Salen de una revisión más a fondo, buscando lo que **sólo** puede hacer un plugin que vive
dentro del IDE y junto al código, no lo que tendría cualquier gestor de tareas. Van
ordenadas por el valor que creo que aportan.

### P16 · Tasklane para agentes de IA (herramientas MCP) ✅ `2.12.0`

El IDE trae el plugin `mcpserver`, con el punto de extensión `com.intellij.mcpServer.mcpToolset`.
Tasklane registraría ahí sus herramientas: `list_tasks` (con el mismo lenguaje de
consulta), `get_task` (cuerpo, anclas, checklist), `create_task`, `update_task`,
`complete_task` y `toggle_checklist_item`. Así Claude Code, Junie o AI Assistant pueden
recibir «haz la tarea de Tasklane sobre el login»: leen la nota y sus anclas, trabajan,
marcan la checklist y la cierran; o apuntan en Tasklane lo que dejan pendiente en vez de
sembrar `// TODO`. Dependencia **opcional**, como `Git4Idea`: sin el plugin no cambia nada.
Es lo que convierte la lista en la memoria compartida entre la persona y el agente.

### P17 · Trabajar en una tarea (tarea activa con su contexto) ⏸️

*Start Working* sobre una tarea: pasa a *Doing*, abre sus anclas y se queda como **tarea
activa** —en el título de la ventana y en la barra de estado—. Al cambiar a otra tarea se
guardan las pestañas del editor abiertas y se restauran las de la nueva, como hacían los *Contexts*
del antiguo plugin Tasks, pero sin servidor de issues. Al terminar, *Complete* devuelve el contexto
anterior. Opcional: crear o cambiar a una rama con el
título de la tarea.

### P18 · El fichero actual y sus tareas ⏸️

Tres piezas que usan el operador `file:` que ya existe:

- **Seguir al editor**: un conmutador que filtra la lista a las tareas ancladas al
  fichero abierto, y cambia cuando cambias de pestaña.
- **Banner en el editor** (`EditorNotificationProvider`): «2 tareas abiertas en este
  fichero», con un enlace a cada una y a la lista filtrada.
- **Recuento en el Project View** (`ProjectViewNodeDecorator`): `AuthService.kt · 2` en
  ficheros y carpetas, para ver de un vistazo dónde se acumula la deuda.

Hoy la relación va sólo de la tarea al código. Con esto va también del código a la tarea
sin tener que pasar por la línea exacta.

### P19 · Tablero en una pestaña del editor 👾

Un `FileEditor` con los estados en columnas y las mismas tarjetas, arrastrando entre
columnas para mover de estado. La tool window vive estrecha (ver su nota de diseño): es
la herramienta del día a día, pero no da la vista de conjunto. El tablero usa el espacio
ancho del editor para planificar, y se abre desde la cabecera o con *Open Board*.

### P20 · Tarea desde un fallo ⏸️

En la consola de Run/Debug y en el árbol de tests: *Create Tasklane Task* sobre un test
que falla o sobre una traza de excepción. El cuerpo lleva el nombre del test, el mensaje
y la traza en un bloque de código; el ancla va al primer marco que sea del proyecto (no
de librerías). Es el momento en que más notas se pierden: «esto falla, luego lo miro».

### P21 · Espejo Markdown en el repositorio 👾

Opcional por repositorio: Tasklane mantiene un `TASKS.md` generado —abiertas por estado y
prioridad, con enlaces a las anclas relativos al repo— que se reescribe al cambiar algo.
Es de sólo lectura; la fuente de verdad sigue siendo la base. Las tareas se ven en
GitHub, en un PR o desde otro editor sin montar sincronización. Es un paso mucho más
barato que compartir de verdad (P5).

!!! Esto debe hacerse si el user lo activa en settings 

### P22 · Los TODO del código, siempre al día ⏸️

Hoy *Import TODO Comments…* es un paso único. Proponer una inspección que marque los
`// TODO` **nuevos** sin tarea —con el *Alt+Enter* que ya existe como arreglo— y un
aviso al hacer commit si se añaden TODOs sueltos. Apagable, y sin tocar nunca los
comentarios que el usuario decidió dejar.

---

## Ronda del 2026-09-25 (cuarta)

Salen de revisar a fondo el código después de la `2.12.0`, en tres pasadas: la interfaz,
el dominio con los datos y MCP, y la caza de lo que parece vivo y no lo está. Ninguna repite
una propuesta anterior, y cada una dice dónde está el hueco. Van ordenadas por el valor que
creo que aportan.

### P23 · Copias de seguridad que sobreviven al proyecto 🟡

Hoy hay **una** copia, `tasklane.db.backup`, en la misma carpeta que la base y sin las
imágenes. `git clean -fdx`, o borrar `.idea` para «resetear» el proyecto, se lleva la base,
la copia y las capturas de una vez, porque la carpeta se ignora a sí misma. Además la copia
sólo se intenta al abrir el proyecto (`MaintenanceActivity` es quien la llama, y nadie más):
un proyecto abierto tres semanas lleva tres semanas sin copia. Propuesta: copias rotadas
—los últimos 7 días y 4 semanas— en el directorio de sistema del IDE (`PathManager`), fuera
del proyecto y con las imágenes; la copia diaria también con el proyecto abierto; y
*Restore from Backup…* junto a *Diagnostics*, que lista las copias con su fecha y cuántas
tareas tiene cada una, y aparta la base actual antes de sustituirla, como hace la
reparación. Y lo que más importa: abrir un proyecto **sin base** pero con copias fuera
ofrece recuperarlas («Hay una copia de hace 2 días con 340 tareas · Restore»).

### P24 · Las capturas, también para el agente 🟡

`tasklane_get_task` sólo dice cuántas imágenes tiene la tarea (`images: 2`), y el cuerpo
lleva `![](tasklane:<sha>)`, que fuera del IDE no significa nada. Media tarea es una
captura, así que el agente trabaja a ciegas justo cuando la imagen era la explicación. El
servidor MCP del IDE sólo devuelve texto, pero puede devolver **la ruta del fichero**, y
Claude Code o Junie abren imágenes del disco: `AttachmentService.file()` ya existe y nadie
la llama. En la otra dirección, adjuntar una imagen desde una ruta en `create_task` y
`update_task`, para que el agente deje la captura de lo que ha hecho o el diagrama que ha
generado.

### P25 · Saber qué ha hecho el agente 🟡

Lo que hace un agente aparece en la lista en el acto, pero sin firma: una tarea cerrada por
Claude Code de madrugada es igual que una cerrada a mano. El servidor MCP dice qué cliente
llama (`ClientInfo.name`, hoy sin usar). Propuesta: guardar quién creó y quién tocó por
última vez cada tarea; un distintivo en la tarjeta, «Claude Code»; un operador `by:`
(`by:agent`, `by:claude`); y un aviso agrupado cuando el agente cierra o crea algo
—«Claude Code closed 2 tasks, created 1 · Show»— para revisar su trabajo. No es el
historial (P13): sólo la firma y el aviso. Falta comprobar que esa API ya está en la
2026.1.5 y no sólo en la 2026.2.

### P26 · Encargar una tarea a un agente 🟡

Para que un agente haga una tarea hay que ir a su terminal y escribir «haz la tarea de
Tasklane sobre…». Una acción en la tarjeta, *Hand Off to Agent*, que abre una pestaña de la
terminal del IDE con el comando configurado —de fábrica `claude "…tarea <id>…"`, o
cualquier otro: `codex`, `gemini`…— y pasa la tarea a *Doing*. Sin comando configurado,
*Copy Agent Prompt* copia la petición. De paso, *Copy Agent Instructions* da el párrafo para
`CLAUDE.md` o `AGENTS.md` que le dice al agente «lo pendiente, a Tasklane, no a `// TODO`».
La terminal va como dependencia opcional, como Git y MCP; `TerminalToolWindowManager` ya
abre una pestaña con un comando. No es P17: no cambia tu contexto, delega la tarea.

### P27 · Pegar y soltar en la lista 🟡

Tasklane existe para capturar, pero la lista no acepta nada: `⌘V` sobre ella no hace nada y
soltar algo tampoco (el único `DropTarget` es el del diálogo). Propuesta: pegar **varias
líneas** crea una tarea por línea —quitando `-`, `*`, `- [ ]` y la numeración, y preguntando
si son muchas—, que es copiar la lista de una reunión o de un chat y tenerla; pegar **una
captura** crea una tarea con ella, y **una URL**, la tarea con su enlace. Soltar ficheros
desde el Finder o la vista del proyecto: una imagen da una tarea con la captura, y un
fichero de código, una tarea anclada a él. `TaskCommand.CreateMany` ya existe.

### P28 · La búsqueda enseña por qué casa 🟡

Se busca en el cuerpo entero, pero la tarjeta plegada enseña siempre **la primera línea**
del cuerpo y sólo resalta en el título: si `token` casa en la línea doce, la tarjeta no dice
por qué ha salido. Propuesta: con una búsqueda activa, la línea del cuerpo que se enseña es
la que casa, con el término resaltado, y el resaltado también ignora los acentos, como la
búsqueda (hoy `autenticacion` encuentra `autenticación` pero no la resalta).

### P29 · El lenguaje de consulta, completo 🟡

La ventana filtra por vencidas y marcadas, y el CSV exporta vencimiento, fechas y checklist,
pero nada de eso se puede **buscar**: `is:overdue` se busca como texto y devuelve cero, y
`-p:low` acaba buscando `p low` en positivo. Propuesta: `is:overdue`, `is:bookmarked`,
`has:due`, `has:checklist` y `has:tag`; negación con `-` (`-#wip`, `-p:low`, `-is:done`);
fechas: `due:today|week|<7d`, `closed:yesterday|week`, `created:>2026-09-01`; y
autocompletado de operadores y valores en el buscador —estados, prioridades, etiquetas—.
Lo ganan también `⇧⇧` y el agente por MCP, que no tienen el filtro de la ventana y hoy no
pueden preguntar «qué está vencido». No es P9: no guarda nada, sólo deja decirlo.

### P30 · Etiquetas de verdad 🟡

Las etiquetas son texto suelto: el campo no sugiere las que ya existen —así nacen `#api` y
`#apis`—, no hay forma de renombrar o fusionar una en todas las tareas, y la ficha de la
tarjeta es gris y no hace nada al pulsarla. Propuesta: autocompletado con las del
repositorio y cuántas tareas tiene cada una; un clic en `#api` en la tarjeta filtra por
ella; una tabla de etiquetas en *Settings* para renombrar, fusionar y borrar, como la de
prioridades y con su reasignación; y color opcional por etiqueta.

### P31 · Vencimiento y etiquetas desde el menú, también sobre una selección 🟡

Prioridad y estado se cambian desde el menú y sobre una selección; vencimiento y etiquetas,
sólo desde el diálogo y tarea a tarea. Propuesta: *Due ▸ Today / Tomorrow / End of Week /
Next Week / Pick Date… / Clear* y *Tags ▸ Add… / Remove ▸* en el menú contextual, para toda
la selección. Como pasó con `Task.order`, las piezas están y nadie las usa:
`TaskCommand.SetDueDate` y `SetTags` están en el reductor y sólo los llaman los tests, y los
preajustes ya están en `DueDates`.

### P32 · Fijar una tarea encima del editor 🟡

La captura ampliada ya se fija con su chincheta y se queda encima del editor mientras se
programa. Lo mismo para la tarea entera: una tarjeta flotante y pequeña con el título, la
checklist —que se marca desde ahí—, las anclas pulsables y las capturas, que sigue a la
vista al cambiar de pestaña y de fichero. Es tener delante los pasos mientras se hacen, sin
la tool window abierta. No es P17: no cambia el estado ni guarda contextos, sólo deja la
nota a la vista.

### P33 · Mover una tarea a otro repositorio 🟡

Con varios repositorios en la ventana, una tarea apuntada en el equivocado se queda ahí: no
hay comando que cambie `task.repo`, el diálogo no tiene selector y no hay *Duplicate*. La
salida hoy es copiar el texto y crearla otra vez, perdiendo fechas, capturas y anclas.
Propuesta: *Move To Repository ▸* —sólo con más de uno—, también sobre una selección, que se
lleva las capturas al almacén del otro repositorio; las anclas valen tal cual, porque son
relativas al proyecto. Y `repository` en `tasklane_update_task`.

### P34 · El teclado, entero de verdad 🟡

`docs/plan-atajos.md` dejó escrito «lo que hay que tocar» y no se hizo, y el README promete
que la ventana «se maneja entera con el teclado», pero desplegar una tarjeta o pulsar un
ancla, un enlace o una casilla de la checklist sólo se puede con el ratón. La agrupación, el
filtro de vista, el selector de repositorio, desplegar y copiar la tarjeta no son acciones
declaradas, así que no se pueden asignar en el *Keymap*. Propuesta: lo que dice el plan
—declarar esas acciones, los atajos locales y `⌘⌥⇧R` para *from Here*—; teclas para
desplegar, marcar y cambiar la prioridad (`1…9`); recorrer los distintivos de la tarjeta con
el teclado; y que un cambio en el *Keymap* se aplique sin reabrir la ventana (hoy se lee una
sola vez).

### P35 · Primeros pasos en una lista vacía 🟡

Un proyecto nuevo enseña «No tasks yet · Press the + button to create one», en texto sin
enlace. Es justo cuando quien acaba de instalar el plugin decide si le sirve, y no se entera
de lo que lo distingue: `⌘⌥R` desde cualquier sitio, *New Tasklane Task from Here* en el
editor, *Import TODO Comments…* y conectar un agente por MCP. Propuesta: esas cuatro como
enlaces en la lista vacía, y un *Got It* la primera vez que se crea un ancla o se pega una
captura. Pesa más ahora, a las puertas del Marketplace.
