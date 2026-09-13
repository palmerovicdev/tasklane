# Tasklane

Plugin de IntelliJ Platform para gestionar TODOs por repositorio sin salir del IDE.

- **Arquitectura y decisiones:** [`docs/architecture.html`](docs/architecture.html)
- **Estado:** v0.7.0 — Fase 6 cerrada (imágenes) y **rediseño a tarjetas completo**:
  filas como tarjeta, estados en una fila con su recuento, filtro de vista, adjuntos
  por arrastre, etiquetas como fichas y calendario para el vencimiento. Siguiente, la
  Fase 7: robustez y pulido.

## Arquitectura en una frase

La UI observa un `StateFlow<TasklaneSnapshot>` inmutable y envía `TaskCommand`s;
`TaskReducer` concentra las invariantes y es Kotlin puro, testeable sin arrancar un IDE;
`TaskFileStore` escribe de forma atómica un XML por repositorio en `.idea/tasklane/`.
La UI nunca toca el almacén.

## Qué se versiona y qué no

| Fichero | Qué hay | VCS |
|---|---|---|
| `.idea/tasklane.xml` | estados y prioridades del proyecto | **sí** — compartible con el equipo |
| `.idea/tasklane/` | las tareas, sus imágenes y el registro de repositorios (`layout.xml`) | no — lleva su propio `.gitignore` con `*` |
| `tasklane-defaults.xml` (config del IDE) | plantilla para proyectos nuevos | n/a — es lo único que roamea |

Un proyecto sin `.idea/tasklane.xml` se siembra desde la plantilla al abrirse, así que
configurar por proyecto no obliga a reconfigurar cada proyecto.

## Repositorios

Cada repositorio de la ventana tiene su propio conjunto de tareas. No se escanea el
disco buscando carpetas `.git`: el modelo lo mantiene el IDE y se lee de ahí.

| | |
|---|---|
| Con Git | Los repositorios registrados en el VCS del proyecto, submódulos incluidos |
| Sin Git (o con el plugin desactivado) | Pseudo-repositorio sobre la raíz del proyecto |
| Uno añadido en caliente | Aparece sin reiniciar, por el evento de *mappings* |
| Un solo repositorio | El selector se esconde y el nombre va al título de la ventana |

**Profundidad.** Por defecto sólo cuentan los hijos directos de la raíz
(*Settings → Tools → Tasklane → Repositories*). Es un filtro de vista, no un borrado:
un repositorio más profundo que ya tenga tareas nunca se oculta, aparece bajo «Other».
Lo mismo con una carpeta que desaparece del disco — se marca como ausente, pero se
puede abrir y sus tareas siguen ahí. Esconder datos es indistinguible de perderlos.

**Claves.** El directorio de cada repositorio es
`slug(ruta relativa)-sha256(esa ruta).take(8)` — `backend-api-a3f91d0e` — y la raíz
del proyecto se queda con `root`. Al ser relativa, mover el proyecto entero no cambia
ninguna clave. Renombrar un subrepositorio sí, y sus tareas quedan accesibles bajo la
entrada ausente, de donde salen con «exportar y quitar» (ver más abajo).

## La ventana

Los estados son una fila encima del buscador, cada uno con el recuento de lo que
enseña (`ToDo 2`). El número sale de la misma cuenta que la lista, así que respeta la
búsqueda y el filtro: nunca dirá `3` sobre una lista de dos. Cambiar de estado no
reconstruye nada —cada uno conserva su árbol, su selección y sus grupos plegados— y el
que estaba abierto se recuerda al reabrir el proyecto.

| Control | Qué hace |
|---|---|
| *New Task* (botón partido) | El cuerpo crea en la pestaña activa; la flecha deja elegir otro estado destino sin cambiar de pestaña |
| Filtro de vista | *All tasks* / *Open* / *Overdue* / *Bookmarked*. Es de la ventana entera y se recuerda en `workspace.xml` |
| Marcador y `⋮` | A la derecha de cada fila. Aparecen con el ratón encima —el marcador, siempre que la tarea lo esté— y el menú es el mismo del clic derecho |

