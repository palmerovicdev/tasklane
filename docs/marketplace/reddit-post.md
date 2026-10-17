**Title:** I built Tasklane, a task list for JetBrains IDEs that lives next to your code, and can point at the exact lines each task is about (free, MIT, no account)

---

Hey r/Jetbrains 👋

I've been building a plugin called **Tasklane**, and it's finally at a point where I'd love other people to try it and tell me what's wrong with it.

It's for the stuff you notice *while* you're coding: "this retry logic is broken", "add an empty state here", "check this before I push". That's too small for Jira or GitHub Issues. `// TODO` comments get lost or end up in commits. And a notes app has no idea which file you were in.

So Tasklane is a tool window with a task list **per Git repository**, stored inside the project. No account, no server, no network calls. Everything stays on your machine.

Here's everything it does. It's a long list, sorry 😅

[📷 01-tasks-next-to-your-code.png]

## The basics

- **One list per Git repository.** Several repos in the same project? Each one gets its own tasks, with a selector to switch and a toggle to search all of them at once. Submodules count too. No Git? It falls back to the project root.
- **States as tabs**, with live counts that respect your search and filter. The defaults are To Do / In Progress / Review / Done, but you can have whatever states you want. The row scrolls sideways if they don't fit.
- **A card per task**: priority stripe, a checkbox to complete it, a title of up to three lines, one line of the body, and badges for priority, code anchors, due date, tags, links, images and date.
- **Bookmarks** pin a task to the top of its group, whatever its priority.
- **View filter**: All / Open / Overdue / Bookmarked, on top of whatever you're searching.
- **Empty repo?** The list shows you how to get started, with the shortcuts.

## Tasks anchored to code

This is the part I use the most.

- **Anchor a task to a line or a block.** Select some code, then *New Tasklane Task from Here* (editor right-click, or `⌘⌥⇧R` / `Ctrl+Alt+Shift+R`), and the task points at `session.ts:49-60`.
- **The editor shows it.** Anchored lines get a mark in the priority colour, either a gutter icon or an inline `TODO` pill (your choice). Hover it to see the task, its tags and even its screenshots. Click it and the task opens in Tasklane.
- **The card shows the code.** Expand an anchored card and you see the current code of that block, read live from the file. One click takes you back to the exact line and column.
- **Anchors follow the code.** Adding lines above, re-indenting or moving the function a bit doesn't break them, because they're re-found by their text. Rename or move the file from the IDE, even with a refactoring, and the anchor goes with it. If the file disappears, the anchor shows as broken instead of silently doing nothing.
- **`Alt+Enter` on a `// TODO`** turns it into an anchored task and removes the comment. *Import TODO Comments…* pulls in every TODO in the project without touching the code.
- **GitHub-style ranges work too**: paste `src/Login.kt:42-58` or `#L42-L58` into the dialog, with path autocompletion.

[📷 02-anchor-tasks-to-code.png]

## Capturing tasks fast

- **`⌘⌥R` / `Ctrl+Alt+R` from anywhere** opens the task dialog without opening the tool window.
- **Priority triggers**: start with `!!!` and the task is created as High. Triggers are configurable per priority.
- **Inline tags**: type `#api` and a space in the text and it becomes a tag chip. It ignores `#123`, `C#` and anything inside code.
- **Tag suggestions** from the repo, with counts. Just click the tags field to see them, so you don't end up with both `#api` and `#apis`.
- **Paste a list → get a list.** `⌘V` in the task list with some text creates one task per line. Bullets, numbers and `[ ]` are stripped, and URLs become links. Great for meeting notes.
- **Paste a screenshot** and you get a task with that image.
- **Drop files** from Finder, the project view or the browser. An image becomes a task with that screenshot, and a code file becomes a task anchored to it.
- Everything you just created stays selected, and one `⌘Z` removes it all.

[📷 03-capture-in-seconds.png]

## Cards that hold the whole story

- **Markdown body**: bold, italics, `code`, strikethrough and fenced code blocks, rendered on the card itself. The dialog has a formatting toolbar.
- **Checklists**: `- [ ] item` becomes a real checkbox you can tick right from the list, and the card shows `☑ 2/5`.
- **Links open in one click** from anywhere on the card, without entering edit mode. Long URLs are shortened for display.
- **Screenshots**: paste, drop or pick images in the dialog. They're stored at full size and deduplicated by hash, with thumbnails in the list. Click to enlarge, and **pin** the image window so it stays on top of the editor while you code.
- **Expand a card** (`⌘↵`) to read everything, images included, without leaving the list.
- **Select and copy** text straight from the card.

[📷 04-cards-hold-the-whole-story.png]

## Organizing

- **Group by** priority, date (Today plus one group per day) or tag, chosen per state. Groups collapse, and that's remembered.
- **Manual order**: drag cards by their handle, or use `⌘⇧↑/↓`.
- **Board view**: open all your states as columns in an editor tab and drag cards between them, selections included. Same cards, same menus, same undo.
- **Archive old stuff**: optionally hide tasks completed more than N days ago. The list always tells you how many are hidden, and search still finds them.
- **Huge lists are fine**: the list loads in pages, and big groups start collapsed.

[📷 05-board.png]

## Search

- **Full-text search** over the whole body, ignoring case and accents, ranked by relevance (SQLite FTS5).
- **A small query language:**
  - `state:doing`, `p:high`, `repo:backend`
  - `is:open`, `is:done`, `is:overdue`, `is:bookmarked`
  - `has:due`, `has:checklist`, `has:tag`, `has:link`, `has:image`, `has:code`, `has:broken-anchor`
  - `file:AuthService` (by anchored path), `#api #urgent`
  - Dates: `due:today`, `due:<7d`, `closed:week`, `created:>2026-09-01`, `updated:<2w`
  - Negate anything with `-`: `-#wip`, `-p:low`, `-is:done`
