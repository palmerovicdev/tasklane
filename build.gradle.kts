import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
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
// la plataforma 2026.1 -> el minimo que soportamos.
kotlin {
    jvmToolchain(25)
    compilerOptions {
        // 21 y no 25: es lo que ejecuta la plataforma 2026.1, nuestro minimo; Java 25
        // llega con la 262. Bytecode 21 corre sin problema sobre el JBR 25 de la 2026.2.
        jvmTarget = JvmTarget.JVM_21
        // Sin puentes hacia los metodos por defecto de las interfaces de la plataforma.
        // En el modo por defecto Kotlin escribe en cada clase que implementa una
        // interfaz —TasklaneToolWindowFactory, por ejemplo— un override de CADA metodo
        // por defecto que llama al de la interfaz, y el Plugin Verifier del Marketplace
        // cuenta esas llamadas como usos: «uses deprecated API» por
        // ToolWindowFactory.isApplicable e isDoNotActivateOnStart, que nunca se
        // escribieron aqui (2.3.0). Sin compatibilidad, la clase no declara lo que no
        // sobrescribe. Nadie de fuera implementa interfaces de este plugin, que es lo
        // unico que ese modo protegia.
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

// NOTA sobre la version de Kotlin (settings.gradle.kts): debe ser >= la que usa
// el IDE contra el que se compila, porque el compilador tiene que poder LEER sus
// metadatos. IDEA 2026.2 trae metadatos 2.4.0, asi que 2.1.x fallaba con
// "Module was compiled with an incompatible version of Kotlin".
//
// Al reves no hay problema: un compilador nuevo lee metadatos viejos, asi que el
// mismo 2.4.20 sirve para el build de CI contra 2026.1.
//
// El stdlib NO se empaqueta (kotlin.stdlib.default.dependency=false), viene del
// IDE. Por eso el build de CI contra 2026.1 es el que detecta si usamos alguna
// API de stdlib mas nueva que la que trae el IDE minimo soportado.

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

// null -> no hay IDE local configurado: camino de CI y de release, que descarga
// platformVersion. No-null -> dev loop local contra el IDE ya instalado.
val localIde: String? = providers.gradleProperty("localIdePath").orNull?.takeIf(String::isNotBlank)

dependencies {
    // SQLite empaquetado (Fase 6). Hasta la 2.3 se usaba el de la plataforma
    // —bundledModule("intellij.platform.sqlite")—, y el Marketplace lo rechaza: su paquete
    // lleva @ApiStatus.Internal. Mismo fichero y mismo esquema; ver el KDoc de Sql.kt.
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
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
        // Igual que Git4Idea: opcional en plugin.xml, obligatorio para compilar las
        // herramientas MCP de com.tasklane.mcp (2.12.0).
        bundledPlugin("com.intellij.mcpServer")

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
            <h3>2.15.0 &mdash; anchor a block of code</h3>
            <ul>
              <li><b>Anchor a whole block, not just a line.</b> Select several lines and use
                  <i>New Tasklane Task from Here</i>: the task is anchored from the first line to
                  the last, and its badge reads <code>Login.kt:42-58</code>. Type ranges the same
                  way in the task dialog or through the MCP tools; GitHub links with a
                  <code>#L42-L58</code> range keep their end now.</li>
              <li><b>The editor tints the block</b> with a hint of the priority colour while the
                  task is open, under the usual gutter icon or inline chip.</li>
              <li><b>The expanded card shows the code</b> of the block as it is in the file
                  <i>today</i>, and follows your edits. The badge's tooltip shows it too, for
                  single-line anchors as well.</li>
              <li>Creating a task from a multi-line selection no longer copies the code into the
                  task: the block is anchored and shown by the card, and the body is left for your
                  note.</li>
            </ul>
            <p><b>Compatibility:</b> a new column is added to the task database the first time the
               project opens; the schema version does not change. Earlier versions keep reading and
               editing your tasks, but a task they save keeps only the first line of its block.</p>

            <h3>2.14.0 &mdash; counts in the status bar</h3>
            <ul>
              <li><b>Your list in the status bar.</b> <i>ToDo 3 &middot; Doing 1 &middot; 2
                  overdue</i> for the active repository, counted like its tabs, with what is
                  overdue in red. It updates as you work and the moment something falls due.</li>
              <li><b>You choose what it counts</b> in <i>Settings &#9656; Tools &#9656; Tasklane
                  &#9656; Status bar</i>: any of your states, and overdue tasks or not. Out of the
                  box, the states that are not done, plus overdue tasks.</li>
              <li><b>Every count is a link.</b> A state opens its tab; the overdue count takes you
                  to those tasks; anywhere else opens Tasklane. Hide or show it by right-clicking
                  the status bar.</li>
            </ul>
            <p><b>Compatibility:</b> no format change. A new partial index on open tasks with a due
               date is created the first time the project opens; earlier versions ignore it.</p>

            <h3>2.13.0 &mdash; anchors that survive renames</h3>
            <ul>
              <li><b>Anchors follow their file.</b> Rename or move a file in the IDE &mdash; by
                  hand, with a refactoring, or by renaming the class it is named after &mdash; and
                  the tasks anchored to it point at the new path. Moving a folder takes everything
                  inside with it. The tasks keep their dates: moving a file is not editing them.</li>
              <li><b>Broken anchors show.</b> When a file disappears any other way (a
                  <code>mv</code> or <code>rm</code> in the terminal, a <code>git checkout</code>),
                  its badge is struck through with a warning icon, in the task dialog too, and it
                  heals on its own if the file comes back.</li>
              <li><b><code>has:broken-anchor</code></b> finds every task left pointing nowhere, in
                  the search box, in Search Everywhere and through the MCP tools.</li>
            </ul>
            <p><b>Compatibility:</b> no format change.</p>

            <h3>2.12.0 &mdash; Tasklane for AI agents</h3>
            <ul>
              <li><b>Your tasks are now tools of the IDE's MCP server.</b> Turn it on in
                  <i>Settings &#9656; Tools &#9656; MCP Server</i> and an agent such as Claude
                  Code, Junie or AI Assistant can list your tasks, read one with its code anchors
                  at the line where the code is <i>now</i>, write down the work it leaves pending
                  instead of adding <code>// TODO</code> comments, tick checklist items as it goes
                  and complete the task when it is done. Seven tools, all prefixed
                  <code>tasklane_</code>.</li>
              <li><b>Safe by design:</b> there is no delete tool, completing twice never reopens a
                  task, unknown state or priority names fail listing the valid ones, and a
                  database that is being repaired refuses to write.</li>
              <li>Nothing changes unless the MCP Server plugin is enabled; Tasklane opens no port
                  of its own.</li>
            </ul>
            <p><b>Compatibility:</b> no format change.</p>

            <h3>2.11.2 &mdash; the reorder grip, better placed</h3>
            <p>The grip of a manually ordered card now sits halfway down the space below the
               bookmark and menu, slightly in from the right edge, so it reads as part of the
               card rather than of the badge row. No format change.</p>

            <h3>2.11.1 &mdash; a grip to reorder, and bigger checkboxes</h3>
            <ul>
              <li><b>Drag cards by a grip.</b> In a manually ordered state, a grip appears in
                  the bottom-right corner of the card under the mouse &mdash; the space below the
                  bookmark and menu that was always empty. It replaces dragging by the colour
                  stripe, which was eight pixels wide and gave no hint it could be dragged.</li>
              <li><b>Bigger checklist boxes.</b> The boxes are now drawn instead of taken from
                  the font, so they are the size of a letter, and ticked ones are filled in
                  blue like the IDE's own checkboxes.</li>
            </ul>
            <p><b>Compatibility:</b> no format change; 2.11.0 and 2.11.1 open each other&rsquo;s
               projects.</p>

            <h3>2.11.0 &mdash; manual order and checklists</h3>
            <ul>
              <li><b>Order a state by hand.</b> <i>Group By &#9656; Manual Order</i>, then drag a
                  card by its colour stripe, or press <code>&#8984;&#8679;&uarr;/&darr;</code>
                  (<i>Move Up</i> / <i>Move Down</i> in the context menu). Switching does not
                  move anything: the manual order starts as the one you were seeing.
                  Bookmarked tasks stay on top, new tasks come in at the top, and grouping
                  still works &mdash; you order within each group.</li>
              <li><b>Checklists are back.</b> <code>- [ ] item</code> lines are drawn as
                  checkboxes on the card &mdash; in the title too, which is why they were
                  removed in 1.0.0 &mdash; and one click ticks them, writing the
                  <code>x</code> into the body. Ticked items are struck through, and a
                  <code>&#9745; 2/5</code> badge counts them. The task dialog has a
                  <i>Checklist</i> button.</li>
            </ul>
            <p><b>Compatibility:</b> no format change. Opening an existing project builds one
               new index once. 2.10.0 opens 2.11.0 projects and simply ignores the manual
               order.</p>

            <h3>2.10.0 &mdash; tasks in Search Everywhere, and archiving what is done</h3>
            <ul>
              <li><b>Search Everywhere finds your tasks.</b> Press <code>&#8679;&#8679;</code>
                  and type: tasks show up in the <i>All</i> tab and in their own
                  <i>Tasklane</i> tab, with the same syntax and scope as the tool window
                  search. Enter opens the tool window on the task, even if it was
                  closed.</li>
              <li><b>Archive what is done.</b> In <i>Settings &rarr; Tools &rarr; Tasklane</i>,
                  hide tasks completed more than N days ago. Only in the list of a done state
                  &mdash; the tab counts what it shows, and search still finds everything
                  &mdash; and never silently: the list says how many are hidden, with a link
                  to show them. Off by default.</li>
            </ul>
            <p><b>Compatibility:</b> no format change; 2.9.0 and 2.10.0 open each other&rsquo;s
               projects.</p>

            <h3>2.9.0 &mdash; undo deletions and due-date reminders</h3>
            <ul>
              <li><b>Deleting can be undone.</b> Deleting tasks still asks nothing, but it
                  now shows a <i>N tasks deleted &mdash; Undo</i> notification, and
                  <code>&#8984;Z</code> / <code>Ctrl+Z</code> in the task list brings the last
                  deletion back. Tasks return exactly as they were: same dates, same place,
                  same images. Also for deleting a whole group, up to 10,000 tasks.</li>
              <li><b>Due-date reminders.</b> A notification says how many tasks of the active
                  repository are overdue or due today &mdash; when the project opens, when you
                  switch repository and every half hour, only for tasks not mentioned yet
                  today. <i>Show</i> takes you to them. Turn it off from the notification or
                  in <i>Settings &rarr; Tools &rarr; Tasklane</i>.</li>
            </ul>
            <p><b>Compatibility:</b> no format change; 2.8.0 and 2.9.0 open each other&rsquo;s
               projects.</p>

            <h3>2.8.0 &mdash; typed anchors, TODO comments and code blocks</h3>
            <ul>
              <li><b>Anchor a task by typing where it points.</b> In the task dialog, write
                  <code>plans/deploy.md:28</code> &mdash; a path relative to the project and
                  a line &mdash; in the <i>Code</i> row and press Enter. You get the same
                  anchor as creating the task from the editor: the gutter mark, the tooltip
                  and the click back to the line, which is found again if the file
                  changes.</li>
              <li>Also accepts <code>path:line:column</code>, a bare path,
                  <code>path#L28</code> as copied from GitHub, and <code>path(28)</code>.
                  A path that does not exist, a folder or a line past the end of the file
                  stops the dialog and says why.</li>
              <li>Files written by another program a moment ago are found too, and the
                  field <b>autocompletes project paths</b> as you type.</li>
              <li><b>Fenced code blocks.</b> Text between <code>```</code> lines is drawn on
                  the card as a code block, in the editor font and taken literally. The
                  code button of the dialog fences a multi-line selection.</li>
              <li><b>Alt+Enter on a TODO comment &rarr; Move TODO to Tasklane.</b> The TODO
                  becomes an anchored task and the comment is removed from the code
                  (undo brings it back). Comments that say more than the TODO are kept.</li>
              <li><b>Import TODO Comments&hellip;</b> turns every TODO of the project into an
                  anchored task in one go, without touching the code and without
                  duplicating the ones already imported.</li>
            </ul>
            <p><b>Compatibility:</b> no format change; 2.6.3 and 2.8.0 open each other&rsquo;s
               projects.</p>

            <h3>2.6.3 &mdash; scrolling and rendering fixes in the task list</h3>
            <p>A bug fix release: scrolling the task list could jump, and cards could be
               drawn at the wrong height.</p>
            <ul>
              <li><b>The list no longer jumps while you scroll.</b> Loading more tasks,
                  expanding a group or a screenshot finishing loading could shift every
                  card and move the list out from under you. The task you are reading now
                  stays where it is.</li>
              <li><b>Cards are drawn at their correct height.</b> After the tool window
                  changed width &mdash; including when the scrollbar appears on its own
                  &mdash; cards were briefly laid out for the previous width, so a wrapped
                  title could lose its last line and push the priority, due date and tag
                  row out of the card.</li>
              <li><b>The end of the list is reachable again.</b> The list could report
                  itself shorter than it is, leaving the last card clipped against the
                  bottom edge with no way to scroll further.</li>
              <li><b>Scrolling through a large group no longer collapses the groups below
                  it.</b> Loading a second page of tasks could silently fold away the rest
                  of the list.</li>
            </ul>
            <p><b>Compatibility:</b> no format change; 2.6.2 and 2.6.3 open each other&rsquo;s
               projects.</p>

            <h3>2.6.2 &mdash; copying tells you the truth</h3>
            <ul>
              <li><b>&ldquo;N task(s) copied&rdquo; now means the text really is on the
                  clipboard.</b> When the clipboard was busy &mdash; a history manager, a
                  menu closing &mdash; the copy could fail silently and the notification
                  claimed a copy that never happened, so pasting returned the previous
                  content. Tasklane now verifies the clipboard, retries, and reports an
                  error if the text cannot be written.</li>
              <li><b>&ldquo;Export and Remove&rdquo; no longer deletes tasks after a copy
                  it could not verify.</b> If the clipboard rejects the export, the
                  repository is left untouched.</li>
            </ul>
            <p><b>Compatibility:</b> no format change; 2.6.1 and 2.6.2 open each other&rsquo;s
               projects.</p>

            <h3>2.6.1 &mdash; pin an image while you code</h3>
            <ul>
              <li><b>An enlarged screenshot can be pinned.</b> The pin in its header keeps
                  the window <b>above the editor</b> and stops it from closing when you
                  click elsewhere, so you can write code with the image in front of you.
                  Unpinned it still closes on the first click outside.</li>
              <li><b>The image counter on a row is now a button</b>: click it to enlarge
                  the task&rsquo;s screenshots without expanding the card, and hover it to
                  see the screenshot itself instead of a sentence.</li>
              <li><b>The preview of an expanded card no longer disappears now and then</b>
                  when the scroll bar comes and goes: it shrinks those few pixels instead
                  of dropping out of the row.</li>
            </ul>
            <p><b>Compatibility:</b> no format change; 2.5.2 and 2.6.1 open each other&rsquo;s
               projects.</p>

            <h3>2.5.2 &mdash; task workflow fixes</h3>
            <ul>
              <li><b>Copy task text with one click</b>, without card metadata or Markdown markers.</li>
              <li><b>Delete complete grouped views</b> from the context menu.</li>
              <li><b>Search stays stable while typing</b> across state panels.</li>
              <li><b>Task editor text remains readable</b> on dark IDE themes.</li>
            </ul>
            <p><b>Compatibility:</b> no format change; 2.5.1 and 2.5.2 open each other&rsquo;s
               projects.</p>

            <h3>2.5.1 &mdash; new screenshots</h3>
            <ul>
              <li><b>New listing screenshots</b>: the tool window, an expanded card, the
                  task dialogs, the context and export menus, the editor mark and the
                  settings, retaken on the current UI.</li>
            </ul>
            <p><b>Compatibility:</b> no format change; 2.5.0 and 2.5.1 open each other&rsquo;s
               projects.</p>

            <h3>2.5.0 &mdash; IntelliJ IDEA 2026.1.5 or newer</h3>
            <ul>
              <li><b>Requires IntelliJ IDEA 2026.1.5 or newer</b> (build 261.27258.48), or any
                  IDE of the platform from that build on. 2025.2, 2025.3 and 2026.1.0&ndash;2026.1.4
                  are no longer supported: they keep 2.4.0, which works as before.</li>
              <li><b>No deprecated or scheduled-for-removal API</b>: the Plugin Verifier
                  findings of 2.4.0 are gone &mdash; <code>ReadAction.compute</code>, the
                  <code>DynamicBundle(String)</code> constructor and
                  <code>SimpleListCellRenderer.create</code>.</li>
            </ul>
            <p><b>Compatibility:</b> no format change; 2.4.0 and 2.5.0 open each other&rsquo;s
               projects.</p>

            <h3>2.4.0 &mdash; hardening</h3>
            <p><b>What the previous releases took for granted is now tested by force:</b>
               crashes, damaged databases and long sessions.</p>
            <ul>
              <li><b>A damaged task database repairs itself.</b> The damaged file is set aside
                  and never deleted, everything that can still be read is rescued, and only
                  what cannot comes from the daily backup. A broken index &mdash; the most
                  common kind of damage &mdash; is repaired with nothing lost. Tasks are
                  read-only while it runs, a notice says what happened, and an interrupted
                  repair finishes on the next start.</li>
              <li><b>Damage found while the project is open</b> stops all writes, so nothing
                  more is lost inside a broken file, and offers to reopen the project to
                  repair it.</li>
              <li><b>SQLite is now bundled</b> instead of using the one inside the IDE, which is
                  internal API. Same database file, same schema: nothing to migrate.</li>
              <li>Crash tests kill the process in the middle of an edit, the migration, the
                  image cleanup, the backup and the repair itself; a nightly build runs the
                  scale benchmark on a million tasks with the IDE&rsquo;s default 2&nbsp;GB heap
                  and fails if any latency or memory budget is exceeded.</li>
            </ul>
            <p><b>Compatibility:</b> no format change; 2.3.0 and 2.4.0 open each other&rsquo;s
               projects.</p>

            <h3>2.3.0 &mdash; a day per section, and a repository you can empty</h3>
            <ul>
              <li><b>Date sections are days.</b> <i>Today</i>, then one section per day that
                  has tasks, each with its date &mdash; no more <i>Yesterday</i>, <i>This
                  week</i> or whole months of earlier years.</li>
              <li><b>Save a repository to a file</b> as CSV, Markdown or plain text, from
                  <i>Export &rsaquo; Save Repository As</i>. CSV is one row per task, ready
                  for a spreadsheet.</li>
              <li><b>Delete all tasks in a repository</b>, separately and after a
                  confirmation that says how many. The repository stays, empty.</li>
              <li><b>Links in a card's body open with a click</b>, not only from the link
                  counter.</li>
              <li><b>The code anchor badge never falls off a narrow card</b>: when
                  <code>Auth.kt:42</code> does not fit, its icon stays and still jumps to
                  the code. The priority badge does the same with its colour dot.</li>
              <li><b>Settings:</b> priorities are listed highest first, as in the tool
                  window; rows of both tables can be dragged by their handle; and clicking a
                  colour swatch opens the colour picker and actually changes the colour.</li>
              <li>The expand, bookmark and menu icons of a card sit closer together.</li>
            </ul>
            <p><b>Fixed:</b> clicking a code anchor badge threw a read-access error on
               recent IDEs instead of opening the file; picking a priority colour in
               Settings did nothing; and the Plugin Verifier findings are gone &mdash; no
               internal API, no <code>KeymapUtil.INSTANCE</code> reference that broke
               2026.1, and no calls to deprecated <code>ToolWindowFactory</code> or
               checkbox renderer methods.</p>
            <p><b>Images are stored at their original size.</b> The 400&nbsp;px cap is gone:
               a screenshot of code at 400&nbsp;px cannot be read.</p>
            <ul>
              <li>A pasted screenshot is saved as a lossless PNG; a dropped, chosen or copied
                  file is saved <b>byte for byte</b>. Measured on a 2880&nbsp;px screenshot:
                  pasting takes 129&nbsp;ms of background CPU instead of 8 and 1.25&nbsp;MB
                  instead of 46&nbsp;KB; dropping a file went from 47&nbsp;ms to 1&nbsp;ms.</li>
              <li><b>Settings always shows how much the active repository&rsquo;s images
                  weigh</b>, with two buttons for that repository only: <i>Delete Unused
                  Images</i> removes right away every image no task points at, and
                  <i>Delete All Images&hellip;</i>, after a confirmation, removes all of them
                  &mdash; tasks are left untouched and show the missing image.</li>
              <li>The image size warning is per repository and names it.</li>
              <li>The task dialog paints its previews from a bounded copy and opening an image
                  full size loads it in the background, so large originals never decode on the
                  UI thread.</li>
            </ul>
            <p><b>Compatibility:</b> no database format change; the schema stays at
               version 1. Images keep their place and name, and the old
               <code>imageMaxSize</code> setting is simply ignored.</p>

            <h3>2.2.0 &mdash; the big operations</h3>
            <p><b>Exporting a million tasks is still reading a million tasks.</b> What
               changes is everything around it: it no longer holds what it reads, no longer
               runs on the UI thread, no longer blocks anything else, and can be
               cancelled.</p>
            <ul>
              <li><b>Exports stream.</b> With 100,000 tasks, copying a 33,000-row state used
                  to hold 280&nbsp;MB; it now peaks at 2.2&nbsp;MB. Exporting the repository
                  to <code>tasks.xml</code> held 1.5&nbsp;GB; now 15&nbsp;MB. What comes out
                  is the tab <b>as it was when you clicked</b>, even if you keep editing.</li>
              <li><b>Past 10,000 tasks, export to a file</b> instead of flooding the
                  clipboard. The file is written in full or not at all.</li>
              <li><b>Acting on many tasks at once</b> &mdash; move, complete, bookmark,
                  change priority, delete &mdash; is one operation and one repaint, not one
                  per row. Above ten rows it runs in the background with a progress bar,
                  and <b>cancelling undoes it</b>. Bookmarking 2,000 tasks went from 1.9&nbsp;s
                  to 0.74&nbsp;s.</li>
              <li><b>&ldquo;Export and Remove&rdquo; works at any size.</b> The repository
                  goes read-only, every task is exported and <b>counted before anything is
                  deleted</b>, and removal runs in batches: with 100,000 tasks, the longest
                  any other action waits went from 12.3&nbsp;s to 263&nbsp;ms.</li>
              <li><b>A daily backup of the task database</b>,
                  <code>tasklane.db.backup</code>, made in the background only when
                  something changed. After an unclean IDE shutdown the database is checked,
                  and if it is damaged you are told, with the date of that backup.</li>
            </ul>
            <p><b>Fixed:</b> a repository renamed or deleted from disk vanished from the
               selector with its tasks inside (since 2.0.0); exporting a new repository to
               XML ended in a &ldquo;corrupt file&rdquo; warning; exporting to XML during an
               unfinished migration overwrote the original; the migration notice showed
               <code>{1}</code> instead of a path.</p>
            <p><b>Compatibility:</b> no format change. The database schema stays at
               version 1, and 2.1.0 opens a project used by 2.2.0 as if nothing
               happened.</p>

            <h3>2.1.0 &mdash; pasted images stop weighing</h3>
            <p><b>Screenshots used to live in one flat folder that had to be listed whole
               every time the project opened, and the list decoded the full-size original
               to paint a thumbnail-sized preview.</b> Neither happens any more.</p>
            <ul>
              <li><b>The list never decodes a large screenshot again.</b> Ones saved at
                  1600&nbsp;px get a 256&nbsp;px thumbnail, made in the background the
                  first time they are shown. A card with ten of them took 134&nbsp;MB of
                  memory and 176&nbsp;ms to open; it is now 36&nbsp;MB and 8.7&nbsp;ms.
                  Clicking a preview still opens the full original.</li>
              <li><b>New screenshots are scaled to 400&nbsp;px</b> instead of 1600.
                  Measured on PNGs calibrated against a real screen capture: 407&nbsp;KB
                  per shot at 1600, 33&nbsp;KB at 400. <b>Images you already saved are left
                  exactly as they are</b> &mdash; a blob is named after its own checksum,
                  so rescaling one would mean rewriting every task that points at it.</li>
              <li><b>Images are stored in subfolders</b>
                  (<code>attachments/ab/cd/&lt;sha&gt;.png</code>). With ten million files,
                  a single folder is not slow &mdash; it is unusable. Whatever you have is
                  moved in the background, in batches, and nothing stops being visible
                  while it happens.</li>
              <li><b>Finding which images are unused stops looking at the disk.</b> The
                  database keeps count: with 100,000 images saved, that went from a
                  329&nbsp;ms folder scan on every open to a 0.49&nbsp;ms query &mdash;
                  and unlike the scan, it does not grow with how much you have saved.</li>
              <li><b>A warning when images pass 5&nbsp;GB</b>, with the size and what to do
                  about it. <b>Nothing is ever deleted because of it</b>, and the threshold
                  can be raised or switched off in the settings.</li>
              <li><b>A weekly background reconciliation</b> adopts images that appear in the
                  folder without the plugin knowing, and marks as missing the ones deleted
                  from outside it.</li>
              <li><i>Tasklane: Diagnostics</i> now counts images from the database: the
                  figure is exact instead of stopping at 200,000, and it says how many are
                  stored above today&rsquo;s scaling cap.</li>
            </ul>
            <p><b>Compatibility:</b> an older version of the plugin will not find images
               that have already been moved and will paint them as missing. Nothing is
               lost &mdash; the files are there, in their subfolders, and they come back as
               soon as you open the project with 2.1 again &mdash; and tasks keep reading
               and editing normally.</p>

            <h3>2.0.0 &mdash; your tasks move into a database</h3>
            <p><b>Tasks no longer live in a <code>tasks.xml</code> that was read whole on
               open and rewritten whole on save.</b> They live in a local SQLite database
               &mdash; the one the IDE already ships. Nothing about the window changes:
               same order, same groups, same counts. Everything about the cost does.</p>
            <ul>
              <li><b>Opening a project stops reading your tasks.</b> With 100,000 tasks,
                  opening the tool window and painting it went from
                  <b>3,027&nbsp;ms to 9.2&nbsp;ms</b>. The window asks for the page you
                  can see, not for the list.</li>
              <li><b>Memory stops growing with the project.</b> Measured: <b>2.5&nbsp;MB</b>
                  with 100,000 tasks, against the 1.3&nbsp;GB it took to keep the model
                  alive in memory. It is the same figure with ten thousand as with a
                  million.</li>
              <li><b>Saving is a transaction, not a dump.</b> Editing one task used to
                  rewrite the whole file &mdash; 1,168&nbsp;ms on 100,000 tasks &mdash;
                  behind a half-second delay. It is now <b>0.44&nbsp;ms</b> and there is
                  no delay, so an IDE crash no longer takes the last thing you typed.</li>
              <li><b>Search uses a full-text index</b> instead of walking every task. On
                  100,000, the worst possible query &mdash; a word present in almost all
                  of them &mdash; went from 115&nbsp;ms to 40&nbsp;ms.</li>
              <li><b>Deleting a state with tasks in it is instant</b>, however many it
                  has: one instruction to the database instead of a pass over all of
                  them.</li>
              <li><b>Your old file is kept.</b> The migration runs once, in the
                  background, with a progress bar, and can be cancelled and resumed. The
                  old <code>tasks.xml</code> is left beside the database as
                  <code>tasks.xml.migrated</code> &mdash; renaming it back is how you go
                  back. And <i>Copy &rarr; Export Repository to XML</i> writes your tasks
                  out to that format whenever you want, so nothing is locked in.</li>
              <li>Fixed: an unreadable <code>tasks.xml</code> used to leave the repository
                  blank until the backup was read. What could be read now gets in anyway,
                  and only the rest is looked for in the backup.</li>
            </ul>

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
    // «2026.1.5 en adelante», y `recommended()` comprueba la ultima de cada rama viva,
    // que no tiene por que incluir el suelo declarado en sinceBuild. IntellijIdea y no
    // Community: desde la 2025.3 no hay IC que descargar.
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2026.1.5")
            create(IntelliJPlatformType.IntellijIdea, "2026.2")
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

        // Y el numero de blobs de las puertas de la Fase 4, que es un eje distinto: un
        // millon de tareas son 10 GB de base y un millon de capturas son 33 GB de PNG,
        // asi que pedirlos a la vez es una noche de disco y ninguna puerta lo necesita.
        providers.gradleProperty("benchBlobs").orNull?.let { systemProperty("tasklane.bench.blobs", it) }

        // Fase 6: cuantos comandos aplica la prueba de longevidad, y donde deja el banco sus
        // cifras en CSV para la CI nocturna.
        providers.gradleProperty("benchCommands").orNull?.let { systemProperty("tasklane.bench.commands", it) }
        providers.gradleProperty("benchOut").orNull?.let { systemProperty("tasklane.bench.out", it) }

        // Las pruebas de caida de la Fase 6 (CrashTest): cuantas veces se mata cada escenario
        // y con que semilla. El build normal hace dos vueltas; -PcrashRounds=50 es la de verdad.
        providers.gradleProperty("crashRounds").orNull?.let { systemProperty("tasklane.crash.rounds", it) }
        providers.gradleProperty("crashSeed").orNull?.let { systemProperty("tasklane.crash.seed", it) }

        // Sin esto una @Ignore que se desactiva a mano no imprime nada util.
        testLogging {
            showStandardStreams = providers.gradleProperty("benchN").isPresent
        }
    }
}
