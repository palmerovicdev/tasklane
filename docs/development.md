# Desarrollo de Tasklane

Compilar, probar, versionar y publicar. Qué hace el plugin está en el
[manual](manual.md); la portada, en el [README](../README.md).

## Requisitos

| | |
|---|---|
| IDE mínimo | 2026.1.5 (`sinceBuild = 261.27258.48`), sin cota superior |
| Bytecode | JVM 21 — lo que ejecuta la plataforma 2026.1 |
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

- `since-build 261.27258.48 < plataforma 262`
- `sourceCompatibility 21, la plataforma pide 25` — no se sube a 25 a propósito:
  generaría bytecode que no arranca en 2026.1, que todavía corre sobre Java 21.

CI compila con `-PlocalIdePath=` contra la 2026.1.5 real —`IU`: desde la 2025.3 no hay
Community que descargar—, y ahí sí se detecta cualquier uso accidental de una API posterior.

> **Nota sobre la versión de Kotlin.** Debe ser >= la que usa el IDE contra el que
> se compila, porque el compilador tiene que poder *leer* sus metadatos. IDEA 2026.2
> trae metadatos 2.4.0 y Kotlin 2.1.x falla con
> `Module was compiled with an incompatible version of Kotlin`. Al revés no hay
> problema: 2.4.20 lee también los metadatos 2.3 de 2026.1.

## Comandos

```bash
./gradlew test                             # tests de dominio, búsqueda, almacén y renderer, sin IDE
./gradlew buildPlugin                      # -> build/distributions/tasklane-2.29.0.zip
./gradlew runIde                           # lanza un IDE sandbox con el plugin
./gradlew verifyPluginProjectConfiguration # chequea targets y sinceBuild
./gradlew verifyPlugin -PlocalIdePath=     # Plugin Verifier (descarga IDEs completos)

# Endurecimiento (Fase 6)
./gradlew test --tests '*CrashTest' -PcrashRounds=50            # matar el proceso 50 veces por escenario
./gradlew test --tests '*ScaleBenchmark' -PbenchN=100000 -PtestHeap=4g   # el banco, con techos que rompen el build
```

Cada noche, [`nightly.yml`](../.github/workflows/nightly.yml) lanza el banco sobre **un millón
de tareas** con el heap de fábrica del IDE —2 GB— y las pruebas de caída con treinta muertes
por escenario en los tres sistemas. Los techos son los presupuestos de cada fase —16 ms en el
EDT, 50 ms para editar, 150 MB de heap— y no cifras de un portátil, así que un runner más
lento no los rompe sin que algo haya empeorado. Deja las cifras de cada noche en un CSV.

## Fases y versiones

**Una fase cerrada subía la versión media:** la fase N dejaba el plugin en `0.N.0`, y
la `1.0.0` quedaba para cuando estuvieran las ocho. Ya están: la `1.0.0` cierra la
Fase 7 y con ella el plan.

De aquí en adelante, cada número de la versión `X.Y.Z` dice qué trae:

| Número | Sube con | Ejemplo |
|---|---|---|
| **Primero** (`X`) | Una funcionalidad **grande** | `2.0.0`: las tareas pasan a una base SQLite por proyecto |
| **Segundo** (`Y`) | Una funcionalidad **pequeña** | `2.8.0`: un ancla de código se puede escribir en el diálogo |
| **Tercero** (`Z`) | Una **corrección de fallos**, sin nada nuevo | `2.6.3`: el desplazamiento y el alto de las tarjetas |

Al subir un número, los de su derecha vuelven a cero.

| | | | |
|---|---|---|---|
| 0 | Andamiaje | — | ✅ salió junto con la Fase 1 |
| 1 | Dominio, persistencia, CRUD | `0.1.0` | ✅ |
| 2 | Estados y prioridades | `0.2.0` | ✅ |
| 3 | Multi-repositorio | `0.3.0` | ✅ |
| 4 | Teclado y búsqueda | `0.4.0` | ✅ |
| 5 | Enlaces y exportación | `0.5.0` | ✅ |
| 6 | Imágenes | `0.6.0` | ✅ |
| 7 | Robustez y pulido | `0.7.0` · `0.7.1` · `1.0.0` | ✅ |

La Fase 7 salió en tres tandas, y es la única que no cupo en una versión. La `0.7.0`
adelantó el pulido de uso; la `0.7.1`, el Markdown de la fila y el ratón sobre la
tarjeta entera; la `1.0.0` cerró lo de fondo —idioma de los avisos, accesibilidad y
navegación por teclado, y el criterio de la fase, que es corromper `tasks.xml` a mano y
comprobar que el plugin recupera del `.bak` **y avisa**—. Lo detalla
[`CHANGELOG.md`](../CHANGELOG.md).

