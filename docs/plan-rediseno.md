# Rediseño a tarjetas — plan

Trabajo abierto a partir de los dos bocetos del 13/09/2026: la tool window con filas
en forma de tarjeta y el diálogo *New Task* ampliado. No sustituye al plan de ocho
fases de `architecture.html` §13 —la Fase 7, *Robustez y pulido*, sigue pendiente—:
va en paralelo y se numera aparte (R1, R2, …) para no renumerar lo ya publicado.

Decisiones ya tomadas, para no volver a discutirlas:

| | |
|---|---|
| Árbol o componentes | Se sigue con `JTree`. Condiciones: máximo **3 líneas** de título por tarjeta y **doble clic** abre el diálogo con la tarea entera |
| Campos nuevos | `dueDate` y `bookmarked` guardados; la lista de comprobación se **deriva** del cuerpo |
| Agrupaciones | `NONE`, `BY_DATE`, `BY_PRIORITY`, `BY_TAG` |
| Orden de trabajo | Filas → diálogo → cabecera |
| Versión | **Cada iteración sube la versión baja** y deja un plugin instalable: R3 → `0.6.1`, R3.1 → `0.6.2`, R4 → `0.6.3`, … R1 y R2 se quedaron dentro de `0.6.0` porque son anteriores a esta regla |

---

## R1 · Filas ✅

Título envuelto en hasta tres líneas (`TitleWrap`, con su propia medida inyectable),
línea de descripción, línea de distintivos, y las cuatro agrupaciones con `GroupKey`
como identidad de grupo. El árbol recalcula alturas al redimensionar la tool window.

## R2 · Diálogo ✅

Vencimiento por preajustes, etiquetas, barra de formato con negrita/cursiva/código/
enlace/listas/casillas, texto de ayuda en el editor y botón *Create Task*. Con esto
`SetDueDate`, `SetTags` y `ToggleBookmark` dejaron de ser comandos sin llamador.

---

## R3 · Cabecera y barra de herramientas ✅ · `0.6.1`

El contador salió como estaba previsto —`Todo 2`, respetando la búsqueda— y de paso
obligó a lo que era el riesgo del punto: **contar y listar tienen que ser la misma
cuenta**. Ahora lo son, `VisibleTasks`, y la llaman las pestañas y cada panel; el
test de esa invariante compara las dos en cada caso. Lo del «desplegable actual» se
quedó en nada: los estados eran ya pestañas del `ContentManager` desde la Fase 2.

Lo demás de la cabecera: subtítulo con el recuento —«2 tasks · repo» con uno solo,
«2 tasks · 3 projects» con varios—, filtro de vista (todas / abiertas / vencidas /
marcadas) y el atajo escrito dentro del campo de búsqueda, resuelto contra el keymap
para que diga la verdad si el usuario lo reasignó.

Dos decisiones que conviene no volver a discutir:

- **El filtro es de la ventana, no de la pestaña**, y por eso vive en la cabecera
  junto al selector de repositorio y no en la barra de cada estado. Uno por pestaña
  significaría cuatro controles diciendo lo mismo y elegir «vencidas» cuatro veces.
  Se guarda en `workspace.xml`: es cómo está mirando esta persona, no configuración
  del equipo.
- **El botón partido no recuerda el destino.** `SplitButtonAction` lo hace por
  defecto —como el de *Run*—, y aquí sería una trampa: al lado hay pestañas diciendo
  en qué estado se está, y el botón habría dejado de obedecerlas en silencio. De ahí
  `useDynamicSplitButton() = false`.

## R3.1 · Las pestañas bajan al panel ✅ · `0.6.2`

Corrección de R3 tras verlo funcionando. Las pestañas de estado eran `Content`s del
`ContentManager`, así que el IDE las pintaba **en el header**, en la misma línea que
el título de la ventana, el selector de repositorio y el filtro; el recuento —lo que
se quiere leer de un vistazo— quedaba enterrado entre controles que no hablan de
tareas. Ahora hay un solo `Content` con un `CardLayout` y la fila de estados
(`StateTabRow`) vive dentro del panel, pegada encima del buscador.

- El recuento es **un número a secas**: `ToDo 5`, no «5 tasks». La fila entera ya
  habla de tareas.
- El subtítulo de la ventana vuelve a ser el nombre del repositorio. El total no hace
  falta cuando cada estado enseña el suyo.
- El estado abierto se recuerda en `workspace.xml`; antes lo recordaba el
  `ContentManager`.

**Deuda que abre:** se pierde `Alt+←/→` entre estados, que lo ponía el IDE. Si hace
falta, se reimplanta con un atajo local en la fila —es donde tendría que vivir— y no
volviendo a las pestañas nativas.

## R4 · Aspecto de tarjeta ✅ · `0.6.3`

