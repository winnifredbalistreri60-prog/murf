package com.uzairansar.hermex.ui.sessions

internal enum class SessionEmptyState { Unavailable, Searching, Filtered, Archived, Scheduled, New }

internal fun sessionEmptyState(
    unavailable: Boolean,
    searching: Boolean,
    hasFilters: Boolean,
    archived: Boolean,
    scheduled: Boolean,
): SessionEmptyState = when {
    unavailable -> SessionEmptyState.Unavailable
    searching -> SessionEmptyState.Searching
    hasFilters -> SessionEmptyState.Filtered
    archived -> SessionEmptyState.Archived
    scheduled -> SessionEmptyState.Scheduled
    else -> SessionEmptyState.New
}
