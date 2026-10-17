#!/usr/bin/env python3
"""Тест «Взять экран»: murf_screen_lease.py против заглушки tools/bot_desktop/lease.py."""
import json, os, subprocess, sys, tempfile, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
FAKE = os.path.join(HERE, "testdata", "fake_hermes")


def run(*args, env):
    r = subprocess.run([sys.executable, os.path.join(HERE, "murf_screen_lease.py"), *args],
                       cwd=FAKE, env=env, capture_output=True, text=True)
    return json.loads(r.stdout.strip())


class LeaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.env = dict(os.environ, FAKE_LEASE=os.path.join(self.tmp, "lease.json"))

    def test_take_release(self):
        self.assertEqual(run("status", "murf-aaaa", env=self.env)["holder"], "agent")
        t = run("take", "murf-aaaa", env=self.env)
        self.assertEqual((t["holder"], t["mine"], t["viewer_id"]), ("human", True, None))
        self.assertFalse(run("release", "murf-bbbb", env=self.env)["ok"])   # чужой не снимет
        self.assertTrue(run("release", "murf-aaaa", env=self.env)["ok"])
        run("take", "murf-aaaa", env=self.env)
        self.assertEqual(run("release", "murf-bbbb", "--force", env=self.env)["holder"], "agent")

    def test_bad_viewer(self):
        self.assertFalse(run("take", "../x", env=self.env)["ok"])

    def test_unsupported(self):
        r = subprocess.run([sys.executable, os.path.join(HERE, "murf_screen_lease.py"), "status"],
                           cwd=self.tmp, env=self.env, capture_output=True, text=True)
        self.assertFalse(json.loads(r.stdout)["supported"])


if __name__ == "__main__":
    unittest.main()