Los estados estuvieron en la barra de pestañas del IDE hasta la `0.6.1`; se bajaron al
panel porque en el header competían con el título, el selector de repositorio y el
filtro. El precio es que `Alt+←/→` ya no cambia de estado.

El filtro **no** es una consulta: no se escribe, no tiene sintaxis y borrar la búsqueda
no se lo lleva por delante. Son dos cosas que se acumulan —«vencidas» *y* lo que diga
el campo—, y por eso el desplegable está en la cabecera y no dentro del campo.

### La tarjeta

Cada tarea es una tarjeta de altura variable: crece con lo que tenga que decir y una
tarea de una frase sigue midiendo una línea.

| Parte | Cuándo aparece |
|---|---|
| Franja de color a la izquierda | Siempre — es la prioridad, dentro de la tarjeta y recortada por ella |
| Título | Hasta **tres** líneas; lo que no cabe se recorta. Doble clic abre la tarea entera |
| Descripción | Si hay cuerpo bajo el título. Una línea, recortada |
| Distintivos | Prioridad (si no es la de por defecto), vencimiento, `2/5` de la lista de comprobación, etiquetas, enlaces, imágenes y fecha |

Los enlaces del título se abren con **un clic** desde la fila, sin entrar a editar; el
doble clic sigue siendo «abrir la tarea» en todo lo demás. La lista de comprobación
sale del propio cuerpo (`- [ ]` / `- [x]`), así que escribirla a mano en el editor la
cuenta igual: no hay dos sitios donde pueda decir cosas distintas.

### Agrupar

Cuatro formas, elegidas en el desplegable de la barra y recordadas **por estado**
—*ToDo* puede agrupar por fecha y *Done* por prioridad—:

| | |
|---|---|
| Sin agrupar | Lo marcado arriba, luego por prioridad, y dentro de cada una lo más reciente |
| Por fecha | *Hoy · Ayer · Esta semana · días · meses · Sin fecha*, contra la fecha que el estado ancle (creación, modificación o cierre) |
| Por prioridad | De la más alta a la más baja, y sólo las que tengan algo |
| Por etiqueta | Una tarea con dos etiquetas sale bajo las dos; las que no tienen ninguna, en un grupo al final |

Agrupando por fecha, **«hoy» se enseña aunque esté vacío**: que no haya nada hoy es
justo lo que se viene a mirar. Sólo ése, y sólo si la lista tiene algo más y no hay
búsqueda ni filtro.

### Vencimiento, etiquetas y marcadores

Los tres se editan en el diálogo de la tarea y son campos del modelo, no texto del
cuerpo: no hay forma de teclear «esto vence el viernes» sin inventar una sintaxis.

- **Vencimiento** por preajustes —hoy, mañana, fin de semana, semana que viene— o
  con una fecha concreta del calendario. Vence al **acabar** el día, así que algo
  puesto para hoy no nace vencido. Pasada la fecha, la tarjeta lo pinta en rojo.
- **Etiquetas** como fichas: se escriben separadas por coma o espacio y se quitan con
  su aspa. En el buscador son `#api`.
- **Marcador**: sube la tarea al principio de su grupo pase lo que pase, porque
  marcar es precisamente decir «que no se me pierda esto». Se pulsa en la propia fila.

## Quick Add

`⌘⌥R` abre **el mismo diálogo** que *New Task*, desde cualquier sitio del IDE y sin
pasar por la Tool Window. Hasta la `0.6.7` abría un popup compacto propio: se escribía
más rápido, pero tenía la mitad de los campos —sin vencimiento, sin etiquetas, sin
barra de formato ni vista previa de las imágenes—, así que una tarea apuntada de prisa
nacía distinta de una escrita con calma. La tarea se crea en el **repositorio activo**,
el mismo que usa *New Task*.

**Triggers.** Escribir `!!! Resolver el fallo` crea la tarea *Resolver el fallo* con
prioridad *High*: el prefijo mueve el desplegable de prioridad mientras se escribe y
**no se guarda**. Gana la coincidencia más larga y hace falta un espacio detrás, así
que `!importante revisar` no dispara nada; borrarlo devuelve la prioridad anterior y
elegirla a mano gana sobre el prefijo. Funcionan **sólo al crear**: sobre una tarea que
ya existe el desplegable está a un clic, y un cuerpo que empiece por `!!! ` no tiene
por qué perderlo sólo por haberlo abierto. Los prefijos se configuran por prioridad y
se apagan enteros desde *Settings → Tools → Tasklane*.