Lo posterior al plan ya no son fases sino versiones. La `1.1.0` añade las **anclas de
código** y *Move To*, y arregla que las cabeceras de grupo no se plegaran. La `1.2.0`
cierra el viaje de vuelta: el código **enseña sus tareas** en el editor. La `1.3.0`
vuelve sobre la tarjeta: su texto se **selecciona y se copia**, un botón la **despliega**
para leerla entera —con sus **capturas** dentro—, y la **prioridad se cambia** desde su
distintivo. La `1.4.0` la deja **entera y en su sitio** con la ventana estrecha: el
distintivo de prioridad en todas las tarjetas, la fila midiendo exactamente lo que se ve
—ni los distintivos se caen por abajo ni los botones dejan de caer donde se ven— y un
suelo de 300 px de ancho para la ventana. La `1.4.1` vuelve sobre la marca del editor y
sobre lo que sale de ella: el tooltip de la pastilla enseña las **capturas** de la tarea,
la pastilla deja **aire** a los lados para que el código no entre y salga de ella, y un
grupo de fecha se copia como el **parte de su día** —la fecha en ISO y una lista
numerada—.

De la `1.5.0` en adelante el trabajo lo marca otro plan,
[`docs/plan-escala.md`](plan-escala.md): que un repositorio de **un millón de
tareas** se abra, se busque y se recorra igual que el primer día. Empieza con un banco
de pruebas —porque nada se optimiza sin medirlo— y va fase a fase, cada una con su
puerta y su versión.

| | | | |
|---|---|---|---|
| E0 | Banco de pruebas e instrumentación | `1.5.0` | ✅ |
| E1 | Quitar los O(n) por comando | `1.5.0` | ✅ |
| E2 | UI acotada | `1.6.0` | ✅ |
| E3 | El almacén (SQLite + FTS5) | `2.0.0` | ✅ |
| E4 | Adjuntos a escala | `2.1.0` | ✅ |
| E5 | Las operaciones grandes | `2.2.0` | ✅ |
| E6 | Endurecimiento | `2.4.0` | ✅ |

Las dos primeras salieron juntas en la `1.5.0`: la acción *Tasklane: Diagnostics*, que
convierte «va lento» en una cifra, y el recorte del trabajo por comando —editar una
tarea sobre 100.000 pasó de 26 ms a 6 ms—. La `1.6.0` cierra la **Fase 2**: la lista se
carga **a páginas** y el árbol se repinta **por diferencias**, así que abrir la ventana
sobre un millón de tareas pasa de diez segundos con el IDE congelado a menos de un
milisegundo.

La `2.0.0` cierra la **Fase 3**, que es la grande: las tareas dejan el `tasks.xml` que se
leía entero al abrir y se reescribía entero al guardar, y se mudan a una base **SQLite**
—la que ya trae el propio IDE, sin empaquetar un byte— con índices, paginación y
búsqueda de texto completo. Con 100.000 tareas, abrir el proyecto y pintar la lista pasa
de **3.027 ms a 9,2 ms**, guardar de un volcado de **1.168 ms a una transacción de
0,44 ms**, y el plugin pasa de ocupar **1,29 GB de memoria a 2,5 MB**. Es una versión
mayor porque cambia el formato: la migración es automática y en segundo plano, y el
`tasks.xml` se conserva al lado como `tasks.xml.migrated` — quitarle el sufijo es la
vuelta atrás.

La `2.1.0` cierra la **Fase 4**, que es lo que quedaba por debajo: las capturas. El
directorio de adjuntos se fragmenta en `ab/cd/`, lo que hay se contabiliza en la base
—recoger la basura deja de listar el disco— y la lista pasa a pintar **miniaturas**, así
que una tarjeta con diez capturas grandes baja de 134 MB a 36 MB
de memoria. Las capturas nuevas se guardan a 400 px en vez de 1600 —de 407 KB a 33 KB cada
una— y **las que ya estaban no se tocan**: el nombre de un blob es el hash de su
contenido, así que reescalarlo sería reescribir las tareas que lo nombran. Lo que no se
puede resolver con ingeniería —que diez millones de capturas ocupan lo que ocupan— se
resuelve avisando: hay una cuota con aviso, configurable, que no borra nada.

La `2.2.0` cierra la **Fase 5**: lo que es O(n) por definición. Exportar va en
*streaming* y sobre una foto de la base —con 100.000 tareas, sacar el repositorio a
`tasks.xml` retenía 1,5 GB y ahora 15 MB—, el portapapeles tiene tope y por encima se
ofrece un fichero, las operaciones sobre muchas filas son una transacción y un repintado,
y «Exportar y quitar» comprueba que salió todo antes de borrar y borra por tandas, así que
nadie espera más de un cuarto de segundo. El `.bak` de cada guardado tiene por fin
sustituto: una copia diaria con `VACUUM INTO` y una comprobación de la base tras cada
cierre inesperado. De paso aparecieron cinco fallos de la Fase 3 en los caminos que esta
recorría —el más serio, que un repositorio renombrado desaparecía del selector con sus
tareas dentro— y están corregidos.

