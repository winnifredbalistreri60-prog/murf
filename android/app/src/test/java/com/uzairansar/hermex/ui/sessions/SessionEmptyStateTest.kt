package com.uzairansar.hermex.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionEmptyStateTest {
    @Test fun failureMustNotLookLikeFirstUseOrNoResults() {
        assertEquals(SessionEmptyState.Unavailable, sessionEmptyState(true, true, true, true, true))
    }
    @Test fun pendingSearchMustNotClaimThereAreNoMatches() {
        assertEquals(SessionEmptyState.Searching, sessionEmptyState(false, true, true, false, false))
    }
    @Test fun filtersTakePriorityOverArchiveAndScheduledEmptyCopy() {
        assertEquals(SessionEmptyState.Filtered, sessionEmptyState(false, false, true, true, true))
    }
    @Test fun distinguishArchiveScheduledAndFirstUse() {
        assertEquals(SessionEmptyState.Archived, sessionEmptyState(false, false, false, true, false))
        assertEquals(SessionEmptyState.Scheduled, sessionEmptyState(false, false, false, false, true))
        assertEquals(SessionEmptyState.New, sessionEmptyState(false, false, false, false, false))
    }
}