## Búsqueda

`⌘K` lleva el foco al campo, sobre el árbol. Se busca en el cuerpo entero, no sólo en
la fila, ignorando mayúsculas y acentos en los dos sentidos: *autenticación* encuentra
*autenticacion* y al revés.

| Operador | Ejemplo |
|---|---|
| Texto libre | `token refresco` — se piden todos los términos |
| `state:` | `state:doing`, `state:"In Review"` |
| `p:` | `p:high` |
| `repo:` | `repo:backend` |
| `is:` | `is:done`, `is:open` |
| `has:` | `has:link`, `has:image` |
| `#tag` | `#api #urgente` — se piden todas las etiquetas |

Los valores son prefijos. Varios valores del mismo operador son un «o»; operadores
distintos se acumulan. Un operador a medio escribir (`state:`) se ignora en vez de
vaciar la lista, y un prefijo desconocido (`https:`) se busca como texto.

**Alcance.** Sólo el repositorio activo, salvo que se active *Search All Repositories*
en la barra — que sólo aparece cuando hay más de uno. Las filas de otro repositorio se
etiquetan con su nombre.

No hay índice invertido: 5.000 tareas normalizadas y cacheadas se recorren en un par
de milisegundos, fuera del EDT y detrás de un debounce de 120 ms. La interfaz
`TaskSearchIndex` existe para que esa decisión sea reversible sin tocar la UI.

## Enlaces

Las URLs del cuerpo se extraen **al escribir** y se guardan derivadas en la tarea, así
que pintar una fila nunca ejecuta una expresión regular. Se reconocen las URLs
sueltas, las que empiezan por `www.` y los enlaces Markdown `[texto](url)`.

| | |
|---|---|
| En el título | El enlace se pinta con el estilo de enlace del IDE y se abre con un clic |
| En el detalle | El icono de cadena de la segunda línea, con el número; con varios enlaces sale una lista |
| Acortado | `https://youtrack.jetbrains.com/issue/ABC-123` → `youtrack.jetbrains.com/…/ABC-123` |
| Tooltip | La URL completa. Lo que se abre es siempre la original, nunca la acortada |

Sólo `http` y `https`. Un `file:` o un `javascript:` pegado en una tarea no es
clicable: lo que se pinta como enlace es lo que un clic va a ejecutar. Las URLs
dentro de un bloque de código o entre comillas invertidas son ejemplos, no destinos,
y no se extraen. La puntuación de la frase tampoco entra — `(ver https://ej.com/a)` no se
lleva el paréntesis, pero `…/Ada_(lenguaje)` sí conserva el suyo.

Nunca hace falta entrar a editar para abrir un enlace, esté donde esté en el texto.

## Imágenes

En el diálogo de una tarea se pega una captura con el atajo de pegar de siempre y
aparece ahí mismo, debajo de la línea donde estaba el cursor. `⌘Z` la deshace como
cualquier otra edición.

| | |
|---|---|
| Qué se pega | Una imagen del portapapeles —una captura de pantalla— o un fichero de imagen copiado del explorador |
| Qué se guarda | Un PNG por imagen, reescalado al máximo de los ajustes (1600 px por defecto), en `.idea/tasklane/repos/<repo>/attachments/` |
| Qué se ve en el texto | `[image]`; la referencia larga queda plegada detrás |
| Ampliar | Un clic sobre la vista previa la abre a tamaño de pantalla |
| Quitar | Se selecciona el `[image]` y `Supr`. Al irse la referencia se va la imagen |
| Buscar | `has:image` filtra las tareas que llevan alguna |

**El nombre del fichero es el SHA-256 de su contenido.** De ahí salen tres cosas
gratis: la misma captura pegada en dos tareas ocupa un fichero y no dos, no hay
nombres que colisionen, y saber qué sobra es contar referencias.

