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
| Versión | **Cada iteración sube la versión baja** y deja un plugin instalable: R3 → `0.6.1`, R3.1 → `0.6.2`, R4 → `0.6.3`, R5 → `0.6.4`, R6 → `0.6.5`, R7 → `0.6.6` R1 y R2 se quedaron dentro de `0.6.0` porque son anteriores a esta regla |

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

## R5 · Adjuntos en el diálogo ✅ · `0.6.4`

Franja de arrastrar y soltar bajo la barra de formato —que además es botón: un clic
abre el selector de ficheros del IDE— y botón de imagen en la barra. Con eso son tres
los gestos que adjuntan, y los tres pasan por el mismo sitio, `ImageInserter`:
normalizar fuera del EDT, volver al hilo de UI sólo para insertar, y hacerlo dentro de
un `WriteCommandAction` con nombre para que `⌘Z` deshaga el gesto entero. Repartido
por tres clases, alguna se habría saltado alguna de las tres cosas.

- **Es una franja baja, no el rectángulo del boceto.** El cuerpo es lo que importa en
  este diálogo, y una caja de adjuntos de cien píxeles le robaba la mitad del sitio
  para algo que la mayoría de tareas no usa. La franja sustituye además a la pista de
  «pega una captura»: dice lo mismo y encima se puede usar.
- **`DropTarget` a pelo y no `TransferHandler`**, porque hace falta saber cuándo el
  ratón entra y sale con algo encima para iluminarla, y eso el `TransferHandler` no lo
  cuenta.
- **Soltar algo que no es imagen no avisa de nada**: se filtra por extensión y ya. Un
  PDF soltado encima no es un error del usuario, es que no era una imagen.
- El selector usa `withFileFilter` y no `withExtensionFilter`, que es reciente: esto
  se compila contra 2025.2, el suelo declarado en `sinceBuild`.

## R6 · Deudas conscientes ✅ · `0.6.5`

Las cuatro, resueltas o cerradas con una respuesta. Ninguna era un fallo: eran
renuncias tomadas para que la función existiera antes que su versión bonita.

**Etiquetas como fichas** — hecho (`TagChipsField`). Era la más gorda y se pagó entera
porque el campo con comas mentía sobre el modelo: se veía *un* texto donde hay una
*lista*, y borrar una etiqueta de en medio obligaba a cazar la coma correcta. El
parseo se fue al dominio (`TagParser`, con tests): hay dos entradas —teclear y pegar
una lista— y tienen que dar exactamente lo mismo. Lo que no trae, a propósito: no hay
foco ni navegación con flechas por ficha. El foco vive en el campo de escribir, y
retroceso sobre el campo vacío se lleva la última, que es el gesto que se usa. Y el
texto a medio escribir cuenta como etiqueta al aceptar: nadie entiende que una
etiqueta que está viendo escrita no se guarde.

**Fecha concreta** — hecha, con calendario propio. La evaluación del `DatePicker` de
microba que pedía el plan tiene respuesta: **no compensa**. No está en el classpath de
compilación de un plugin —habría que apuntar a un jar de dentro de la instalación del
IDE, cuyo nombre y sitio cambian entre versiones— y encima es Swing antiguo que no
sigue el tema. Pintar seis filas de siete celdas cuesta menos que eso y sale con los
colores del IDE; la aritmética, que es donde se falla, vive en `MonthGrid` y tiene
tests. La fecha elegida vence al acabar el día, igual que los preajustes.

**Desplegable de agrupación** — hecho. Las cuatro agrupaciones en la barra.
`Tasklane.GroupByDate` sigue declarada aunque ya no esté ahí: es asignable en el
keymap y quien le puso un atajo espera que siga funcionando.

**Iconos propios** — calendario sí, negrita y cursiva **no**, y es definitivo. El
calendario hacía falta: sin él la fila tenía que escribir «Due» delante de la fecha
para que no se confundiera con la de modificación, que va justo al lado. `B` e `I`, en
cambio, se quedan como glifos: son las letras dibujadas con la fuente de la interfaz,
nítidas a cualquier escala y entendidas en todos los editores del mundo. Un trazo
propio sólo sería una `B` peor.

## R7 · Documentación y versión ✅ · `0.6.6`

- `README.md`: la anatomía de la tarjeta, las cuatro agrupaciones, vencimiento,
  etiquetas y marcadores, y la tabla de este plan con la versión de cada iteración.
- `docs/architecture.html`: `dueDate` y `bookmarked` en el modelo y su ida y vuelta en
  el XML, por qué la lista de comprobación se **deriva** en vez de guardarse,
  `GroupKey` como identidad de grupo —y por qué por clave y no por índice—, y
  `TitleWrap` con el porqué de envolver a mano. Actualizadas también las dos
  decisiones que este plan dio la vuelta: las pestañas de estado y el fondo de la fila.
