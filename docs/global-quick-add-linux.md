# Global Quick Add on Linux

Global Quick Add is opt-in and only runs while the IDE is open. The existing IDE-local Quick Add keymap action remains available if global registration fails.

- **X11:** Tasklane uses libxcb and requests passive grabs for the configured combination. If the X server reports that another client already owns the combination, choose another shortcut. Reapply the setting after changing the X11 keyboard mapping.
- **Wayland:** Tasklane uses the desktop's [Global Shortcuts portal](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.GlobalShortcuts.html). The desktop may prompt you to approve or change the shortcut. Settings show the effective shortcut reported by the desktop. A missing portal, declined request, or empty grant leaves the shortcut unavailable; Tasklane does not fall back to XWayland or monitor keyboard input.

## Desktop verification

Unit tests cover registration, cleanup, conflict and cancellation handling, activation routing, and D-Bus wire signatures without connecting to a desktop. They do not establish whether an installed IDE will receive an actual key press or whether a compositor will raise its dialog.

On X11 and on a Wayland desktop that implements the portal, enable Global Quick Add in an installed IDE and switch to a different application. Press the shortcut, confirm that the dialog appears in the intended IDE project, and confirm that holding it does not create duplicate dialogs. Change the shortcut, then disable it, and confirm that old bindings stop responding. On Wayland, also try declining the prompt and confirm the IDE-local action still works. Verify native focus separately: the desktop may restrict foreground activation even when it delivers the shortcut.
