# Preparación de Tasklane 2.33.0

**Estado: validación en curso; aún no publicar.** Esta preparación no ejecuta ninguna subida
ni modifica credenciales de Marketplace.

## Contenido

- Atajo global opcional y desactivado por defecto; ajustes propios de combinación y proyecto.
- Integraciones Windows/macOS/X11 y portal Wayland compatible, con estado de disponibilidad.
- Versión `2.33.0` sincronizada entre Gradle, changelog y notas del descriptor generado.
- Manual, guía del atajo, descripción de Marketplace y documentación de desarrollo actualizados.
- Sin migración del formato de tareas. La configuración global es local y no se sincroniza.

## Comprobaciones

- Pruebas previas de la funcionalidad: 34 aprobadas.
- Suite previa: 1.108 pruebas, 1.087 aprobadas, 21 omitidas, sin fallos ni errores.
- Build anterior: comprobación local, no sustituye el build de release de esta versión.
- Build de release, opciones de búsqueda y Plugin Verifier: en curso.
- Verificación independiente de concurrencia y recursos nativos: en curso.
- Pruebas reales con el IDE en segundo plano en Windows, macOS, X11 y Wayland: pendientes.

## Artefacto previsto

`build/distributions/tasklane-2.33.0.zip`. Se registrarán aquí el hash y los resultados
observados cuando terminen las validaciones. No se considera válida una comprobación de
un ZIP anterior o construido antes de una corrección.

## Límites de publicación

La firma, si se utiliza, requiere las credenciales existentes del autor. No se generan ni
buscan claves privadas durante esta preparación. La aprobación final de Marketplace y la
subida del paquete son acciones separadas.
