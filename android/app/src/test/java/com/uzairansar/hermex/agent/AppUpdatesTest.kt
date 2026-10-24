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
        // judge host is NOT allowed for 1.10.5+ APK urls (GitHub-only)
        assertNull(AppUpdates.parseManifest("""{"versionCode":204,"url":"https://judge.dredd20.ru/downloads/murf-1.10.4.apk","sha256":"$sha"}"""))
    }

    @Test fun cacheBustsManifestUrl() {
        val u = AppUpdates.manifestRequestUrl(1_700_000_000_000L)
        assertTrue(u.startsWith(AppUpdates.MANIFEST_URL))
        assertTrue(u.contains("?t=1700000000000"))
        assertTrue(AppUpdates.MANIFEST_URL.contains("github.com/winnifredbalistreri60-prog/murf"))
    }

    @Test fun versionCompareLabelShowsBothSides() {
        val remote = AppUpdates.Info(
            versionCode = 204,
            versionName = "1.10.4-murf",
            url = "https://github.com/winnifredbalistreri60-prog/murf/releases/download/v1.10.4/murf-1.10.4.apk",
            sha256 = sha,
            size = 1,
            notes = "",
        )
        val label = AppUpdates.versionCompareLabel(remote, localCode = 203, localName = "1.10.3-murf")
        assertTrue(label.contains("203"))
        assertTrue(label.contains("204"))
        assertTrue(label.contains("1.10.3-murf"))
        assertTrue(label.contains("1.10.4-murf"))
    }
}
