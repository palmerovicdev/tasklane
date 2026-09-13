# Tasklane

Plugin de IntelliJ Platform para gestionar TODOs por repositorio sin salir del IDE.

- **Arquitectura y decisiones:** [`docs/architecture.html`](docs/architecture.html)
- **Estado:** Fase 2 — estados y prioridades configurables por proyecto, tabs por estado
  y agrupación por fecha.

## Arquitectura en una frase

La UI observa un `StateFlow<TasklaneSnapshot>` inmutable y envía `TaskCommand`s;
`TaskReducer` concentra las invariantes y es Kotlin puro, testeable sin arrancar un IDE;
`TaskFileStore` escribe de forma atómica un XML por repositorio en `.idea/tasklane/`.
La UI nunca toca el almacén.

## Qué se versiona y qué no

| Fichero | Qué hay | VCS |
|---|---|---|
| `.idea/tasklane.xml` | estados y prioridades del proyecto | **sí** — compartible con el equipo |
| `.idea/tasklane/` | las tareas | no — lleva su propio `.gitignore` con `*` |
| `tasklane-defaults.xml` (config del IDE) | plantilla para proyectos nuevos | n/a — es lo único que roamea |

Un proyecto sin `.idea/tasklane.xml` se siembra desde la plantilla al abrirse, así que
configurar por proyecto no obliga a reconfigurar cada proyecto.

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
./gradlew test                             # 51 tests de dominio, config y almacén, sin IDE
./gradlew buildPlugin                      # -> build/distributions/tasklane-0.1.0.zip
./gradlew runIde                           # lanza un IDE sandbox con el plugin
./gradlew verifyPluginProjectConfiguration # chequea targets y sinceBuild
./gradlew verifyPlugin -PlocalIdePath=     # Plugin Verifier (descarga IDEs completos)
```

## Atajos por defecto

| Atajo | Acción | Nota |
|---|---|---|
| `⌘⌥R` | Crear tarea rápida | Choca con *Resume Program* en el keymap de macOS — decisión consciente, reasignable en *Settings → Keymap* |
| `⌘K` | Foco en la búsqueda | Solo dentro de la Tool Window, así que no compite con *Commit* |

## Fases

| | | |
|---|---|---|
| 0 | Andamiaje | ✅ build verde, `tasklane-0.1.0.zip` generado |
| 1 | Dominio, persistencia, CRUD | ✅ |
| 2 | Estados y prioridades | ✅ 51 tests verdes |
| 3 | Multi-repositorio | pendiente |
| 4 | Teclado y búsqueda | pendiente |
| 5 | Enlaces y exportación | pendiente |
| 6 | Imágenes | pendiente |
| 7 | Robustez y pulido | pendiente |