- **Autocompletion** for operators and their values: your states, priorities and tags.
- **It shows why each task matched**: matches are highlighted, and a collapsed card shows the body line that matched, not just the first one.
- **Search Everywhere** (`⇧⇧`) has a Tasklane tab with the same syntax. `Enter` jumps to the task, even if it's archived.
- **Click a tag** on a card to filter by it.

[📷 06-find-anything.png]

## Acting on many tasks + undo

- **Multi-select and act on all of them as one operation**: complete, bookmark, *Move To ▸*, *Priority ▸*, *Due ▸* (Today / Tomorrow / End of Week / Next Week / Pick Date), *Tags ▸ Add / Remove*, delete. Over ten tasks it runs in the background, and cancelling rolls it all back.
- **Keyboard everything**: `Space` completes, `1`…`9` sets priority, `⇧⌥←/→` moves tasks to the previous/next state, `⌘N` creates a task in the current state.
- **Real undo/redo** (`⌘Z` / `⌘⇧Z`) for everything you do in the list: moves, priorities, edits, checkboxes, reordering, creating, deleting. The status bar tells you what was undone.
- **Delete doesn't ask, it lets you undo**: "3 tasks deleted, Undo" brings them back exactly as they were.

[📷 07-act-on-many.png]

## AI agents (MCP)

If you use Claude Code, Junie, AI Assistant, Cursor or any other MCP client:

- With the IDE's built-in **MCP server** turned on, agents get Tasklane tools: list repositories, list and search tasks (same query syntax), get a full task (anchors resolved to *today's* line, plus screenshot file paths), create, update, complete, and tick checklist items.
- **There's deliberately no delete tool.** Agents can't nuke your list.
- **Hand Off to Agent**: right-click a task and an IDE terminal tab opens with your agent already working on it. You can set up as many agents as you want (Claude Code, Codex, Gemini, the same one with another model…), each with its own command. When it's done, the agent closes the task.
- **Copy Agent Prompt** for agents that don't live in a terminal, and **Copy Agent Instructions** gives you a paragraph for `CLAUDE.md` / `AGENTS.md` so agents put their leftovers in Tasklane instead of sprinkling `// TODO`s.
- Whatever the agent changes shows up in the list instantly, and it doesn't mess with *your* undo stack.

[📷 08-hand-off-to-agents.png]

## Make it yours

Settings live under *Settings → Tools → Tasklane*:

- **States**: name, which is the default, which ones are terminal (they close tasks and stamp a completion date), and how each one groups and sorts. Drag rows to reorder the tabs. You also choose which states show in the tool window, on the board and in the status bar.
- **Priorities**: name, default, trigger, and colours for light and dark themes.
- **Tags**: see every tag in the repo with its count. Rename, merge (rename to an existing name), delete, and give them colours. Coloured tags show as filled pills.
- **Code anchors**: gutter icon, inline pill, or nothing.
- **Template for new projects**, so you don't configure every project from scratch.
- States, priorities and tag colours are saved in `.idea/tasklane.xml`, so **you can commit them and share them with your team**. Personal stuff (filters, open tab, reminders) stays in `workspace.xml`.
- Removing a state or priority that still has tasks asks where to move them. Nothing gets lost.

[📷 09-make-it-yours.png]

## Due dates and the status bar

- **Due dates** from presets or a calendar. Overdue dates turn red.
- **A reminder** like "2 overdue · 1 due today in backend", at most once a day per task. Easy to turn off.
- **A status bar widget**: `To Do 7 · In Progress 2 · Review 2 · 1 overdue` for the active repo. Click a count to jump there.

[📷 10-never-miss-a-due-date.png]

## Export, import and your data

- **Copy to clipboard** as Markdown or plain text: a whole state, a date group or just the selection. A date group comes out as a little daily report (`## 2026-09-11` plus a numbered list), handy for standups.
- **Save to file**: `.md`, `.csv` (opens fine in Excel) or `.txt`, for the selection, a group, a state or the whole repo.
- **Repository archive (`.zip`)** with all tasks and their screenshots. Import merges by id, shows you a summary first and never overwrites newer tasks.
- **Local SQLite database** in `.idea/tasklane/`, with its own `.gitignore`, so nothing shows up in `git status` unless you want it to.
- **Daily backup**, an integrity check after a crash, and **automatic repair** if the database ever gets damaged. The damaged file is kept, never deleted. It's tested by killing the process mid-write.
- **Image housekeeping**: unused screenshots are cleaned up automatically, there's a size counter and cleanup buttons, and a warning if a repo's images go over a size you choose.
- *Tasklane: Diagnostics* shows counts, disk usage, backup status and latencies.

## Performance and accessibility

- Built for big lists: a nightly CI benchmark runs on **a million tasks** with the IDE's default 2 GB heap. Counting, grouping and searching all run off the UI thread.
- The whole tool window works from the **keyboard**. `Tab` even walks through the links, checkboxes and badges on a card. The list, the search field and the dialog all have proper screen reader labels.
- Every action is in the Keymap and in Find Action, so you can rebind anything.

## Details

- Works in **any JetBrains IDE**: IntelliJ IDEA, WebStorm, PyCharm, GoLand, PhpStorm, RubyMine, CLion, Rider… version **2026.1 or newer**.
- Git, Terminal and MCP integrations are optional. Without them, the rest still works.
- **Free and open source (MIT).**

**Install:** *Settings → Plugins → Marketplace*, search for **Tasklane**
**Source:** https://github.com/palmerovicdev/tasklane

It's a solo project, so any feedback helps a lot: what's confusing, what's missing, what annoys you, or what breaks. I'll be around in the comments. Thanks for reading all the way down here 🙏
