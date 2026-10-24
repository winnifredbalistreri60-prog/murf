"""Gate logic for screen/start: unsupported hosts must not look like success."""
import importlib.util
import os
import sys
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent


def load_relay():
    # Importing phone_relay starts servers only in __main__; safe to load module pieces via exec of helpers.
    # We test _screen_desktop_blocker by importing after stubbing ThreadingHTTPServer bind — module binds on main only.
    sys.path.insert(0, str(HERE))
    spec = importlib.util.spec_from_file_location("phone_relay_under_test", HERE / "phone_relay.py")
    mod = importlib.util.module_from_spec(spec)
    # prevent accidental listen if __main__ — not triggered
    spec.loader.exec_module(mod)
    return mod


class ScreenGateTest(unittest.TestCase):
    def test_blocker_no_xvnc(self):
        mod = load_relay()
        with mock.patch.object(mod.shutil, "which", return_value=None):
            with mock.patch.object(mod.os, "uname", return_value=mock.Mock(machine="x86_64")):
                with mock.patch.object(mod.os.path, "isdir", return_value=True):
                    with mock.patch.object(mod.os.path, "isfile", return_value=True):
                        err = mod._screen_desktop_blocker()
        self.assertIsNotNone(err)
        self.assertIn("TigerVNC", err)

    def test_blocker_arm(self):
        mod = load_relay()
        with mock.patch.object(mod.os, "uname", return_value=mock.Mock(machine="aarch64")):
            with mock.patch.object(mod.os.path, "isfile", return_value=True):
                err = mod._screen_desktop_blocker()
        self.assertIsNotNone(err)
        self.assertIn("aarch64", err)
        self.assertIn("не поддерживается", err)

    def test_screen_desktop_sets_supported_false(self):
        mod = load_relay()
        with mock.patch.object(mod, "_screen_desktop_blocker", return_value="экран агента требует TigerVNC"):
            res = mod.screen_desktop("start")
        self.assertFalse(res.get("ok"))
        self.assertIs(res.get("supported"), False)
        self.assertIn("TigerVNC", res["error"])


if __name__ == "__main__":
    unittest.main()
