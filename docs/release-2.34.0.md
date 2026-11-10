# Preparación de Tasklane 2.34.0

**Estado: preparación parcial; aún no publicar.** El paquete existe, pero faltan las
comprobaciones indicadas abajo. Esta preparación no publica ni firma el plugin.

## Contenido

- Sugerencias de etiquetas existentes al escribir `#` en el texto del diálogo de tareas.
- Al elegir una sugerencia, se añade como ficha; no cambia el formato de las tareas.
- Versión y notas sincronizadas en `gradle.properties`, `CHANGELOG.md` y el descriptor.

## Comprobaciones observadas

- `test verifyPluginProjectConfiguration buildPlugin -PlocalIdePath= --offline --no-configuration-cache`: correcto.
- Suite: 1.113 pruebas, 0 fallos, 0 errores y 21 omitidas.
- ZIP generado contra la plataforma mínima 2026.1.5, con el índice de opciones de búsqueda;
  el descriptor declara `2.34.0` y `since-build 261.27258.48`.
- Revisión independiente de la lógica del lookup: detectó un problema con la puntuación;
  la corrección se revisó de nuevo. Esto no sustituye una prueba de la interfaz.

## Pendiente antes de publicar

- Ejecutar `verifyPlugin` contra los IDE configurados: la 2026.2 no está en la caché local.
  Un intento independiente con la 2026.1.5 quedó incompleto al no poder resolver metadatos
  y dependencias por red; no se ha obtenido un resultado de compatibilidad del Verifier.
- Probar en el IDE el popup, la selección con ratón y teclado, Escape, los separadores y
  la escritura de Markdown y código.
- Revisar la versión y los cambios finales antes del flujo habitual de publicación. La firma
  y la subida a Marketplace siguen siendo acciones separadas.

## Artefacto

- `build/distributions/tasklane-2.34.0.zip`
- SHA-256: `14961f0fad04dd435afff2b04e06881e368478fdaf12115857397b0fe9b30401`
