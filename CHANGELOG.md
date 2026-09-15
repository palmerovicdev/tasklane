# Changelog

Todas las versiones publicables del plugin. El formato sigue
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y el versionado, semver:
hasta la `1.0.0` la versión media era **la fase cerrada** (fase N → `0.N.0`); a partir
de ahí manda semver sobre lo publicado.

> Al subir la versión hay que tocar tres sitios: `pluginVersion` en `gradle.properties`,
> `changeNotes` en `build.gradle.kts` —que es lo que sale en la ficha del Marketplace y
> en el diálogo de actualización del IDE— y este fichero.

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
