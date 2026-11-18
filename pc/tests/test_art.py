"""Miniatury okładek: eksport wielowątkowy z częstym zapisem postępu (foldery tymczasowe, bez biblioteki 39player)."""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import sys
import tempfile
import unittest
from types import SimpleNamespace

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)

PLAYER_DIR = os.environ.get("SYNC39_PLAYER39_DIR", r"D:\Claude Projects\39player")

try:
    import PIL  # noqa: F401
    HAVE_PIL = True
except ImportError:
    HAVE_PIL = False


@unittest.skipUnless(HAVE_PIL and os.path.isdir(os.path.join(PLAYER_DIR, "player39")), "brak Pillow albo 39player")
class ArtExportTest(unittest.TestCase):
    def setUp(self):
        if PLAYER_DIR not in sys.path:
            sys.path.insert(0, PLAYER_DIR)                          # player39.tags – okładki z tagów i folderu
        from PIL import Image

        from sync39 import art
        self.art = art
        self.tmp = tempfile.mkdtemp(prefix="sync39-art-")
        self.sync = os.path.join(self.tmp, "39sync")
        tracks, self.arts = {}, {}
        for i in range(120):
            d = os.path.join(self.tmp, "music", f"Album {i:03d}")
            os.makedirs(d)
            Image.new("RGB", (64, 64), (i * 2 % 256, 80, 160)).save(os.path.join(d, "folder.jpg"), "JPEG")
            p = os.path.join(d, "01.flac")
            with open(p, "wb") as f:
                f.write(b"\0" * 16)                                 # bez okładki w tagach – okładka z folder.jpg
            tracks[i] = SimpleNamespace(path=p, pathkey=p.lower(), disc_no=0, track_no=1, mtime=1_700_000_000)
            self.arts[hashlib.sha1(str(i).encode()).hexdigest()] = (d, [i])
        self.lib = SimpleNamespace(tracks=tracks)
        # duży istniejący indeks (jak przy tysiącach albumów) – zapis trwa dłużej, niż wątki potrzebują na dopisanie
        self.existing = {f"{n:040x}": 1 for n in range(30000)}
        os.makedirs(os.path.join(self.sync, "art"))
        with open(os.path.join(self.sync, "art", "index.json"), "w", encoding="utf-8") as f:
            json.dump(self.existing, f)
        self.old_every = art.SAVE_EVERY
        art.SAVE_EVERY = 1                     # postęp po każdej okładce – wątki dopisują do indeksu w trakcie zapisu

    def tearDown(self):
        self.art.SAVE_EVERY = self.old_every
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_export_with_frequent_progress_saves(self):
        stats = self.art.export(self.lib, self.sync, self.arts, size=32, log=lambda *a: None)
        self.assertEqual((stats["written"], stats["errors"]), (120, 0))
        with open(os.path.join(self.sync, "art", "index.json"), encoding="utf-8") as f:
            self.assertEqual(set(json.load(f)), set(self.arts) | set(self.existing))
        for key in self.arts:
            self.assertTrue(os.path.exists(self.art.art_path(self.sync, key)))
        again = self.art.export(self.lib, self.sync, self.arts, size=32, log=lambda *a: None)
        self.assertEqual(again["todo"], 0)                            # przyrostowo – nic do zrobienia


if __name__ == "__main__":
    unittest.main()
