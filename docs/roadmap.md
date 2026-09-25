# Roadmap — próximas adiciones

Lo que se ha propuesto añadir a Tasklane después de la `1.0.0`, con su estado. No es un
compromiso: es la lista de la que se elige cada iteración, y el registro de lo que ya se
ofreció para no volver a ofrecerlo como si fuera nuevo.

Cada iteración elegida sale como **versión media** (ver la regla de versiones en el
README). Las propuestas se numeran con `P` y no se renumeran nunca.

| Estado | Qué significa |
|---|---|
| ✅ Hecha | Publicada; se indica la versión |
| 🟡 Propuesta | Ofrecida y todavía sin decidir |
| ⏸️ No aceptada | Ofrecida y no elegida. No está descartada: puede volver, pero no como novedad |

---

## Hechas

Propuestas de revisiones anteriores que se eligieron:

| | Qué | Versión |
|---|---|---|
| ✅ | Anclas de código y *Move To ▸* sin diálogo | `1.1.0` |
| ✅ | El código enseña sus tareas (margen o pastilla) | `1.2.0` |
| ✅ | Borrado con *Undo* y aviso de vencimientos | `2.9.0` |
| ✅ | Tareas en *Search Everywhere* y archivo de lo terminado | `2.10.0` |
| ✅ | Orden manual por estado y listas de comprobación | `2.11.0` |
| ✅ | P16 · Tasklane para agentes de IA (herramientas MCP) | `2.12.0` |

---

## Ronda del 2026-09-25 · ⏸️ no aceptadas

Salieron de revisar el código después de la `2.11.2`. No se eligió ninguna.

### P1 · Anclas que sobreviven a renombrar y mover ficheros ⏸️
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

### P4 · Widget en la barra de estado ⏸️
«3 abiertas · 1 vencida» del repositorio activo; un clic abre la ventana, y cambia de
color si hay algo vencido. Hoy no hay ningún `StatusBarWidget`.

### P5 · Compartir tareas (importar XML) ⏸️
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

## Ronda del 2026-09-25 (segunda) · ⏸️ no aceptadas

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

### P12 · Anclar un rango, no una línea ⏸️
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

### P15 · Deshacer todo, no sólo el borrado ⏸️
Completar, mover de estado, cambiar la prioridad, marcar o reordenar —sobre todo en
selecciones grandes— se deshacen con `⌘Z` igual que el borrado. Todo pasa ya por
`TaskCommand` / `TaskReducer`, que es el sitio natural para guardar la inversa.

---

## Ronda del 2026-09-25 (tercera) · 🟡 propuestas

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

### P17 · Trabajar en una tarea (tarea activa con su contexto) 🟡
*Start Working* sobre una tarea: pasa a *Doing*, abre sus anclas y se queda como **tarea
activa** —en el título de la ventana y en la barra de estado—. Al cambiar a otra tarea se
guardan las pestañas del editor abiertas y se restauran las de la nueva, como hacían los
*Contexts* del antiguo plugin Tasks, pero sin servidor de issues. Al terminar,
*Complete* devuelve el contexto anterior. Opcional: crear o cambiar a una rama con el
título de la tarea.

### P18 · El fichero actual y sus tareas 🟡
Tres piezas que usan el operador `file:` que ya existe:
- **Seguir al editor**: un conmutador que filtra la lista a las tareas ancladas al
  fichero abierto, y cambia cuando cambias de pestaña.
- **Banner en el editor** (`EditorNotificationProvider`): «2 tareas abiertas en este
  fichero», con un enlace a cada una y a la lista filtrada.
- **Recuento en el Project View** (`ProjectViewNodeDecorator`): `AuthService.kt · 2` en
  ficheros y carpetas, para ver de un vistazo dónde se acumula la deuda.

Hoy la relación va sólo de la tarea al código. Con esto va también del código a la tarea
sin tener que pasar por la línea exacta.

### P19 · Tablero en una pestaña del editor 🟡
Un `FileEditor` con los estados en columnas y las mismas tarjetas, arrastrando entre
columnas para mover de estado. La tool window vive estrecha (ver su nota de diseño): es
la herramienta del día a día, pero no da la vista de conjunto. El tablero usa el espacio
ancho del editor para planificar, y se abre desde la cabecera o con *Open Board*.

### P20 · Tarea desde un fallo 🟡
En la consola de Run/Debug y en el árbol de tests: *Create Tasklane Task* sobre un test
que falla o sobre una traza de excepción. El cuerpo lleva el nombre del test, el mensaje
y la traza en un bloque de código; el ancla va al primer marco que sea del proyecto (no
de librerías). Es el momento en que más notas se pierden: «esto falla, luego lo miro».

### P21 · Espejo Markdown en el repositorio 🟡
Opcional por repositorio: Tasklane mantiene un `TASKS.md` generado —abiertas por estado y
prioridad, con enlaces a las anclas relativos al repo— que se reescribe al cambiar algo.
Es de sólo lectura; la fuente de verdad sigue siendo la base. Las tareas se ven en
GitHub, en un PR o desde otro editor sin montar sincronización. Es un paso mucho más
barato que compartir de verdad (P5).

### P22 · Los TODO del código, siempre al día 🟡
Hoy *Import TODO Comments…* es un paso único. Proponer una inspección que marque los
`// TODO` **nuevos** sin tarea —con el *Alt+Enter* que ya existe como arreglo— y un
aviso al hacer commit si se añaden TODOs sueltos. Apagable, y sin tocar nunca los
comentarios que el usuario decidió dejar.
