"""Wspólne testy formatów wymienianych przez NAS (strona PC: 39player player39/droid_link.py i sync39).

Wzorcowe pliki leżą w protocol/ (katalog główny repozytorium 39droid). Te same pliki sprawdza test Kotlin
app/src/test/.../ProtocolTest.kt. Każda strona sprawdza, że:
  * to, co sama zapisuje, ma dokładnie taki kształt (klucze i typy, a gdzie się da – wartości) jak plik wzorcowy,
  * plik wzorcowy zapisany przez drugą stronę czyta poprawnie.
Zmiana formatu = zmiana pliku w protocol/ + obie strony; inaczej któryś z testów nie przejdzie.

    cd pc && python -m unittest tests.test_protocol -v
"""
from __future__ import annotations

import json
import os
import shutil
import sqlite3
import sys
import tempfile
import unittest
from types import SimpleNamespace

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)
PROTOCOL = os.path.join(os.path.dirname(HERE), "protocol")
PLAYER_DIR = os.environ.get("SYNC39_PLAYER39_DIR", r"D:\Claude Projects\39player")
ROOT = "F:\\MUZYKA"


def fixture(name: str):
    with open(os.path.join(PROTOCOL, name), encoding="utf-8") as f:
        return json.load(f)


def shape(v):
    """Kształt wartości JSON: klucze i typy (liczby całkowite i ułamkowe to jedno – „number”)."""
    if isinstance(v, dict):
        return {k: shape(x) for k, x in v.items()}
    if isinstance(v, list):
        return [shape(v[0])] if v else []
    if isinstance(v, bool):
        return "bool"
    if isinstance(v, (int, float)):
        return "number"
    if v is None:
        return "null"
    return type(v).__name__


class FakeLib:
    """Najmniejsza „biblioteka 39player” dla Applier: zapisuje wywołania zamiast zmieniać pliki."""

    def __init__(self, rels: dict[int, str], playlist_dir: str):
        from player39 import db as DB
        self.cfg = {"foldery_muzyki": [ROOT], "playlista_najlepsze": "_NAJLEPSZE.m3u8"}
        self.tracks = {}
        for tid, rel in rels.items():
            path = os.path.join(ROOT, *rel.split("/"))
            self.tracks[tid] = SimpleNamespace(id=tid, path=path, pathkey=DB.pathkey(path), title=f"T{tid}",
                                               artist="A", duration=200.0, play_count=0, last_played=None)
        self.unlistened = {1002: 1794000000.0}
        self.con = sqlite3.connect(":memory:", isolation_level=None)
        self.con.executescript(DB.SCHEMA)
        self.con.executemany("INSERT INTO tracks(id, path, pathkey) VALUES(?,?,?)",
                             [(t.id, t.path, t.pathkey) for t in self.tracks.values()])
        self.dir = playlist_dir
        self.calls: list[tuple] = []

    def heart_level(self, tid, pending=True):
        return 0

    def heart_changed_at(self, tid):
        return 0.0

    def touch_hearts(self, ids, ts=None):
        self.calls.append(("touch_hearts", list(ids), ts))

    def set_heart(self, ids, level, immediate=False):
        self.calls.append(("set_heart", list(ids), level))

    def set_mark(self, ids, kind, value, undoable=True):
        self.calls.append(("set_mark", list(ids), kind, value))

    def playlist_path(self, rel):
        return os.path.join(self.dir, *rel.split("/"))

    def create_playlist(self, name, folder=""):
        self.calls.append(("create_playlist", name, folder))
        if not name.lower().endswith((".m3u8", ".m3u")):                # jak Library.create_playlist
            name += ".m3u8"
        with open(os.path.join(self.dir, *(folder.split("/") if folder else []), name), "w") as f:
            f.write("#EXTM3U\n")

    def add_to_playlist(self, rel, ids):
        self.calls.append(("add_to_playlist", rel, list(ids)))

    def remove_from_playlist(self, rel, ids):
        self.calls.append(("remove_from_playlist", rel, list(ids)))

    def move_in_playlist(self, rel, tid, before=None):
        self.calls.append(("move_in_playlist", rel, tid, before))
        return True

    def rename_playlist_item(self, rel, new_name):
        self.calls.append(("rename_playlist_item", rel, new_name))
        parent = rel.rpartition("/")[0]
        os.rename(self.playlist_path(rel), self.playlist_path(f"{parent}/{new_name}" if parent else new_name))
        return f"{parent}/{new_name}" if parent else new_name

    def delete_playlists(self, rels):
        self.calls.append(("delete_playlists", list(rels)))
        for r in rels:
            os.remove(self.playlist_path(r))
        return len(rels), []