**Lo que ya no referencia ninguna tarea se borra al abrir el proyecto**, con 24 horas
de gracia — lo justo para que deshacer un pegado, o cancelar el diálogo, no deje la
imagen a medio camino. Sólo se miran los repositorios cuyas tareas están leídas: en
uno sin leer, «no veo referencias» significa «no lo sé», no «no hay ninguna».

Un adjunto que falta no borra su referencia: el editor pinta un marcador en su sitio.
Y las referencias no salen al exportar — fuera del IDE, un SHA de 64 caracteres no es
una imagen ni un enlace.

## Exportación

El botón de copiar de la barra —y el menú contextual— llevan lo que se está viendo al
portapapeles. Con una búsqueda activa se exporta el resultado de la búsqueda: es lo
único que se puede revisar antes de pegarlo.

| Alcance | Qué copia |
|---|---|
| Este estado | La pestaña entera, un bloque por grupo de fecha |
| Este grupo | Sólo el grupo donde está la selección |
| Selección | Sólo las filas seleccionadas |

El formato se alterna en el mismo menú y se recuerda por proyecto:

```markdown
## Done · Today

- [x] Arreglar el login
- [x] Revisar el PR de facturación
  hay que avisar a soporte
```

Sin Markdown quedan guiones pelados, que es lo que se pega en un correo o en un chat.
El detalle de la tarea va sangrado bajo su línea y las etiquetas detrás del título.

**Exportar y quitar.** Un repositorio marcado como ausente —su carpeta ya no está en
disco— es el único que se puede quitar de la lista, y sólo por esta vía: primero sus
tareas van al portapapeles, se confirma, y después se borra la carpeta de datos. Es
la salida que hace honesto el trato con los repositorios ausentes, que nunca ocultan
ni borran nada por su cuenta.

## Requisitos

| | |
|---|---|
| IDE mínimo | 2025.2 (`sinceBuild = 252`), sin cota superior |
| Bytecode | JVM 21 — lo que ejecuta la plataforma 2025.2 |
| Toolchain | JBR de la IDEA instalada (`org.gradle.java.installations.paths`) |
| Kotlin | 2.4.20 |
| Gradle | 9.5.0 vía wrapper |

## Cómo se resuelve la plataforma

`localIdePath` en `gradle.properties` decide contra qué se compila:

| | |
|---|---|
| **Con valor** (por defecto) | Compila contra el IDE ya instalado. **Descarga cero.** |
| **Vacío** (`-PlocalIdePath=`) | Descarga `platformVersion`, la versión mínima soportada. Es lo que hace CI. |

Compilar contra un IDE local más nuevo que `sinceBuild` tiene un coste: el compilador
no puede garantizar el suelo. `verifyPluginProjectConfiguration` lo avisa con dos
mensajes que en local son **esperados**:

- `since-build 252 < plataforma 262`
- `sourceCompatibility 21, la plataforma pide 25` — no se sube a 25 a propósito:
  generaría bytecode que no arranca en 2025.2.

CI compila con `-PlocalIdePath=` contra la 2025.2 real, y ahí sí se detecta
cualquier uso accidental de una API posterior.

> **Nota sobre la versión de Kotlin.** Debe ser >= la que usa el IDE contra el que
> se compila, porque el compilador tiene que poder *leer* sus metadatos. IDEA 2026.2
> trae metadatos 2.4.0 y Kotlin 2.1.x falla con
> `Module was compiled with an incompatible version of Kotlin`. Al revés no hay
> problema: 2.4.20 lee también los metadatos de 2025.2.

## Comandos

```bash
./gradlew test                             # 265 tests de dominio, búsqueda, almacén y renderer, sin IDE
./gradlew buildPlugin                      # -> build/distributions/tasklane-0.7.0.zip
./gradlew runIde                           # lanza un IDE sandbox con el plugin
./gradlew verifyPluginProjectConfiguration # chequea targets y sinceBuild
./gradlew verifyPlugin -PlocalIdePath=     # Plugin Verifier (descarga IDEs completos)
```

## Atajos por defecto

