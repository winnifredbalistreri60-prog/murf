#!/usr/bin/env python3
"""Тест «Сообщить о проблеме»: вычистка секретов и сохранение."""
import json, os, stat, sys, tempfile, unittest

os.environ["MURF_REPORTS_DIR"] = tempfile.mkdtemp()
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import murf_reports as R  # noqa: E402


class ReportsTest(unittest.TestCase):
    def test_redact(self):
        raw = ("Authorization: Bearer abcdefghijklmnop123\npassword=Hunter2pass token: 'xyz123456789' "  # gitleaks:allow (fake test value)
               "https://agent.example.net/phone/api/x 203.0.113.48:2223 user@mail.ru ghp_ABCDEFGHIJKLMNOPQRSTUV "
               "sk-proj-AAAAAAAAAAAAAAAAAAAA agent.example.net hermes_session=deadbeefdeadbeefdeadbeefdeadbeef1")
        out = R.redact(raw)
        for bad in ("abcdefghijklmnop123", "Hunter2pass", "xyz123456789", "example.net", "203.0.113", "user@mail.ru",
                    "ghp_ABC", "sk-proj", "deadbeef"):
            self.assertNotIn(bad, out, out)
        self.assertIn("<host>", out)

    def test_save_and_list(self):
        res = R.save_report({"description": "Чат завис, пароль=qwerty123", "log": "E/Chat: boom at https://x.example.com/a",
                             "device": {"app_version": "1.7.0-murf", "model": "vivo"}})
        self.assertTrue(res["ok"])
        p = os.path.join(R.REPORTS_DIR, res["id"] + ".json")
        rep = json.load(open(p))
        self.assertNotIn("qwerty123", rep["description"])
        self.assertNotIn("x.example.com", rep["log"])
        self.assertEqual(stat.S_IMODE(os.stat(p).st_mode), 0o600)
        self.assertTrue(os.path.exists(os.path.join(R.REPORTS_DIR, res["id"] + ".md")))
        self.assertEqual(R.list_reports()[-1]["id"], res["id"])

    def test_empty_and_rate(self):
        with self.assertRaises(R.ReportError):
            R.save_report({"description": " ", "log": ""})
        R._rate[:] = [__import__("time").time()] * R.RATE_N
        with self.assertRaises(R.ReportError) as c:
            R.save_report({"description": "x"})
        self.assertEqual(c.exception.status, 429)
        R._rate.clear()


if __name__ == "__main__":
    unittest.main()