@unittest.skipUnless(os.path.isdir(os.path.join(PLAYER_DIR, "player39")), "brak 39player")
class ProtocolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if PLAYER_DIR not in sys.path:
            sys.path.insert(0, PLAYER_DIR)

    def setUp(self):
        from player39 import droid_link as DL
        from sync39 import settings
        self.DL = DL
        self.tmp = tempfile.mkdtemp(prefix="sync39-protocol-")
        self.old = (DL.SHARED_DIR, settings.STATE_DIR)
        DL.SHARED_DIR = os.path.join(self.tmp, "shared")
        settings.STATE_DIR = os.path.join(self.tmp, "state")
        self.sync = os.path.join(self.tmp, "39sync")
        os.makedirs(os.path.join(self.sync, "state"))
        j = fixture("journal.json")
        rels = {}
        for ev in j["events"]:
            for tid, rel in zip(ev.get("tracks") or [ev.get("track")], ev.get("rels") or [ev.get("rel")]):
                if tid is not None:
                    rels[tid] = rel
        self.pl = os.path.join(self.tmp, "playlisty")
        os.makedirs(os.path.join(self.pl, "Z telefonu"))
        with open(os.path.join(self.pl, "Ulubione.m3u8"), "w") as f:
            f.write("#EXTM3U\n")
        self.lib = FakeLib(rels, self.pl)

    def tearDown(self):
        from sync39 import settings
        self.DL.SHARED_DIR, settings.STATE_DIR = self.old
        self.lib.con.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    # ------------------------------------------------------------------ telefon → PC
    def test_journal_is_read_and_applied(self):
        """Dziennik zmian z telefonu: 39player czyta wszystkie zdarzenia i wykonuje właściwe operacje biblioteki."""
        DL = self.DL
        d = os.path.join(self.sync, "inbox", "0123456789abcdef")
        os.makedirs(d)
        shutil.copy(os.path.join(PROTOCOL, "journal.json"), os.path.join(d, "j-0000000041-0000000050.json"))
        events, name, ok = DL.read_events(DL.pending(self.sync)["0123456789abcdef"], 0)
        self.assertEqual((len(events), name, len(ok)), (10, "Galaxy A55", 1))
        self.assertTrue({e["type"] for e in events} <= DL.KNOWN)
        last, stats = DL.apply_collected(self.lib, DL.collect(self.sync), log=lambda s: None)
        self.assertEqual(last, {"0123456789abcdef": 50})
        self.assertFalse({"nieznane", "błędy", "brak utworu"} & set(stats), stats)
        self.assertEqual(DL.load_rejected(self.sync, "0123456789abcdef"), [])
        calls = [c for c in self.lib.calls if c[0] != "touch_hearts"]
        self.assertEqual(calls, [
            ("set_heart", [1001], 2),
            ("set_mark", [1002], "liked", True), ("set_mark", [1002], "best", False),
            ("set_mark", [1002], "listened", True),
            ("create_playlist", "Na drogę.m3u8", "Z telefonu"),
            ("add_to_playlist", "Z telefonu/Na drogę.m3u8", [1001]),
            ("remove_from_playlist", "Ulubione.m3u8", [1002]),
            ("move_in_playlist", "Z telefonu/Na drogę.m3u8", 1001, 1002),
            ("rename_playlist_item", "Z telefonu/Na drogę.m3u8", "W drogę.m3u8"),
            ("delete_playlists", ["Ulubione.m3u8"]),
        ])
        self.assertIn(("touch_hearts", [1001], 1795000000.5), self.lib.calls)   # chwila kliknięcia na telefonie
        self.assertEqual(self.lib.con.execute("SELECT play_count FROM tracks WHERE id=1001").fetchone()[0], 1)
        self.assertEqual(self.lib.con.execute("SELECT play_count FROM tracks WHERE id=1003").fetchone()[0], 0)

    def test_phone_state_and_command_are_read(self):
        """Stan odtwarzania i polecenie pauzy zapisane przez telefon – 39player je rozumie."""
        DL = self.DL
        st = fixture("now-phone.json")
        shutil.copy(os.path.join(PROTOCOL, "now-phone.json"), DL.now_path(self.sync, st["device"]))
        nows = DL.read_nows(self.sync)
        self.assertEqual([n["device"] for n in nows], [st["device"]])
        self.assertIn(st["repeat"], DL.REPEAT_FROM_NAME)
        self.assertEqual(DL.resume_position(st, now=st["ts"] + 10), 71000)    # gra – pozycja + 10 s
        ids, idx = DL.resolve_queue(self.lib, st)
        self.assertEqual((ids, idx), ([1003, 1001, 1002], 1))
        shutil.copy(os.path.join(PROTOCOL, "cmd-from-phone.json"), DL.cmd_path(self.sync, "pc"))
        cmd = DL.read_cmd(self.sync, "pc")
        self.assertEqual((cmd["cmd"], cmd["from"], cmd["ts"]), ("pause", "0123456789abcdef", 1795000310.5))

    # ------------------------------------------------------------------ PC → telefon
    def test_pc_state_matches_fixture(self):
        """Stan odtwarzania PC (now-pc.json) – ten sam kształt i wartości co plik wzorcowy (bez czasu i wersji)."""
        DL = self.DL
        want = fixture("now-pc.json")
        player = SimpleNamespace(queue=[1001, 1002, 1003], index=0, current=1001, shuffle=False, repeat=2,
                                 is_playing=lambda: False, mp=SimpleNamespace(position=lambda: 42000))
        t = self.lib.tracks[1001]
        t.title, t.artist, t.duration = "ゴーストルール", "DECO*27 feat. 初音ミク", 215.5
        got = DL.player_state(self.lib, player)
        self.assertEqual(shape(got), shape(want))
        for k in ("ts", "app"):
            got[k] = want[k]
        self.assertEqual(got, want)

    def test_pc_command_matches_fixture(self):
        DL = self.DL
        DL.write_cmd(self.sync, "0123456789abcdef", "pause")
        got = DL.read_cmd(self.sync, "0123456789abcdef")
        want = fixture("cmd-from-pc.json")
        self.assertEqual(shape(got), shape(want))
        self.assertEqual((got["cmd"], got["from"]), (want["cmd"], want["from"]))

    def test_rejected_matches_fixture(self):
        """Zmiany nieprzyjęte – wpis z Applier.reject i plik na NAS (flush) mają kształt pliku wzorcowego."""
        DL = self.DL
        want = fixture("rejected.json")
        events = {e["seq"]: e for e in fixture("journal.json")["events"]}
        applier = DL.Applier(self.lib, log=lambda s: None)
        for item in want["items"]:
            applier.reject(events[item["seq"]], "test", item["reason"])
        for got, exp in zip(applier.rejected, want["items"]):
            self.assertEqual(shape(got), shape(exp))
            got["at"] = exp["at"]
            self.assertEqual(got, exp)
        DL.add_pending_rejections({want["device"]: applier.rejected})
        DL.flush_rejections(self.sync)
        with open(DL.rejected_path(self.sync, want["device"]), encoding="utf-8") as f:
            self.assertEqual(json.load(f), want)

    def test_pc_status_matches_fixture(self):
        """Stan sync39 dla telefonu (pc-status.json) – te same klucze i typy co plik wzorcowy."""
        from sync39 import daemon, settings
        os.makedirs(os.path.join(self.tmp, "data"))
        cfg = dict(settings.DEFAULTS, player39_dir=self.tmp, music_src=self.tmp, music_dir=self.tmp, sync_dir=self.sync)
        d = daemon.Daemon(cfg)
        daemon.set_status(state="waiting_confirmation", message="x", reason="y", pending_deletions=20,
                          last_mirror=1795000400.0, last_export=1795000300.0)
        self.assertEqual(shape(d.pc_status()), shape(fixture("pc-status.json")))
        self.assertTrue(d.publish_pc_status(force=True))
        with open(os.path.join(self.sync, "state", daemon.PC_STATUS_FILE), encoding="utf-8") as f:
            self.assertEqual(shape(json.load(f)), shape(fixture("pc-status.json")))

    def test_manifest_matches_fixture(self):
        """manifest.json z publish() – te same klucze i typy co plik wzorcowy (czyta go telefon)."""
        from sync39 import snapshot
        from sync39.__main__ import publish
        db = os.path.join(self.tmp, "library.db")
        con = sqlite3.connect(db)
        con.executescript(snapshot.SCHEMA)
        con.execute("INSERT INTO info VALUES('created', '1')")
        con.commit()
        con.close()
        work = os.path.join(self.tmp, "work")
        os.makedirs(work)
        publish(self.sync, db, work, 0, {"0123456789abcdef": {"seq": 47}}, {}, log=lambda *a: None)
        with open(os.path.join(self.sync, "manifest.json"), encoding="utf-8") as f:
            self.assertEqual(shape(json.load(f)), shape(fixture("manifest.json")))

    def test_snapshot_schemas_match_fixture(self):
        """Schemat migawki i pliku stanu – na nich test Kotlin wykonuje zapytania telefonu (apply-state.sql)."""
        from sync39 import snapshot
        with open(os.path.join(PROTOCOL, "snapshot-schema.sql"), encoding="utf-8") as f:
            self.assertEqual(f.read().strip(), snapshot.SCHEMA.strip(),
                             "schemat migawki się zmienił – zaktualizuj protocol/snapshot-schema.sql i 39droid")
        with open(os.path.join(PROTOCOL, "state-schema.sql"), encoding="utf-8") as f:
            self.assertEqual(f.read().strip(), snapshot._state_schema().strip(),
                             "schemat pliku stanu się zmienił – zaktualizuj protocol/state-schema.sql i 39droid")


if __name__ == "__main__":
    unittest.main()
