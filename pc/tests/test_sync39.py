"""Testy sync39 na kopii danych 39player (oryginalna baza, ustawienia i playlisty nie są zmieniane).

    cd pc && python -m unittest discover -s tests -v

Wymaga folderu 39player (SYNC39_PLAYER39_DIR albo domyślnie D:\\Claude Projects\\39player) z PyQt6 i mutagen."""
from __future__ import annotations

import gzip
import json
import os
import shutil
import sqlite3
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)

PLAYER_DIR = os.environ.get("SYNC39_PLAYER39_DIR", r"D:\Claude Projects\39player")


@unittest.skipUnless(os.path.isdir(os.path.join(PLAYER_DIR, "player39")), "brak 39player")
class Sync39Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from sync39 import p39
        cls.tmp = tempfile.mkdtemp(prefix="sync39-test-")
        cls.data = p39.copy_data(PLAYER_DIR)
        cfg_path = os.path.join(cls.data, "config.json")
        with open(cfg_path, encoding="utf-8-sig") as f:
            cfg = json.load(f)
        # playlisty na kopii – import zapisuje do plików m3u8
        pl_src = cfg.get("folder_playlist")
        cls.playlists = os.path.join(cls.tmp, "playlisty")
        shutil.copytree(pl_src, cls.playlists)
        cfg["folder_playlist"] = cls.playlists
        with open(cfg_path, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False)
        cls.lib, cls.cfg = p39.load_library(PLAYER_DIR, cls.data)
        cls.sync_dir = os.path.join(cls.tmp, "39sync")
        os.makedirs(cls.sync_dir)

    @classmethod
    def tearDownClass(cls):
        cls.lib.con.close()
        shutil.rmtree(cls.tmp, ignore_errors=True)
        shutil.rmtree(cls.data, ignore_errors=True)

    def _rel(self, tid):
        from sync39.snapshot import Rel
        t = self.lib.tracks[tid]
        return Rel(self.lib).of(t.path, t.pathkey)

    def test_import_and_export(self):
        from sync39 import inbox, snapshot
        lib = self.lib
        best = lib.playlist_contains("playlista_najlepsze")
        liked_only = next(i for i, t in lib.tracks.items() if t.loved and i not in best and i not in lib.unlistened)
        plain = next(i for i, t in lib.tracks.items() if not t.loved and i not in best and i not in lib.unlistened)
        played = next(i for i in lib.tracks if i not in (liked_only, plain))
        unl = next(iter(lib.unlistened), None)
        before_plays = lib.tracks[played].play_count or 0
        pl = next(r for r in lib.playlist_names() if not r.startswith("_"))
        events = [
            {"seq": 1, "ts": 1794940000.0, "type": "heart", "track": plain, "rel": self._rel(plain), "level": 2},
            {"seq": 2, "ts": 1794940001.0, "type": "heart", "track": liked_only, "rel": self._rel(liked_only), "level": 0},
            {"seq": 3, "ts": 1794940002.0, "type": "play", "track": played, "rel": self._rel(played), "played_ms": 120000,
             "counted": True},
            {"seq": 4, "ts": 1794940003.0, "type": "pl_add", "playlist": pl, "tracks": [plain], "rels": [self._rel(plain)]},
            {"seq": 5, "ts": 1794940004.0, "type": "pl_create", "playlist": "Z telefonu test.m3u8"},
            {"seq": 6, "ts": 1794940005.0, "type": "pl_add", "playlist": "Z telefonu test.m3u8", "tracks": [played],
             "rels": [self._rel(played)]},
            # ID nieaktualne, ale ścieżka poprawna – utwór znajdowany po ścieżce
            {"seq": 7, "ts": 1794940006.0, "type": "pl_add", "playlist": pl, "tracks": [999999999], "rels": [self._rel(played)]},
            {"seq": 8, "ts": 1794940007.0, "type": "pl_add", "playlist": "_NAJLEPSZE.m3u8", "tracks": [played]},
        ]
        if unl is not None:
            events.append({"seq": 9, "ts": 1794940008.0, "type": "mark", "track": unl, "rel": self._rel(unl),
                           "best": 0, "liked": 1, "listened": 1})
        dev = "testdevice01"
        d = os.path.join(self.sync_dir, "inbox", dev)
        os.makedirs(d)
        with open(os.path.join(d, "j-0000000001-0000000009.json"), "w", encoding="utf-8") as f:
            json.dump({"format": 1, "device": dev, "device_name": "Test", "events": events}, f)
        with open(os.path.join(d, "j-0000000002-0000000002.json.tmp"), "w", encoding="utf-8") as f:
            f.write("{niedokończony")                      # plik w trakcie zapisu – pomijany

        applied = inbox.run(lib, self.sync_dir, log=lambda *a: None)
        self.assertEqual(applied[dev]["seq"], events[-1]["seq"])
        self.assertEqual(lib.heart_level(plain, pending=False), 2)
        self.assertEqual(lib.heart_level(liked_only, pending=False), 0)
        self.assertEqual(lib.tracks[played].play_count, before_plays + 1)
        self.assertIn(plain, lib.playlist_ids(pl)[0])
        self.assertIn(played, lib.playlist_ids(pl)[0])
        self.assertTrue(os.path.exists(os.path.join(self.playlists, "Z telefonu test.m3u8")))
        self.assertIn(played, lib.playlist_ids("Z telefonu test.m3u8")[0])
        self.assertNotIn(played, lib.playlist_contains("playlista_najlepsze"))   # playlisty systemowej nie zmieniamy
        if unl is not None:
            self.assertEqual(lib.marks_of(unl), (0, 1, 1))
        self.assertTrue(os.path.exists(os.path.join(d, "done", "j-0000000001-0000000009.json")))
        self.assertTrue(os.path.exists(os.path.join(d, "j-0000000002-0000000002.json.tmp")))

        # ponowne uruchomienie – nic do zrobienia, te same zdarzenia nie są stosowane drugi raz
        shutil.copy(os.path.join(d, "done", "j-0000000001-0000000009.json"), d)
        inbox.run(lib, self.sync_dir, log=lambda *a: None)
        self.assertEqual(lib.tracks[played].play_count, before_plays + 1)

        # eksport: migawka zawiera zmiany i potwierdzenie dla telefonu
        out = os.path.join(self.tmp, "snap.db")
        stats = snapshot.build(lib, self.cfg, out, inbox.load_applied(self.sync_dir), log=lambda *a: None)
        self.assertGreater(stats["tracks"], 0)
        con = sqlite3.connect(out)
        self.assertEqual(con.execute("SELECT heart FROM track WHERE id=?", (plain,)).fetchone()[0], 2)
        self.assertEqual(con.execute("SELECT heart FROM track WHERE id=?", (liked_only,)).fetchone()[0], 0)
        self.assertEqual(con.execute("SELECT seq FROM applied WHERE device=?", (dev,)).fetchone()[0], events[-1]["seq"])
        self.assertEqual(con.execute("SELECT value FROM info WHERE key='format'").fetchone()[0], "1")
        # klucze albumów bez „/” na końcu
        self.assertEqual(con.execute("SELECT COUNT(*) FROM track WHERE album_key LIKE '%/'").fetchone()[0], 0)
        # ścieżki względne z „/” i bez litery dysku
        self.assertEqual(con.execute("SELECT COUNT(*) FROM track WHERE rel LIKE '%\\%' OR rel LIKE '_:%'").fetchone()[0], 0)
        con.close()

    def test_gzip_roundtrip_and_art_key(self):
        from sync39 import snapshot
        a = snapshot.art_key("vocaloid/! single", "Plot 0")
        b = snapshot.art_key("vocaloid/! single", "plot  0")
        self.assertEqual(a, b)                             # wielkość liter i spacje nie zmieniają okładki
        self.assertNotEqual(a, snapshot.art_key("vocaloid/! single", "Inny singiel"))
        raw = b"x" * 1000
        self.assertEqual(gzip.decompress(gzip.compress(raw)), raw)


if __name__ == "__main__":
    unittest.main()
