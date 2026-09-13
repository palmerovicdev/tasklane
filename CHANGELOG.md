# Changelog

Todas las versiones publicables del plugin. El formato sigue
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y el versionado, semver:
hasta la `1.0.0` la versión media era **la fase cerrada** (fase N → `0.N.0`); a partir
de ahí manda semver sobre lo publicado.

> Al subir la versión hay que tocar tres sitios: `pluginVersion` en `gradle.properties`,
> `changeNotes` en `build.gradle.kts` —que es lo que sale en la ficha del Marketplace y
> en el diálogo de actualización del IDE— y este fichero.

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