La `2.3.0` no es del plan: es una iteración pedida de una vez sobre el uso diario.
Agrupar por fecha pasa a ser **un grupo por día**; un repositorio se puede **guardar
entero** en CSV, Markdown o texto y, por separado, **vaciar**; los enlaces del cuerpo de
una tarjeta se pulsan donde están; el ancla de código y la prioridad se quedan en su icono
en vez de caerse de una tarjeta estrecha; y en los ajustes las prioridades van de la más
alta a la más baja, las filas se arrastran y cambiar un color por fin cambia el color.

La `2.4.0` cierra la **Fase 6**, y con ella el plan de escala: comprobar a la fuerza lo que
las anteriores daban por hecho. Se mata el proceso a mitad de escribir —de una edición, de la
migración, de la limpieza de imágenes, de la copia y de la propia reparación— y la base queda
sana siempre; una base dañada **se repara sola** rescatando todo lo que todavía se lee y
pidiendo a la copia sólo lo que no; cien mil comandos seguidos no mueven ni el heap ni la
latencia; y cada noche la CI mide un millón de tareas con techos que rompen el build. SQLite
pasa a ir empaquetado, porque el del IDE resultó ser API interna, sobre el mismo fichero.

En paralelo al plan de ocho fases fue el **rediseño a tarjetas**, con su propia
numeración y su propio plan: [`docs/plan-rediseno.md`](plan-rediseno.md). Está
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

## Publicar

Publicar es un acto deliberado: lo dispara **una etiqueta `vX.Y.Z`**, no un push a `main`.

**Antes de etiquetar**, tres sitios y en este orden:

1. `pluginVersion` en `gradle.properties`
2. `changeNotes` en `build.gradle.kts` — es lo que sale en la ficha del Marketplace y
   en el diálogo de actualización del IDE
3. [`CHANGELOG.md`](../CHANGELOG.md), con su sección `## [X.Y.Z]`: es el texto de la
   release de GitHub

Y la `<description>` de `plugin.xml`, que nadie toca al subir versión: contrastarla con el
CHANGELOG antes de publicar.

**Con el label `release` en un PR** —la forma normal—: se suben esos tres sitios en el PR,
se le pone el label y, **al mergearlo**,
[`release-on-label.yml`](../.github/workflows/release-on-label.yml) comprueba que
`vX.Y.Z` no existe todavía y que el CHANGELOG tiene su sección, crea la etiqueta sobre el
commit del merge, lanza `release.yml` sobre ella y lo dice en un comentario del PR. Poner el
label a un PR ya mergeado también vale; ponérselo a uno abierto no hace nada hasta mergearlo.
`release.yml` se lanza a mano (`workflow_dispatch`) porque una etiqueta empujada con el
`GITHUB_TOKEN` no dispara otros workflows.

**A mano**, con todo ya en `main`:

```bash
git tag vX.Y.Z && git push origin vX.Y.Z
```

Las dos acaban en [`release.yml`](../.github/workflows/release.yml), que comprueba que la
etiqueta y `pluginVersion` dicen lo mismo, pasa los tests y el Plugin Verifier contra
2026.1.5 y 2026.2, construye el zip y crea la **release de GitHub** con la sección del
CHANGELOG y el zip colgado. Si están los secretos de firma, cuelga el zip firmado; si además
está `PUBLISH_TOKEN`, lo sube al Marketplace. Sin ellos lo avisa y la release sale igual, con
el zip sin firmar, que es el que se sube a mano en la web del Marketplace.

**Secretos del repositorio.** Los cuatro van como *secrets* de GitHub Actions y no
tocan el repositorio:

| | |
|---|---|
| `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` | Firma del plugin. Se generan una vez siguiendo [*Plugin Signing*](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html) |
| `PUBLISH_TOKEN` | Token del perfil del Marketplace |

**Una versión con sufijo va a su propio canal:** `1.4.1-beta.1` se publica en `beta`,
no en el estable, y sólo la ve quien haya añadido ese canal en el IDE. El canal sale
del propio número de versión, así que no hay un segundo sitio que pueda discrepar.

**Publica el repositorio antes que el plugin.** Las capturas de la ficha se sirven por
URL absoluta desde `main` —la descripción del Marketplace no resuelve rutas relativas—,
así que si el repositorio no está publicado la ficha sale con las imágenes rotas. Las
de la ficha están en [`docs/screenshots/`](screenshots). **No se renombran ni se borran**:
la ficha de cada versión ya publicada sigue apuntando a ellas en `main` (en la 2.29.0
`export-menu.png` pasó a `3export-menu.png` y la ficha se quedó sin esa imagen).

Las diapositivas de [`docs/marketplace/`](marketplace) son las del README y las del
**carrusel**, que se suben desde el panel del Marketplace: es lo que se ve en los
resultados de búsqueda, donde la descripción todavía no se ha abierto.