| Atajo | Acción | Nota |
|---|---|---|
| `⌘⌥R` | Quick Add | Global. Choca con *Resume Program* en el keymap de macOS — decisión consciente, reasignable en *Settings → Keymap* |
| `⌘K` | Foco en la búsqueda | Sólo dentro de la Tool Window, así que no compite con *Commit* |
| `Enter` | Editar la tarea seleccionada | Dentro del árbol |
| `Supr` | Borrar las seleccionadas | Dentro del árbol |

Todas las acciones están declaradas en `plugin.xml`, así que aparecen en *Settings →
Keymap* y en *Search Everywhere* aunque no traigan atajo por defecto. Las de la Tool
Window leen el keymap primero y sólo caen al valor local si no tienen asignación.

## Fases y versiones

**Una fase cerrada sube la versión media:** la fase N deja el plugin en `0.N.0`, y la
`1.0.0` queda para cuando estén las ocho. Versión y fase son el mismo número, así que
`pluginVersion` dice por sí solo hasta dónde llega el plugin instalado. La versión baja
(*patch*) es para lo que no mueve el plan de ocho fases: correcciones, compatibilidad,
textos y **cada iteración del rediseño a tarjetas**, que así deja siempre un plugin
instalable en vez de esperar a que el plan entero esté cerrado. La `0.7.0` es la única
excepción a la regla y está anotada debajo: salió con parte de su fase hecha.

| | | | |
|---|---|---|---|
| 0 | Andamiaje | — | ✅ salió junto con la Fase 1 |
| 1 | Dominio, persistencia, CRUD | `0.1.0` | ✅ |
| 2 | Estados y prioridades | `0.2.0` | ✅ |
| 3 | Multi-repositorio | `0.3.0` | ✅ |
| 4 | Teclado y búsqueda | `0.4.0` | ✅ |
| 5 | Enlaces y exportación | `0.5.0` | ✅ |
| 6 | Imágenes | `0.6.0` | ✅ |
| 7 | Robustez y pulido | `0.7.0` | 🔄 **en curso** — entregado el pulido de uso; el criterio sigue sin pasar |

La `0.7.0` **abre** la Fase 7, no la cierra: sale con el pulido de uso —los iconos
de la barra de formato y `Escape` en el diálogo, detallados en el plan del rediseño—
porque son cosas que se notan a diario y no tenía sentido guardarlas. Lo que queda de
la fase es lo de fondo: migraciones y modo solo lectura por versión, recuperación de
ficheros corruptos, accesibilidad, tests de integración y `verifyPlugin` contra
2025.2 → 2026.2. La fase se dará por cerrada cuando pase su criterio —corromper
`tasks.xml` a mano y ver que el plugin recupera del `.bak` y avisa—, y es la única
versión media que sale antes de tiempo: las seis anteriores sí cerraron su fase.

En paralelo al plan de ocho fases fue el **rediseño a tarjetas**, con su propia
numeración y su propio plan: [`docs/plan-rediseno.md`](docs/plan-rediseno.md). Está
**cerrado** en la `0.6.6`; cada iteración subió la versión baja y dejó un plugin
instalable.

| | | | |
|---|---|---|---|
| R1 | Filas | — | ✅ salió dentro de la `0.6.0` |
| R2 | Diálogo | — | ✅ salió dentro de la `0.6.0` |
| R3 | Cabecera y barra | `0.6.1` | ✅ |
| R3.1 | Las pestañas bajan al panel | `0.6.2` | ✅ |
| R4 | Aspecto de tarjeta | `0.6.3` | ✅ |
| R5 | Adjuntos en el diálogo | `0.6.4` | ✅ |
| R6 | Deudas conscientes | `0.6.5` | ✅ |
| R7 | Documentación | `0.6.6` | ✅ |
| — | Correcciones de uso | `0.6.7` | resalte del ratón y pegar imágenes en Quick Add |
| — | Un solo sitio donde crear | `0.6.8` | el doble clic vuelve a abrir la tarea y Quick Add pasa a ser el diálogo |

El **formato de fichero no sube de versión** con el rediseño: `tags`, `dueDate` y
`bookmarked` son atributos nuevos, se omiten cuando están vacíos y el códec conserva
los desconocidos, así que una versión vieja del plugin abre el fichero sin perder nada.
