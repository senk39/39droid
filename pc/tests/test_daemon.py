"""sync39 w tle: wstrzymane usuwanie z NAS, lista plików i potwierdzenie z 39player (foldery tymczasowe)."""
from __future__ import annotations

import json
import os
import shutil
import sys
import tempfile
import time
import unittest

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)

from sync39 import daemon, settings  # noqa: E402


class DaemonConfirmTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="sync39-daemon-")
        self.old_state = settings.STATE_DIR
        settings.STATE_DIR = os.path.join(self.tmp, "state")
        self.src = os.path.join(self.tmp, "PC")
        self.dst = os.path.join(self.tmp, "NAS")
        os.makedirs(self.dst)
        t = time.time() - 5000
        for i in range(60):
            p = os.path.join(self.src, f"A{i % 3}", f"{i:02d}.mp3")
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, "wb") as f:
                f.write(os.urandom(50 + i))
            os.utime(p, (t, t))
        cfg = dict(settings.DEFAULTS, player39_dir=self.tmp, music_src=self.src, music_dir=self.dst, sync_dir="",
                   mirror_min_delete_guard=5, mirror_threads=2)
        self.d = daemon.Daemon(cfg)

    def tearDown(self):
        settings.STATE_DIR = self.old_state
        shutil.rmtree(self.tmp, ignore_errors=True)

    def status(self):
        with open(settings.state_file("status.json"), encoding="utf-8") as f:
            return json.load(f)

    def confirm(self, count):
        with open(settings.state_file(daemon.CONFIRM_FILE), "w", encoding="utf-8") as f:
            json.dump({"ts": time.time(), "count": count}, f)

    def test_guard_list_and_confirmation(self):
        self.d.mirror_cycle()
        self.assertEqual(self.status()["state"], "idle")
        self.assertEqual(sum(len(fs) for _d, _s, fs in os.walk(self.dst)), 60)
        shutil.rmtree(os.path.join(self.src, "A1"))                  # 20 plików naraz – ponad próg
        self.d.mirror_cycle()
        st = self.status()
        self.assertEqual((st["state"], st["pending_deletions"]), ("waiting_confirmation", 20))
        with open(settings.state_file(daemon.PENDING_FILE), encoding="utf-8") as f:
            listed = [ln for ln in f.read().splitlines() if ln and not ln.startswith("#")]
        self.assertEqual(len(listed), 20)
        self.assertTrue(all(r.startswith("A1/") for r in listed))

        # potwierdzono mniej, niż jest do usunięcia – nadal czeka
        self.confirm(10)
        n = daemon.take_confirmation()
        self.assertEqual(n, 10)
        self.assertIsNone(daemon.take_confirmation())               # plik zużyty
        self.d.mirror_cycle(confirmed_count=n)
        self.assertEqual(self.status()["state"], "waiting_confirmation")
        self.assertTrue(os.path.isdir(os.path.join(self.dst, "A1")))

        # stare potwierdzenie – pomijane
        with open(settings.state_file(daemon.CONFIRM_FILE), "w", encoding="utf-8") as f:
            json.dump({"ts": time.time() - 7200, "count": 20}, f)
        self.assertIsNone(daemon.take_confirmation())

        self.confirm(20)
        self.d.mirror_cycle(confirmed_count=daemon.take_confirmation())
        st = self.status()
        self.assertEqual((st["state"], st["pending_deletions"]), ("idle", 0))
        self.assertFalse(os.path.exists(os.path.join(self.dst, "A1")))
        self.assertFalse(os.path.exists(settings.state_file(daemon.PENDING_FILE)))


if __name__ == "__main__":
    unittest.main()