Fondo redondeado por fila con la franja de prioridad **dentro**, recortada por la
misma forma para que no asome por las esquinas; separación vertical entre tarjetas;
distintivo de prioridad con su color —sólo cuando no es la de por defecto, que si no
sería la misma etiqueta en todas las filas—; marcador y menú `⋮` a la derecha, con
*hit testing* propio (`targetAt`) junto al de los enlaces; y el grupo de hoy vacío
con su «You're all caught up!».

Cuatro cosas que se decidieron por el camino:

- **La fila seleccionada no pinta tarjeta.** Ese fondo lo pone el árbol —selección
  ancha, con el color y el redondeo del tema— y taparlo con lo nuestro era pelearse
  con la plataforma. La franja sí se sigue pintando: es lo que identifica la
  prioridad, y desaparecería justo en la fila que se está mirando.
- **Cada fila ocupa todo el ancho visible**, no lo que mida su texto. El árbol da a
  cada fila el tamaño que pida el renderer, y sin forzarlo las tarjetas salían
  dentadas, cada una tan ancha como su frase.
- **Los controles de la derecha ocupan su sitio siempre**, con un icono vacío cuando
  no toca. Escondiéndolos, el título recuperaba esos píxeles y una frase que cabía
  justa en una línea se partía en dos al pasar el ratón: la fila entera daba un salto
  por acercarse a ella.
- **Sólo se fuerza el grupo «hoy»**, y sólo si la lista ya tiene algo y no hay
  búsqueda ni filtro. Que hoy esté vacío es la respuesta que se viene a buscar; que lo
  esté *el martes pasado* no le importa a nadie. Con la lista entera vacía manda el
  «no hay tareas» del árbol, que además dice cómo crear la primera.

El fondo va en `paintComponent` porque es de la fila entera y un borde sólo puede
pintar en sus insets. De paso se fue `PriorityStripeBorder`: la franja ya no es un
borde, es parte de la tarjeta.

## R5 · Adjuntos en el diálogo

- Zona de arrastrar y soltar, y clic para elegir fichero.
- Botón de imagen en la barra de formato (hoy sólo se pega o se arrastra al editor).

## R6 · Deudas conscientes

Ninguna es un fallo: son renuncias tomadas para que la función existiera antes que
su versión bonita. Las de etiquetas, fecha e iconos vienen de R2; la del
desplegable, de R3.

- **Etiquetas como fichas** en vez de un campo con comas. La plataforma no trae
  control de fichas, y escribirlo —borrado con retroceso, navegación con flechas,
  accesibilidad— es un proyecto en sí mismo.
- **Fecha concreta** en el vencimiento, además de los preajustes. Evaluar el
  `DatePicker` de microba que la plataforma empaqueta; es de terceros y no se puede
  ejercitar en los tests, así que hace falta decidir si compensa.
- **Desplegable de agrupación** en la barra. El modelo tiene cuatro agrupaciones
  desde R1 y la barra sólo conmuta *por fecha*; las otras dos se eligen en
  *Settings → Tools → Tasklane*. No es un agujero —se llega—, pero es un viaje largo
  para algo que se cambia mirando la lista.
- **Iconos propios** de calendario, negrita y cursiva. `AllIcons` no trae ninguno de
  los tres, y por eso hoy el vencimiento lleva la palabra «Due» delante y la barra de
  formato usa los glifos `B`, `I`, `</>`. Con SVG propios en `resources` se cerraría.

## R7 · Documentación y versión

- `README.md`: tarjetas, agrupaciones, vencimiento, etiquetas y marcadores; y la
  tabla de fases con el estado de este plan.
- `docs/architecture.html`: los campos nuevos del modelo y su ida y vuelta en el XML,
  `GroupKey` como identidad de grupo, y `TitleWrap` con el porqué de envolver a mano.
- Subir `pluginVersion` a la versión de cierre. El formato de fichero **no** sube de versión: `dueDate` y
  `bookmarked` son atributos nuevos, y el códec ya conserva los desconocidos, así que
  una versión vieja del plugin abre el fichero sin perder nada.

---

## Fuera de este plan

Del repaso de la Fase 6 quedaron apuntados varios fallos de imágenes y recolección de
basura —adjuntos borrados en un repositorio cargado en modo degradado, IO en el EDT
dentro de `ImageInlayRenderer.paint()`, `MarkdownField` sin `setDisposedWith`—. No son
del rediseño y **están sin verificar uno por uno**: pertenecen a la Fase 7.

Ya corregido de esa lista, por tocarlo este trabajo: `createTask()` capturaba el
repositorio activo dos veces alrededor de un diálogo modal, así que un cambio de
repositorio mientras estaba abierto escribía la imagen en uno y creaba la tarea en otro.
