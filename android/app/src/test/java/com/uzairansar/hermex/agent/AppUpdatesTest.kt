package com.uzairansar.hermex.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatesTest {
    private val sha = "6e375f0453b41bf4de52a076036a955f5769149c00fffc052acc1e15eebcb987"

    @Test fun parsesValidManifest() {
        val i = AppUpdates.parseManifest("""{"versionCode":170,"versionName":"1.7.0","url":"https://github.com/winnifredbalistreri60-prog/murf/releases/download/v1.7.0/murf-1.7.0.apk","sha256":"${sha.uppercase()}","size":5,"notes":"n"}""")!!
        assertEquals(170, i.versionCode); assertEquals(sha, i.sha256); assertEquals(5L, i.size)
        assertTrue(AppUpdates.isNewer(i, 160)); assertFalse(AppUpdates.isNewer(i, 170))
    }

    @Test fun rejectsForeignHostAndBadHash() {
        assertNull(AppUpdates.parseManifest("""{"versionCode":170,"url":"https://evil.example/murf.apk","sha256":"$sha"}"""))
        assertNull(AppUpdates.parseManifest("""{"versionCode":170,"url":"http://github.com/winnifredbalistreri60-prog/murf/releases/download/v1.7.0/murf.apk","sha256":"$sha"}"""))
        assertNull(AppUpdates.parseManifest("""{"versionCode":170,"url":"https://github.com/winnifredbalistreri60-prog/murf/releases/download/v1.7.0/../x.apk","sha256":"$sha"}"""))
        assertNull(AppUpdates.parseManifest("""{"versionCode":170,"url":"https://github.com/winnifredbalistreri60-prog/murf/releases/download/v1.7.0/murf.apk","sha256":"abc"}"""))
        assertNull(AppUpdates.parseManifest("""{"versionCode":170,"url":"https://github.com.evil.com/winnifredbalistreri60-prog/murf/releases/download/murf.apk","sha256":"$sha"}"""))
        assertNull(AppUpdates.parseManifest("not json"))
    }
}
