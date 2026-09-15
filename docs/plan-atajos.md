# Plan de atajos

Qué se puede hacer con el teclado hoy, qué falta, y cuánto de eso debería llevar tecla
de fábrica. Escrito para decidirlo de una vez: hoy hay funciones que sólo existen con el
ratón y otras que tienen tecla pero no aparecen en *Settings → Keymap*, que son dos
problemas distintos y se arreglan de forma distinta.

## Las dos preguntas, que no son la misma

1. **¿Aparece en el Keymap?** Una acción aparece si está declarada en `plugin.xml` con su
   texto en el bundle. Aparecer no cuesta nada y es lo que permite que alguien le asigne
   la tecla que quiera. **Aquí la respuesta debería ser siempre sí.**
2. **¿Trae tecla de fábrica?** Cada atajo global se le quita al IDE o a otro plugin. Aquí
   la respuesta debería ser casi siempre no, y cuando sea sí, por una razón que se pueda
   escribir en una línea.

Entre las dos hay un tercer sitio, y es donde vive la mitad de este plugin: el atajo
**local de la tool window**, que se instala con `registerCustomShortcutSet` sobre el
árbol y sólo existe mientras la ventana tiene el foco. `TasklanePanel.localShortcut` ya
hace lo correcto —lee el keymap si la acción tiene asignación y si no cae a un valor
local—, así que `Enter` edita aquí sin que `Enter` deje de ser `Enter` en el editor.

Su coste es la **visibilidad**: un atajo local no sale en el Keymap ni en el tooltip del
botón, así que nadie lo descubre solo. Eso se compensa escribiéndolo —README y ficha del
Marketplace—, no convirtiéndolo en global.

## Inventario

`G` = global de fábrica · `L` = local de la tool window · `—` = sin tecla ·
`(no)` = ni siquiera está declarada, así que no se puede asignar.

### Global

| Acción | Dónde se usa | Hoy | Propuesta |
|---|---|---|---|
| `Tasklane.QuickAdd` | cualquier sitio | **G** `⌘⌥R` / `Ctrl+Alt+R` | igual |
| `Tasklane.NewTaskFromCode` | menú contextual del editor | — | **G** `⌘⌥⇧R` / `Ctrl+Alt+Shift+R` |
| `ActivateTasklaneToolWindow` | lo registra la plataforma | — | dejarlo al usuario, pero **documentarlo** |

### Tool window

| Acción | Hoy | Propuesta |
|---|---|---|
| `Tasklane.EditTask` | **L** `Enter` | igual |
| `Tasklane.DeleteTask` | **L** `Supr` | igual |
| `Tasklane.FocusSearch` | **L** `⌘K` / `Ctrl+K` | igual |
| `Tasklane.SelectPreviousState` / `SelectNextState` | **L** `⌥←` / `⌥→` | igual |
| `Tasklane.MoveToPreviousState` / `MoveToNextState` | **L** `⇧⌥←` / `⇧⌥→` | igual |
| copiar texto de la tarjeta | **L** `⌘C` (sólo con selección) | declararla como acción |
| quitar la selección de texto | **L** `Escape` (sólo con selección) | igual, sin declarar |
| plegar / desplegar grupo | **L** `←` / `→` | igual, sin declarar |
| `Tasklane.NewTask` | — | **L** `⌘N` / `Ctrl+N` |
| `Tasklane.ToggleComplete` | — | **L** `Espacio` |
| `Tasklane.ToggleBookmark` | — | **L** `F11` |
| desplegar la tarjeta (el chevrón) | (no) | declarar + **L** `⌘↵` / `Ctrl+Enter` |
| `Tasklane.MoveToState ▸` | — | — (ya está a `⇧F10` + una letra) |
| `Tasklane.SetPriority ▸` | — | — (ídem) |
| `Tasklane.Export ▸` y las tres de dentro | — | — |
| `Tasklane.ExportMarkdown` | — | — |
| `Tasklane.SearchAllRepos` | — | — |
| `Tasklane.Settings` | — | — |
| agrupar: ninguna / fecha / prioridad / etiqueta | `GroupByDate` sí, **las otras tres (no)** | declarar las cuatro, sin tecla |
| filtro de vista (*All/Open/Overdue/Bookmarked*) | (no) | declarar las cuatro, sin tecla |
| selector de repositorio | (no) | declarar «siguiente repositorio», sin tecla |

### Diálogo de tarea

