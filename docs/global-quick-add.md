# Add a task while another application is focused

Global Quick Add opens Tasklane's existing **New Task** dialog using an optional system shortcut. The IDE must be running with a project open. It is disabled by default and does not replace the IDE's **Quick Add Task** Keymap binding.

## Configure

1. Open **Settings → Tools → Tasklane Global Quick Add**.
2. Enable the system-wide shortcut and choose its modifiers and key.
3. Leave the destination as the last active project, or enter the absolute directory of a fixed project. A fixed project must be open in this IDE instance; it never silently falls back to a different project.
4. Apply. Check the registration status; it must show **Active**, not merely **Registering**. Resolve a conflict by choosing another combination and applying again. **Retry registration** retries the saved configuration, not unapplied edits.
5. Switch to another application and press the shortcut. The dialog creates the task in the target project's active Tasklane repository.

Settings are local to this IDE installation and do not roam through Settings Sync. Disabling the feature releases its shortcut. Other running IDE instances may already own the same combination; choose a different combination in each instance.

## Platform boundaries

- Windows uses registered system hotkeys; F12 is reserved by Windows.
- macOS uses registered hotkeys, not keyboard monitoring. Native keyboard positions and function-key availability can differ from the labels on non-US layouts. F21–F24 are not supported by the current macOS adapter.
- X11 uses passive key grabs on a private connection, not whole-keyboard capture.
- Wayland uses the desktop's Global Shortcuts portal. The desktop can ask for consent, change the requested combination, deny registration, or lack the portal entirely. The reported effective binding is authoritative. No X11, input-monitoring or elevated-permission workaround is attempted.

Window activation is a request to the desktop, not a guarantee. In particular, Wayland controls foreground focus; a registered shortcut does not prove that the IDE can raise its window. The feature does not run when the IDE is closed, unlock a session, or intercept secure desktop input.

## Verification status

Implementation verification is tracked in `odd/tasks/global-quick-add.md`. Automated tests use fake native/portal adapters: they do not register real system hotkeys. Native foreground behavior remains unverified until the following manual checks are completed on each target system.

- Start with the feature disabled: no system shortcut is reserved.
- Enable and apply; switch to another app; invoke once and hold the keys: only one task dialog opens.
- Cancel: no task is saved. Confirm: the task and its attachments use the same captured repository.
- Change the shortcut, disable it, and unload the plugin: the old combination must be released and stale callbacks ignored.
- Open two projects; verify last-active and fixed-project routing. Close the fixed project: no task appears in another project.
- Reserve the combination elsewhere; verify visible failure and successful retry after resolving it.
- On Wayland test consent grant, denial, timeout, effective key changes and a desktop without portal support; verify foreground behavior separately.
