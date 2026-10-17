package com.uzairansar.hermex.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MurfRedactTest {
    @Test fun removesSecretsHostsAndEmails() {
        val raw = "Authorization: Bearer abcdefghijklmnop123\npassword=Hunter2pass token: 'xyz123456789' " + // gitleaks:allow (fake test value)
            "https://agent.example.net/phone/api/x 203.0.113.48:2223 user@mail.ru ghp_ABCDEFGHIJKLMNOPQRSTUV " +
            "sk-proj-AAAAAAAAAAAAAAAAAAAA agent.example.net hermes_session=deadbeefdeadbeefdeadbeefdeadbeef1 пароль: qwerty123 Пароль=Zz9"
        val out = MurfRedact.redact(raw)
        listOf("abcdefghijklmnop123", "Hunter2pass", "xyz123456789", "example.net", "203.0.113", "user@mail.ru",
            "ghp_ABC", "sk-proj", "deadbeef", "qwerty123", "Zz9").forEach { assertFalse("$it in $out", out.contains(it)) }
        assertTrue(out.contains("<host>"))
    }

    @Test fun keepsOrdinaryText() {
        val s = "E/ChatViewModel: stream closed after 42 ms, retry 3"
        assertTrue(MurfRedact.redact(s) == s)
    }
}
