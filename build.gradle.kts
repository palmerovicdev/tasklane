import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import kotlin.text.isNotBlank

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// El toolchain apunta al JBR que ya trae la IDEA instalada (ver
// org.gradle.java.installations.paths en gradle.properties), asi no hay que
// descargar ningun JDK. El bytecode se emite para 21, que es lo que ejecuta
// la plataforma 2025.2 -> el minimo que soportamos.
kotlin {
    jvmToolchain(25)
    compilerOptions {
        // 21 y no 25: es lo que ejecuta la plataforma 2025.2, nuestro minimo.
        // Bytecode 21 corre sin problema sobre el JBR 25 de un IDE 2026.x.
        jvmTarget = JvmTarget.JVM_21
    }
}

// NOTA sobre la version de Kotlin (settings.gradle.kts): debe ser >= la que usa
// el IDE contra el que se compila, porque el compilador tiene que poder LEER sus
// metadatos. IDEA 2026.2 trae metadatos 2.4.0, asi que 2.1.x fallaba con
// "Module was compiled with an incompatible version of Kotlin".
//
// Al reves no hay problema: un compilador nuevo lee metadatos viejos, asi que el
// mismo 2.4.20 sirve para el build de CI contra 2025.2.
//
// El stdlib NO se empaqueta (kotlin.stdlib.default.dependency=false), viene del
// IDE. Por eso el build de CI contra 2025.2 es el que detecta si usamos alguna
// API de stdlib mas nueva que la que trae el IDE minimo soportado.

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

// null -> no hay IDE local configurado: camino de CI y de release, que descarga
// platformVersion. No-null -> dev loop local contra el IDE ya instalado.
val localIde: String? = providers.gradleProperty("localIdePath").orNull?.takeIf(String::isNotBlank)

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        // Dependencia OPCIONAL en plugin.xml pero obligatoria para compilar: el
        // proveedor de repositorios Git necesita git4idea en el classpath, aunque
        // en runtime su descriptor no se cargue si el plugin Git esta desactivado.
        bundledPlugin("Git4Idea")
        // Git4Idea expone tipos de dvcs en su API (GitRepository : Repository), y
        // esos modulos de la plataforma no entran solos en el classpath.
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")

        if (localIde != null) {
            // Descarga cero: se compila contra el IDE ya instalado.
            local(localIde)
        } else {
            // Camino de CI y de release: se descarga la version MINIMA soportada,
            // para que el compilador rechace APIs posteriores a sinceBuild.
            create(
                providers.gradleProperty("platformType"),
                providers.gradleProperty("platformVersion"),
            )
        }
    }
}

intellijPlatform {
    // Indexa las etiquetas del Configurable para que aparezcan en la busqueda de
    // Settings. Para hacerlo arranca un IDE headless, y contra el IDE local eso
    // comparte el sandbox con la instancia de `runIde`: el segundo proceso muere
    // con "Only one instance of IDEA can be run at a time".
    //
    // Se desactiva solo en el dev loop. En CI y en release (localIdePath vacio ->
    // plataforma descargada, sandbox propio) si se ejecuta, que es donde importa:
    // es el zip que se publica.
    //
    // Va aqui y no como `tasks.buildSearchableOptions { enabled = false }`: apagar
    // la tarea deja a `prepareJarSearchableOptions` esperando un directorio que ya
    // nadie crea, y `clean buildPlugin` falla. El interruptor de la extension si
    // salta la cadena entera.
    buildSearchableOptions = localIde == null

    pluginConfiguration {
        id = providers.gradleProperty("pluginId")
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Sin cota superior: sin esto IPGP fijaria until-build a la rama de
            // compilacion y el plugin dejaria de instalarse en versiones nuevas.
            untilBuild = provider { null }
        }

        // Las notas de la version, tal y como salen en la ficha del Marketplace y en
        // el dialogo de actualizacion del IDE. Se actualizan A MANO en cada release,
        // junto con pluginVersion; el historial largo vive en CHANGELOG.md.
        changeNotes = provider {
            """
            <h3>1.0.0 &mdash; the eight phases are done</h3>
            <ul>
              <li>Task cards now render the Markdown of the body instead of showing the
                  markers: bold, italics, inline code and strikethrough.</li>
              <li>The whole card reacts to the mouse, not only the part with text.</li>
              <li>Group headers show a chevron and fold with a click; the "Group by"
                  drop-down now keeps its tick on the grouping actually in use.</li>
              <li>Keyboard navigation reaches the state tabs, and the tree, the search
                  field and the dialog fields report proper names to screen readers.</li>
              <li>An unreadable task file is quarantined, recovered from its backup and
                  reported; a file written by a newer version opens read-only.</li>
            </ul>
            """.trimIndent()
        }
    }

    // Firma y publicacion. Todo por variables de entorno: aqui no entra ni un secreto.
    // CERTIFICATE_CHAIN / PRIVATE_KEY / PRIVATE_KEY_PASSWORD los da el generador de
    // JetBrains; PUBLISH_TOKEN sale del perfil del Marketplace.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Una version con sufijo (1.0.0-beta.1) sale al canal con ese nombre en vez
        // de al estable, que es como el Marketplace distingue las preliminares.
        channels = providers.gradleProperty("pluginVersion").map { version ->
            listOf(version.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    // Solo se resuelve al ejecutar `verifyPlugin`, que descarga IDEs completos.
    // Reservado para CI; en local no se invoca.
    //
    // Los dos extremos van EXPLICITOS y no solo `recommended()`: lo que se promete es
    // «2025.2 en adelante», y `recommended()` comprueba la ultima de cada rama viva,
    // que no tiene por que incluir el suelo declarado en sinceBuild.
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2026.2")
            recommended()
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }
}
