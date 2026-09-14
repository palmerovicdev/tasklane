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
            <h3>1.3.0 &mdash; cards you can read and act on</h3>
            <ul>
              <li><b>Card text is selectable.</b> Drag across a card to select its text
                  and press <code>Cmd/Ctrl+C</code> to copy it. <code>Escape</code>
                  clears the selection.</li>
              <li><b>Expand a card</b> with the new chevron on its right to read the
                  whole task in place &mdash; the title without the three-line cap and
                  every line of the body, not just the first one. Click it again to fold
                  it back. It only shows up when the card is actually hiding something.</li>
              <li><b>Images show up in an expanded card</b>, scaled, with the same
                  border and the same loading marker as in the dialog, each one right
                  after the line that references it. Click one to see it full size. A
                  folded card still shows the <code>1 img</code> counter instead.</li>
              <li><b>Change the priority without opening the dialog.</b> Click the
                  priority badge on a card for a list of priorities with their colours,
                  or use the new <i>Priority</i> submenu in the context menu to change
                  every selected task at once. Every card carries the badge now, the
                  default priority included &mdash; it is the button, and the cards
                  nobody has touched yet are the ones whose priority changes most.</li>
              <li>Fixed: with a narrow tool window, a card with a few tags asked for more
                  width than there was, and the platform popped the rest of the row
                  outside the panel on hover &mdash; with the buttons on its right inside.
                  Reaching for them closed it. A card never asks for more than it can
                  show now.</li>
              <li>Fixed: cards in a narrow tool window could lose their bottom line
                  &mdash; priority, date and tags gone, with no hint why. The tree kept a
                  width of its own and stopped following the panel, so a card was
                  measured at one width and painted at another. It follows the panel now,
                  and the window itself will not go below 300px wide.</li>
            </ul>

            <h3>1.2.1</h3>
            <ul>
              <li>Fixed: the inline mark's popup vanished after a fraction of a second, with
                  the mouse still on it. It now stays for as long as you hover it.</li>
            </ul>

            <h3>1.2.0 &mdash; and the code points back</h3>
            <ul>
              <li><b>Anchored lines are marked in the editor</b>, in the colour of the
                  task's priority. Hover the mark to read the task, click it to open it in
                  the tool window.</li>
              <li>Only tasks that are still open get a mark: a task in a done state is
                  history, not a note about the code.</li>
              <li>Choose the mark in <i>Settings &rarr; Tools &rarr; Tasklane</i>: a gutter
                  icon, or an inline chip on the exact character the task was about. The
                  choice is yours alone &mdash; it is not shared with the project.</li>
              <li>An anchor now remembers the column too, so it takes you back to the
                  character and not just to the line.</li>
            </ul>

            <h3>1.1.0 &mdash; tasks that point at the code</h3>
            <ul>
              <li><b>New Tasklane Task from Here</b> in the editor's context menu creates a
                  task anchored to the file and line you are looking at, with the selected
                  text as its body. The card shows a <code>Auth.kt:42</code> badge that
                  jumps straight back there.</li>
              <li>An anchor survives edits above it: the line is found again by its text,
                  not by its number.</li>
              <li>New search operators: <code>file:</code> and <code>has:code</code>.</li>
              <li><b>Move To</b> sends the selected tasks to another state without opening
                  the dialog, with <code>Shift+Alt+Left/Right</code> to move them one tab
                  at a time. <code>Alt+Left/Right</code> switches state tabs again.</li>
              <li>Fixed: group headers would not fold. The platform silently refuses to
                  collapse a top-level node in a tree without root handles.</li>
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