- El formato de fichero **no** sube de versión: `tags`, `dueDate` y `bookmarked` son
  atributos nuevos, se omiten cuando están vacíos y el códec ya conserva los
  desconocidos, así que una versión vieja del plugin abre el fichero sin perder nada.
  Subirlo habría hecho justo lo contrario de lo que toca: abrir el repositorio en solo
  lectura por tres atributos que no rompen nada.

---

## Correcciones posteriores · `0.6.7`

Dos fallos vistos al usarlo, los dos del rediseño:

- **El resalte del ratón se salía de la tarjeta.** La causa no era el hover sino el
  ancho: R4 forzaba cada fila al ancho del viewport «para que las tarjetas no
  quedaran dentadas», y resulta que el árbol **ya** estira el renderer hasta el borde
  visible descontando el margen que él mismo reserva. Pedirle más hacía que
  `DefaultTreeUI` calculara el rectángulo de la selección y el del ratón sobre un
  ancho mayor que el hueco, y asomaran por la derecha. Se quita el ancho forzado, se
  apaga el resalte del árbol (`RenderingUtil.setHoverPaintingDisabled`) y se pinta
  dentro de la tarjeta, con su forma.
- **Quick Add no dejaba pegar imágenes.** Ahora sí, y con una diferencia respecto al
  diálogo grande: la imagen **no se escribe al pegar** sino al crear la tarea. En el
  popup se puede cambiar de repositorio *después* de pegar —el blob habría ido al
  equivocado— y cerrarlo sin crear nada no debe dejar un huérfano esperando al
  recolector. Mientras tanto en el texto hay un marcador legible, `[image 1]`, que se
  borra como cualquier texto: así se descarta una imagen pegada por error.

## Un solo sitio donde crear · `0.6.8`

Dos gestos que no llevaban donde debían —y lo que hubo que mudar para arreglar el
segundo—. Los tres acaban en el mismo sitio: el diálogo de la tarea.

- **El doble clic no abría la tarea**, que era una de las dos condiciones con las que
  se aceptó seguir con `JTree`. La causa no estaba en nuestro código: `CheckboxTree`
  instala su propio `ClickListener` en el constructor —antes que cualquier oyente
  nuestro— y, en cuanto el clic es doble y no cae en la casilla, **consume el evento
  de soltar** para disparar su gancho `onDoubleClick`. `DoubleClickListener` vive
  precisamente de ese evento, así que nunca llegaba a enterarse. Se atiende en
  `mouseClicked`, que es otro evento y sí llega, con la casilla añadida a la lista de
  sitios que el gesto ignora —enlace, marcador y menú ya estaban— para que pinchar dos
  veces en ella marque y desmarque en vez de abrir además el diálogo.
- **Quick Add abre el diálogo, no un popup propio.** Se intentó primero la vía corta
  —enseñarle al popup a pegar capturas, que era lo que le faltaba ese día— y el
  arreglo dejó claro el problema de fondo: cada campo del rediseño había entrado sólo
  en el diálogo, así que el popup se había quedado con la mitad del modelo y con una
  segunda implementación de todo lo que sí compartía. Una tarea apuntada de prisa
  nacía distinta de una escrita con calma. Se borra el popup; se pierden `⌘Enter`
  para encadenar varias, `⌥1…9` y el selector de repositorio, y se gana un solo sitio
  donde crear una tarea. Detalle en `docs/architecture.html` §6.
- **Los triggers de prioridad se mudan al diálogo.** Eran lo único que el popup sabía
  hacer y el diálogo no, así que borrarlo sin más se habría llevado por delante
  `!!! Resolver el fallo`. Actúan **sólo al crear**: en una tarea que ya existe el
  desplegable está a un clic, y un cuerpo que empiece por `!!! ` no tiene por qué
  perderlo sólo por haberlo abierto y aceptado.

## Cerrado

El plan está completo en la `0.6.6`. Lo que queda anotado y **no** es de aquí: los
fallos de imágenes y recolección de basura del repaso de la Fase 6 —abajo—, que
pertenecen a la Fase 7 del plan de ocho.

---

## Fuera de este plan

Del repaso de la Fase 6 quedaron apuntados varios fallos de imágenes y recolección de
basura —adjuntos borrados en un repositorio cargado en modo degradado, IO en el EDT
dentro de `ImageInlayRenderer.paint()`, `MarkdownField` sin `setDisposedWith`—. No son
del rediseño y **están sin verificar uno por uno**: pertenecen a la Fase 7.

Ya corregido de esa lista, por tocarlo este trabajo: `createTask()` capturaba el
repositorio activo dos veces alrededor de un diálogo modal, así que un cambio de
repositorio mientras estaba abierto escribía la imagen en uno y creaba la tarea en otro.
