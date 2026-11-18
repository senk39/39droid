"""sync39 w tle: wstrzymane usuwanie z NAS, lista plików i potwierdzenie z 39player (foldery tymczasowe)."""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
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
        os.makedirs(os.path.join(self.tmp, "data"))                  # dane „39player” – nie prawdziwe z LOCALAPPDATA
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

    def test_pc_status_for_phone(self):
        """Stan dla telefonu w <sync_dir>/state/pc-status.json: od razu po zmianie stanu (lustro czeka na potwierdzenie,
        eksport zaległy), bez zmian – nie częściej niż co PC_STATUS_EVERY_S."""
        sync = os.path.join(self.tmp, "39sync")
        os.makedirs(sync)
        self.d.sync_dir = sync
        path = os.path.join(sync, "state", daemon.PC_STATUS_FILE)

        def pc():
            with open(path, encoding="utf-8") as f:
                return json.load(f)

        self.d.mirror_cycle()
        self.d.force_export = False
        self.assertTrue(self.d.publish_pc_status())
        st = pc()
        self.assertEqual((st["state"], st["pending_deletions"], st["export_pending_since"]), ("idle", 0, 0))
        self.assertGreater(st["last_mirror"], 0)
        self.assertAlmostEqual(st["ts"], time.time(), delta=5)
        self.assertNotIn(self.src, json.dumps(st))                   # bez ścieżek PC
        self.assertFalse(self.d.publish_pc_status())                 # bez zmian – później

        shutil.rmtree(os.path.join(self.src, "A1"))
        self.d.mirror_cycle()
        self.assertTrue(self.d.publish_pc_status())                  # zmiana stanu – od razu
        self.assertEqual((pc()["state"], pc()["pending_deletions"]), ("waiting_confirmation", 20))

        self.d.seen_sig, self.d.exported_sig, self.d.sig_changed_at = 5.0, 1.0, 1234.0
        self.assertTrue(self.d.publish_pc_status())                  # biblioteka zmieniona, eksportu brak
        self.assertEqual(pc()["export_pending_since"], 1234.0)
        self.d._pc_at -= daemon.PC_STATUS_EVERY_S + 1
        self.assertTrue(self.d.publish_pc_status())                  # co PC_STATUS_EVERY_S – znak życia

    def test_settings_reload_live(self):
        """Odstępy czasu z sync39.json – zmiana (np. z 39player) działa bez ponownego uruchomienia sync39."""
        path = os.path.join(self.tmp, "sync39.json")

        def write(data, ts):
            with open(path, "w", encoding="utf-8") as f:
                json.dump(data, f)
            os.utime(path, (ts, ts))

        self.d.cfg["_path"] = path
        self.d.cfg_mtime = 0.0
        write({"check_interval": 10, "mirror_full_interval": 600}, time.time() - 100)
        self.assertTrue(self.d.reload_settings())
        self.assertEqual((self.d.iv("check_interval"), self.d.iv("mirror_full_interval")), (10.0, 600.0))
        self.assertEqual(self.d.iv("export_quiet"), 45.0)                # brak w pliku – domyślnie
        self.assertFalse(self.d.reload_settings())                       # plik bez zmian
        write({"check_interval": "zle", "export_retry": 0}, time.time())
        self.assertTrue(self.d.reload_settings())
        self.assertEqual((self.d.iv("check_interval"), self.d.iv("export_retry")), (30.0, 1.0))  # zła – domyślna, min. 1 s

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

    def test_failed_export_keeps_running_and_backs_off(self):
        calls = []

        def timeout(*args, **kw):
            calls.append(kw.get("timeout"))
            raise subprocess.TimeoutExpired(args, kw.get("timeout"))

        self.d.sync_dir = os.path.join(self.tmp, "39sync")
        os.makedirs(self.d.sync_dir)
        old = daemon.run_cli
        daemon.run_cli = timeout
        try:
            self.d.phone_cycle()                                      # przekroczony czas nie zatrzymuje demona
            self.assertEqual(self.status()["state"], "error")
            self.assertEqual(calls, [daemon.EXPORT_TIMEOUT_S])
            self.d.phone_cycle()                                      # ponowna próba dopiero po EXPORT_RETRY_S
            self.assertEqual(len(calls), 1)
            self.assertTrue(self.d.force_export)

            self.d.export_retry_at = time.time() - 1
            daemon.run_cli = lambda *args, **kw: 0
            self.d.phone_cycle()
            self.assertEqual(self.status()["state"], "idle")
            self.assertFalse(self.d.force_export)
            self.assertEqual(self.d.export_retry_at, 0.0)
        finally:
            daemon.run_cli = old

    def test_heartbeat_during_long_operation(self):
        old = daemon.HEARTBEAT_S
        daemon.HEARTBEAT_S = 0.05
        try:
            daemon.set_status(state="export", message="długi eksport")
            ts0 = self.status()["ts"]
            t = threading.Thread(target=self.d._heartbeat)
            t.start()
            time.sleep(0.3)                                           # pętla główna zajęta – znak życia i tak jest
            self.d.stop.set()
            t.join(2)
            self.assertFalse(t.is_alive())
            st = self.status()
            self.assertGreater(st["ts"], ts0)
            self.assertEqual(st["state"], "export")
        finally:
            daemon.HEARTBEAT_S = old


if __name__ == "__main__":
    unittest.main()