| Acción | Hoy | Propuesta |
|---|---|---|
| cerrar | `Escape`, incluso desde el cuerpo | igual |
| crear / guardar | `⌘↵` (el del `DialogWrapper`) | igual |
| pegar imagen | el atajo de pegar del IDE | igual |
| negrita / cursiva / código / enlace / lista | sólo botones de la barra | **no** tocarlo: son atajos de *dentro* de un editor, y ahí `⌘B` es «ir a la declaración» |

### Editor

Nada que asignar: la marca inline y la del margen se usan con el ratón —pasar por encima
y pulsar— y no tienen equivalente de teclado que tenga sentido. `Tasklane.NewTaskFromCode`
ya cubre el camino de ida.

## Lo que hay que tocar

Tres cambios, en este orden:

1. **Declarar lo que falta** (`plugin.xml` + `TasklaneBundle.properties`). Las cuatro
   agrupaciones, los cuatro filtros de vista, desplegar la tarjeta, copiar el texto de la
   tarjeta y «siguiente repositorio». Cuesta una línea de XML y dos de bundle cada una, y
   es lo que hace que la lista del Keymap deje de mentir por omisión.
   - Las cuatro agrupaciones ya existen como `SelectGroupingAction`, pero se construyen en
     código dentro de `GroupingActionGroup`, así que el `ActionManager` no las conoce.
     Declararlas y que el grupo las pida por id en vez de instanciarlas.
   - `Tasklane.GroupByDate` queda como está: ya está declarada y es un conmutador.
2. **Añadir los cuatro atajos locales** en `TasklanePanel.installShortcuts()`, con el
   mismo `localShortcut(id, fallback, tree)` de los que ya hay. Sin tocar `plugin.xml`:
   el fallback es local por definición.
3. **Añadir el único global nuevo**, `Tasklane.NewTaskFromCode`, con su
   `<keyboard-shortcut>` para `$default` y para los dos keymaps de macOS, igual que
   `Tasklane.QuickAdd`.

## Por qué esas teclas y no otras

- **`⌘⌥⇧R` para *from Here*.** Pegado a `⌘⌥R`: es la misma acción con el sitio puesto, y
  la relación entre las dos teclas lo dice. Es además el segundo punto de entrada más
  usado, y el que hoy obliga a un viaje al menú contextual justo cuando se está mirando
  el código. Hereda el mismo aviso que `QuickAdd`: en macOS choca, el IDE lo dice al
  instalar y el usuario elige.
- **`⌘N` para *New Task*.** Es lo que hace `⌘N` en cualquier lista del IDE. Global no
  puede ser —ahí es *Generate*—, pero local no le quita la tecla a nadie.
- **`Espacio` para completar.** Es *el* gesto de una lista de tareas. Local, porque en un
  `JTree` el espacio es «marcar la fila» y eso sólo debe cambiar dentro de esta lista.
- **`F11` para el marcador.** La tecla del marcador de la plataforma. Dentro de nuestra
  lista significa nuestro marcador, que es lo que uno espera al pulsarla ahí.
- **`⌘↵` para desplegar la tarjeta.** «Enter abre la tarea, `⌘Enter` la enseña aquí».
- **Nada para los submenús** *Move To* y *Priority*. `⇧F10` ya abre el menú contextual
  desde el teclado y desde ahí son una letra. Una tecla propia para cada uno gastaría dos
  combinaciones en ahorrar una pulsación.
- **Nada para exportar, ajustes, filtros ni agrupaciones.** Son decisiones que se toman
  una vez cada muchas y se cambian desde la barra. Estarán en el Keymap para quien las
  use a diario.

## Antes de publicarlo

- Comprobar el único global nuevo contra los dos keymaps de fábrica: *Settings → Keymap*,
  buscar por atajo `⌘⌥⇧R` y `Ctrl+Alt+Shift+R`, y confirmar que no aparece nadie más.
  Los locales no hace falta comprobarlos: sólo existen con el foco en la tool window.
- Los atajos locales **no salen en el Keymap**, así que la ficha del Marketplace y el
  README son su única documentación. Si se añaden, se escriben.
- Todo atajo local nuevo pasa por `localShortcut`, nunca por `registerCustomShortcutSet`
  sobre una acción del `ActionManager`: eso le reescribe el atajo a todo el IDE. La nota
  ya está en `TasklanePanel.installShortcuts`.

## Si hay que elegir tres

`⌘N`, `Espacio` y `F11`. Son las tres cosas que se hacen muchas veces seguidas en la
lista —crear, tachar, fijar— y las tres únicas que hoy obligan a soltar el teclado sin
ninguna razón. El resto puede esperar a que alguien lo pida desde el Keymap.
