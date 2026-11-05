package com.tasklane.globalquickadd

/** A fixed destination must never redirect a task to another open project. */
internal fun <T> selectGlobalQuickAddProject(projects: List<T>, fixed: String, last: String?, path: (T) -> String?): T? =
    if (fixed.isNotEmpty()) projects.firstOrNull { path(it) == fixed }
    else projects.firstOrNull { last != null && path(it) == last } ?: projects.singleOrNull()

