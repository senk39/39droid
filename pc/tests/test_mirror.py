"""Testy lustra PC → NAS (sync39.mirror) na folderach tymczasowych."""
from __future__ import annotations

import os
import shutil
import sys
import tempfile
import time
import unittest

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)

from sync39 import mirror  # noqa: E402


def write(path: str, data: bytes, mtime: float | None = None) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)
    if mtime is not None:
        os.utime(path, (mtime, mtime))


class MirrorTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="mirror-test-")
        self.src = os.path.join(self.tmp, "PC")
        self.dst = os.path.join(self.tmp, "NAS")
        self.index = os.path.join(self.tmp, "index.json")
        os.makedirs(self.src)
        self.t = time.time() - 10_000
        for i in range(60):
            write(os.path.join(self.src, "Vocaloid", f"P{i % 6}", f"[2020.01.0{i % 9 + 1}] Album {i % 6}",
                               f"{i:02d} - Utwór {i}.mp3"), os.urandom(100 + i), self.t + i)
        write(os.path.join(self.src, "Anime", "Łódź ☆ Żółć", "01 - x.flac"), b"flac" * 50, self.t)
        write(os.path.join(self.src, "Anime", "desktop.ini"), b"[x]", self.t)
        write(os.path.join(self.src, "Anime", "nowy.mp3.tmp39"), b"tmp", self.t)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_mirror(self, **kw):
        logs = []
        p, stats = mirror.run_once(self.src, self.dst, self.index, threads=3, log=logs.append,
                                   min_guard=kw.pop("min_guard", 5), **kw)
        return p, stats

    def test_full_cycle(self):
        p, stats = self.run_mirror()
        self.assertEqual(stats["copied"], 61)
        self.assertFalse(os.path.exists(os.path.join(self.dst, "Anime", "desktop.ini")))
        self.assertFalse(os.path.exists(os.path.join(self.dst, "Anime", "nowy.mp3.tmp39")))
        f = os.path.join(self.dst, "Anime", "Łódź ☆ Żółć", "01 - x.flac")
        self.assertTrue(os.path.exists(f))
        self.assertAlmostEqual(os.path.getmtime(f), self.t, delta=2)
        # bez zmian – nic do zrobienia
        p, stats = self.run_mirror()
        self.assertTrue(p.empty)

        # zmiana nazwy pliku i folderu albumu (czas modyfikacji zachowany) – przeniesienie, bez wysyłania
        old = os.path.join(self.src, "Vocaloid", "P0", "[2020.01.01] Album 0")
        new = os.path.join(self.src, "Vocaloid", "P0", "[2020.01.01] Album 0 (VocaDB)")
        os.rename(old, new)
        os.rename(os.path.join(self.src, "Anime", "Łódź ☆ Żółć", "01 - x.flac"),
                  os.path.join(self.src, "Anime", "Łódź ☆ Żółć", "01 - X (nowa nazwa).flac"))
        p, stats = self.run_mirror()
        self.assertGreater(stats["renamed"], 1)
        self.assertEqual(stats["copied"], 0)
        self.assertEqual(stats["deleted"], 0)
        self.assertFalse(os.path.exists(os.path.join(self.dst, "Vocaloid", "P0", "[2020.01.01] Album 0")))
        self.assertTrue(os.path.isdir(os.path.join(self.dst, "Vocaloid", "P0", "[2020.01.01] Album 0 (VocaDB)")))

        # edycja tagów (nowa zawartość) – ponowne wysłanie
        target = next(os.path.join(dp, n) for dp, _d, ns in os.walk(new) for n in ns)
        write(target, b"nowe tagi" * 30)
        p, stats = self.run_mirror()
        self.assertEqual(stats["copied"], 1)

        # usunięcie pliku – znika z NAS, pusty folder też
        os.remove(target)
        p, stats = self.run_mirror()
        self.assertEqual(stats["deleted"], 1)

    def test_mass_delete_guard(self):
        self.run_mirror()
        shutil.rmtree(os.path.join(self.src, "Vocaloid", "P1"))      # 10 plików z 61 – ponad 2% i ponad próg 5
        p, stats = self.run_mirror()
        self.assertTrue(p.guarded)
        self.assertEqual(stats["deleted"], 0)
        self.assertTrue(os.path.isdir(os.path.join(self.dst, "Vocaloid", "P1")))
        # potwierdzenie
        p, stats = self.run_mirror(confirmed=True)
        self.assertEqual(stats["deleted"], 10)
        self.assertFalse(os.path.exists(os.path.join(self.dst, "Vocaloid", "P1")))

    def test_bootstrap_existing_copy(self):
        # na NAS jest już kopia z innego narzędzia (inny czas modyfikacji) i plik, którego nie ma na PC
        for dirpath, _d, names in os.walk(self.src):
            for n in names:
                if mirror.skip_file(n):
                    continue
                s = os.path.join(dirpath, n)
                d = os.path.join(self.dst, os.path.relpath(s, self.src))
                os.makedirs(os.path.dirname(d), exist_ok=True)
                shutil.copyfile(s, d)                               # bez zachowania czasu modyfikacji
        write(os.path.join(self.dst, "Vocaloid", "stary.mp3"), b"old", self.t)
        p, stats = self.run_mirror()
        self.assertEqual(stats["copied"], 0)                        # zgodne rozmiarem – bez ponownego wysyłania
        self.assertEqual(stats["deleted"], 1)
        self.assertFalse(os.path.exists(os.path.join(self.dst, "Vocaloid", "stary.mp3")))

    def test_dry_run_changes_nothing(self):
        p, stats = self.run_mirror(dry_run=True)
        self.assertEqual(len(p.copies), 61)
        self.assertEqual(stats, {})
        self.assertFalse(any(os.scandir(self.dst)) if os.path.isdir(self.dst) else False)

    def test_disconnected_source(self):
        self.run_mirror()
        shutil.rmtree(self.src)
        with self.assertRaises(RuntimeError):
            self.run_mirror()
        self.assertTrue(os.path.isdir(os.path.join(self.dst, "Vocaloid")))


if __name__ == "__main__":
    unittest.main()
