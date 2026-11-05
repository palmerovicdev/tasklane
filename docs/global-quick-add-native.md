# Native global shortcut adapters

Quick Add uses registered operating-system shortcuts, not keyboard hooks. The
Windows adapter registers a thread-owned `RegisterHotKey` combination with
`MOD_NOREPEAT`, receives `WM_HOTKEY` on its dedicated message-loop thread, and
unregisters it on that same thread. Windows F12 is rejected because
the operating system reserves it for debuggers. A combination already owned by
another application is reported as unavailable.

The macOS adapter uses exclusive Carbon `RegisterEventHotKey`. Registration and teardown
are dispatched to the native main dispatch queue; the Swing event-dispatch
thread is not treated as the native main thread. The Carbon handler accepts only
Tasklane's own registered hotkey ID, and native callbacks remain reachable for as
long as their native callers need them. Carbon reports
conflicts and unsupported registration as unavailable. This adapter currently
supports letter, digit, and F1–F20 key codes; F21–F24 are reported unavailable.

Both adapters have injectable native bridges for deterministic registration,
activation, conflict, timeout, and teardown tests. Those tests do not reserve a real
system shortcut. They do not prove foreground activation or native behavior on
an installed IDE: real Windows and macOS desktop verification remains required.

References: [Microsoft `RegisterHotKey`](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-registerhotkey) and [Apple Carbon Core documentation](https://developer.apple.com/documentation/coreservices/carbon_core).
