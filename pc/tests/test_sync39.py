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
from pathlib import Path

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)

PLAYER_DIR = os.environ.get("SYNC39_PLAYER39_DIR", r"D:\Claude Projects\39player")

# Zapytania, którymi 39droid nakłada plik stanu na pełną bibliotekę – wspólny plik protocol/apply-state.sql (ten sam
# tekst ma Protocol.APPLY_STATE w 39droid; zgodność sprawdza ProtocolTest). Test sprawdza, że wynik jest taki sam jak
# nowa pełna biblioteka.
PROTOCOL_DIR = os.path.join(os.path.dirname(HERE), "protocol")


def apply_state_sql() -> list[str]:
    with open(os.path.join(PROTOCOL_DIR, "apply-state.sql"), encoding="utf-8") as f:
        return [ln.strip().rstrip(";") for ln in f if ln.strip() and not ln.startswith("--")]


PHONE_APPLY_STATE = apply_state_sql()


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
        from player39 import droid_link                     # blokada zmian z telefonu – nie w ~/.39sync
        droid_link.SHARED_DIR = os.path.join(cls.tmp, "shared")
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
        from player39 import droid_link                     # …a telefon dostaje powód (state/rejected-<urządzenie>.json)
        self.assertEqual([x["seq"] for x in droid_link.load_rejected(self.sync_dir, dev)], [8])
        # numer zastosowanych zmian także w bazie biblioteki – eksport bierze go z kopii bazy (pierwszeństwo przed
        # applied.json), inne urządzenia – z applied.json
        from sync39.__main__ import with_db_applied
        self.assertEqual(droid_link.db_applied(lib.con)[dev], events[-1]["seq"])
        merged = with_db_applied(lib, {dev: {"seq": 2, "name": "Test"}, "inne": {"seq": 7}})
        self.assertEqual(merged[dev], {"seq": events[-1]["seq"], "name": "Test"})
        self.assertEqual(merged["inne"], {"seq": 7})
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

    def test_publish_full_then_state_only(self):
        """Pierwszy eksport – pełna biblioteka i stan; bez zmian – nic; zmiana serduszek / potwierdzeń – tylko mały
        plik stanu. Stan nałożony na starą pełną bibliotekę (tak jak robi to telefon) daje nową bibliotekę."""
        import time
        from sync39 import snapshot
        from sync39.__main__ import publish
        quiet = lambda *x: None                            # noqa: E731
        sync = os.path.join(self.tmp, "pub-sync")
        os.makedirs(sync)
        work = os.path.join(self.tmp, "pub-work")
        os.makedirs(work)
        a, b, c = (os.path.join(self.tmp, f"pub-{x}.db") for x in "abc")
        snapshot.build(self.lib, self.cfg, a, {"dev": {"seq": 3}}, log=quiet)
        self.assertEqual(publish(sync, a, work, 1, {"dev": {"seq": 3}}, {}, log=quiet), "full")
        m1 = json.loads(Path(sync, "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(m1["state_base"], m1["sha256"])
        self.assertTrue(os.path.isfile(os.path.join(sync, m1["state"])))
        self.assertLess(m1["state_size"], m1["size"] / 5)

        time.sleep(1.1)                                    # inna chwila utworzenia i inna nazwa pliku
        snapshot.build(self.lib, self.cfg, b, {"dev": {"seq": 3}}, log=quiet)
        con = sqlite3.connect(b)                           # nowa wersja 39player / sync39 – treść ta sama
        con.execute("UPDATE info SET value = 'nowa' WHERE key IN ('player39_version', 'generator')")
        con.commit()
        con.close()
        self.assertEqual(publish(sync, b, work, 1, {"dev": {"seq": 3}}, {}, log=quiet), "none")

        snapshot.build(self.lib, self.cfg, c, {"dev": {"seq": 4}}, log=quiet)
        con = sqlite3.connect(c)                           # serduszko zmienione na PC
        tid = con.execute("SELECT id FROM track WHERE heart = 0 LIMIT 1").fetchone()[0]
        con.execute("UPDATE track SET heart = 2, play_count = play_count + 5, last_played = 1794950000 WHERE id = ?",
                    (tid,))
        con.execute("UPDATE node SET likes = likes + 1 WHERE id IN (SELECT node_id FROM node_track WHERE track_id = ?)",
                    (tid,))
        con.commit()
        con.close()
        self.assertEqual(publish(sync, c, work, 1, {"dev": {"seq": 4}}, {}, log=quiet), "state")
        m2 = json.loads(Path(sync, "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(m2["snapshot"], m1["snapshot"])   # pełna biblioteka ta sama
        self.assertEqual(m2["sha256"], m1["sha256"])
        self.assertNotEqual(m2["state"], m1["state"])
        self.assertEqual(m2["state_base"], m1["sha256"])
        self.assertEqual(m2["applied"], {"dev": 4})

        # telefon: kopia starej pełnej biblioteki + nowy stan (te same zapytania co SyncManager.applyState w 39droid)
        phone = os.path.join(self.tmp, "pub-phone.db")
        shutil.copy(a, phone)
        state = os.path.join(self.tmp, "pub-state.db")
        with gzip.open(os.path.join(sync, m2["state"])) as src, open(state, "wb") as dst:
            shutil.copyfileobj(src, dst)
        con = sqlite3.connect(phone)
        con.execute("ATTACH DATABASE ? AS st", (state,))
        with con:
            for q in PHONE_APPLY_STATE:
                con.execute(q)
        con.execute("DETACH DATABASE st")
        con.close()
        self.assertEqual(snapshot.fingerprint(phone), snapshot.fingerprint(c))

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
