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
        // SPIKE Fase 0: SQLite de la plataforma.
        bundledModule("intellij.platform.sqlite")

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
            <h3>1.6.0 &mdash; the list stops weighing what the project weighs</h3>
            <ul>
              <li><b>Repainting the list no longer depends on how big the project is.</b>
                  On 100,000 tasks, opening the tool window went from 1,179&nbsp;ms to
                  4.1&nbsp;ms, and repainting after completing a task from 1,194&nbsp;ms
                  to 0.31&nbsp;ms. On a million, from 10.2&nbsp;s to 0.61&nbsp;ms. The
                  tree is no longer rebuilt on every change: it is brought up to date row
                  by row, and the rows that did not change keep the height they were
                  already measured at.</li>
              <li><b>The list loads a page at a time.</b> At the end of a group there is a
                  row telling you how many tasks are left; scrolling to it, clicking it or
                  pressing <i>Enter</i> brings the next ones. Same gesture as
                  <i>Find in Files</i>.</li>
              <li><b>Big groups start collapsed</b>, with their count in the header
                  &mdash; and so do the ones below, once the ones above already fill the
                  screen. Opening one loads its first page right then. What you open or
                  close always wins.</li>
              <li><b>Revealing a task from an editor mark</b> no longer has to load
                  everything above it: the list opens at its height and says how much is
                  left above.</li>
              <li>Fixed: <b>every repaint walked the whole project</b> on the UI thread
                  just to forget expanded cards that no longer existed. On big lists that
                  cleanup cost more than the repaint itself &mdash; and it was never
                  needed, since task ids are not reused.</li>
              <li>Fixed: <b>copying a state or a group to the clipboard copied whatever
                  happened to be painted.</b> It now asks for the full list again, which
                  is what &ldquo;copy this state&rdquo; means even when the list loads in
                  chunks.</li>
            </ul>

            <h3>1.5.0 &mdash; big lists stop weighing</h3>
            <ul>
              <li><b>A command costs eight times less.</b> On a list of 100,000 tasks, a
                  command went from 4.14&nbsp;ms to 0.51&nbsp;ms. The model now reports what
                  it changed instead of the service working it out by walking everything
                  after every keystroke.</li>
              <li><b>Saving the edit dialog sends one change, not six.</b> Body, state,
                  priority, tags, due date and anchors used to go as six separate commands,
                  each producing a new state &mdash; six copies of the list and up to six
                  tree repaints for one click on <i>Save</i>. Editing a task on a list of
                  100,000 went from 26&nbsp;ms to 6&nbsp;ms.</li>
              <li><b>New action: <i>Tasklane: Diagnostics</i>.</b> How many tasks you have,
                  what they weigh on disk, how many images and how much deduplication saves
                  you, and the last hour of latencies per operation. One button copies the
                  whole report, because where it belongs is an issue. It says so plainly
                  when repainting goes over its budget, since that one happens on the UI
                  thread.</li>
              <li>Fixed: <b>the image caches are bounded by memory, not by number of
                  entries.</b> A 1600px screenshot decoded takes 10.2&nbsp;MB and sixteen
                  were kept &mdash; up to 164&nbsp;MB of heap with twenty tasks on screen.
                  The caps are now 64&nbsp;MB and 16&nbsp;MB, actually counted.</li>
              <li>Fixed: deleting a task that was already gone still rebuilt the list, with
                  a repaint and a disk write behind it. And accepting the edit dialog
                  without changing anything still moved the task's modified date.</li>
            </ul>

            <h3>1.4.1 &mdash; the mark reads, and what you copy carries its date</h3>
            <ul>
              <li><b>The inline chip's tooltip shows the task's screenshots.</b> Half a task
                  is a pasted image &mdash; the error you saw, the design to copy &mdash; and
                  the tooltip used to give you the title and the state in exactly the case
                  where looking at the picture <i>was</i> the answer. Up to two per tooltip,
                  scaled, highest priority first.</li>
              <li><b>A date group is copied as the log of its day.</b> In Markdown the
                  heading is the date in ISO form and the tasks are a numbered list, ready to
                  paste into a journal or a weekly report. &ldquo;Done &middot; Today&rdquo;
                  stopped being true the next day, and the <code>- [x]</code> box is noise
                  once the whole group means &ldquo;this got done that day&rdquo;. Only for
                  groups that are one actual day: this week, a month and undated keep the tab
                  heading and their boxes rather than invent a date. Plain text is
                  unchanged.</li>
              <li>Fixed: the inline chip sat flush against the code on both sides, so
                  <code>websi</code>&middot;<code>TODO</code>&middot;<code>te</code> read as a
                  single word. It keeps a little air now &mdash; the space belongs to the
                  mark, not to your file.</li>
            </ul>

            <h3>1.4.0 &mdash; the whole card, where you can reach it</h3>
            <ul>
              <li><b>Every card shows its priority badge now</b>, the default priority
                  included. It used to stay quiet &mdash; it would be the same word on
                  every row &mdash; but since the badge became the button that changes
                  the priority, staying quiet hid the control on exactly the cards nobody
                  has touched yet. The colour dot is part of the button too.</li>
              <li>The <i>Priority</i> submenu has an icon: it was the only entry in the
                  context menu without one.</li>
              <li>Fixed: with a narrow tool window, a card with a few tags asked for more
                  width than there was, and the platform popped the rest of the row
                  outside the panel on hover &mdash; with the buttons on its right inside.
                  Reaching for them closed it.</li>
              <li>Fixed: cards could lose their bottom line &mdash; priority, date and
                  tags gone, with no hint why. The tree kept a width of its own and
                  stopped following the panel, so a card was measured at one width and
                  painted at another. It follows the panel now, and the window will not go
                  below 300px wide.</li>
              <li>Fixed: a word with no spaces in it &mdash; a long identifier, an
                  unshortened URL &mdash; used to stretch the whole row, and the buttons
                  on the right stopped landing where they are drawn: clicking the bookmark
                  folded the card. Those lines are cut with an ellipsis now.</li>
            </ul>

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
                  every selected task at once.</li>
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

    test {
        // El banco de la Fase 0 materializa corpus grandes a proposito: medir lo que
        // el modelo retiene ES la puerta del §2.6. Con el heap por defecto de Gradle
        // un corpus de 100k muere antes de llegar a la primera medida, y un OOM no
        // es un dato: es la ausencia de dato.
        //
        // Se sube por propiedad para que el job nocturno del §6.3 pueda pedir mas sin
        // tocar el fichero, y para que un `./gradlew test` normal no reserve 4 GB.
        maxHeapSize = providers.gradleProperty("testHeap").getOrElse("2g")

        // El tamano del corpus del banco. Los tests normales lo ignoran.
        providers.gradleProperty("benchN").orNull?.let { systemProperty("tasklane.bench.n", it) }

        // Sin esto una @Ignore que se desactiva a mano no imprime nada util.
        testLogging {
            showStandardStreams = providers.gradleProperty("benchN").isPresent
        }
    }
}
