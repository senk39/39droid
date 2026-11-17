"""Zmiany z telefonu (<sync_dir>/inbox/<urządzenie>/*.json) stosowane w 39player.

Każde zdarzenie ma numer `seq` rosnący w obrębie urządzenia. Zastosowane numery są zapisane w
<sync_dir>/state/applied.json i trafiają do migawki – telefon wie wtedy, które jego zmiany są już w danych z PC
i może przestać je nakładać lokalnie. Zdarzenia stosowane są przez API biblioteki 39player (te same reguły co
kliknięcia w programie: ♥ → _REKOMENDACJE Z PC, ♥♥ → także _NAJLEPSZE, zdjęcie serduszka usuwa z obu)."""
from __future__ import annotations

import glob
import json
import os
import shutil
import time

from . import FORMAT

KNOWN = {"heart", "mark", "play", "pl_add", "pl_remove", "pl_create"}


def state_path(sync_dir: str) -> str:
    return os.path.join(sync_dir, "state", "applied.json")


def load_applied(sync_dir: str) -> dict:
    try:
        with open(state_path(sync_dir), encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def save_applied(sync_dir: str, applied: dict) -> None:
    p = state_path(sync_dir)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    tmp = p + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(applied, f, ensure_ascii=False, indent=1)
    os.replace(tmp, p)


def pending(sync_dir: str) -> dict[str, list[str]]:
    """{urządzenie: [pliki dziennika]} – nieprzetworzone pliki."""
    out = {}
    for d in sorted(glob.glob(os.path.join(sync_dir, "inbox", "*"))):
        if os.path.isdir(d):
            files = sorted(glob.glob(os.path.join(d, "*.json")))
            if files:
                out[os.path.basename(d)] = files
    return out


def read_events(files: list[str], after_seq: int, log=print) -> tuple[list[dict], str, list[str]]:
    """(zdarzenia o seq > after_seq posortowane, nazwa urządzenia, poprawnie odczytane pliki)."""
    events: dict[int, dict] = {}
    name, ok = "", []
    for p in files:
        try:
            with open(p, encoding="utf-8") as f:
                data = json.load(f)
        except (OSError, ValueError) as e:
            log(f"  pomijam uszkodzony plik {os.path.basename(p)}: {e}")
            continue
        if int(data.get("format", 0)) > FORMAT:
            log(f"  pomijam {os.path.basename(p)} – nowszy format ({data.get('format')}), zaktualizuj sync39")
            continue
        name = data.get("device_name") or name
        ok.append(p)
        for ev in data.get("events") or []:
            try:
                seq = int(ev["seq"])
            except (KeyError, TypeError, ValueError):
                continue
            if seq > after_seq:
                events[seq] = ev
    return [events[k] for k in sorted(events)], name, ok


class Applier:
    def __init__(self, lib, rel, dry_run: bool = False, log=print):
        self.lib = lib
        self.rel = rel
        self.dry = dry_run
        self.log = log
        self.by_rel = {}
        for t in lib.tracks.values():
            r = rel.of(t.path, t.pathkey)
            if r:
                self.by_rel[r.casefold()] = t.id
        cfg = lib.cfg
        self.special = {(cfg.get(k) or "").casefold() for k in
                        ("playlista_nieprzesluchane", "playlista_najlepsze", "playlista_polubione",
                         "playlista_polubione_tel") if cfg.get(k)}
        self.stats: dict[str, int] = {}

    def _count(self, what: str):
        self.stats[what] = self.stats.get(what, 0) + 1

    def resolve(self, tid, rel: str | None) -> int | None:
        t = self.lib.tracks.get(tid) if tid is not None else None
        if rel:
            hit = self.by_rel.get(rel.casefold())
            if t is not None:
                mine = self.rel.of(t.path, t.pathkey)
                if mine is None or mine.casefold() == rel.casefold() or hit is None:
                    return t.id
            if hit is not None:
                return hit                                     # ID zmienione (np. ponowny import) – ta sama ścieżka
        return t.id if t is not None else None

    def _ids(self, ev) -> list[int]:
        out = []
        rels = ev.get("rels") or []
        for k, tid in enumerate(ev.get("tracks") or []):
            r = self.resolve(tid, rels[k] if k < len(rels) else None)
            if r is not None and r not in out:
                out.append(r)
        return out

    def apply(self, ev: dict) -> None:
        typ = ev.get("type")
        if typ not in KNOWN:
            self._count("nieznane")
            return
        lib = self.lib
        if typ in ("heart", "mark", "play"):
            tid = self.resolve(ev.get("track"), ev.get("rel"))
            if tid is None:
                self._count("brak utworu")
                return
            if typ == "heart":
                level = max(0, min(2, int(ev.get("level", 0))))
                if not self.dry:
                    lib.set_heart([tid], level, immediate=True)
                self._count("serduszka")
            elif typ == "mark":
                if tid not in lib.unlistened:
                    self._count("oznaczenia poza listą")
                    return
                b, li, x = bool(ev.get("best")), bool(ev.get("liked")), bool(ev.get("listened"))
                if not self.dry:
                    lib.set_mark([tid], "liked", li or b, undoable=False)
                    lib.set_mark([tid], "best", b, undoable=False)
                    lib.set_mark([tid], "listened", x, undoable=False)
                self._count("oznaczenia")
            else:
                if not ev.get("counted", True):
                    return
                t = lib.tracks[tid]
                ts = float(ev.get("ts") or time.time())
                if not self.dry:
                    t.play_count = (t.play_count or 0) + 1
                    t.last_played = max(t.last_played or 0, ts)
                    lib.con.execute("UPDATE tracks SET play_count=?, last_played=? WHERE id=?",
                                    (t.play_count, t.last_played, tid))
                self._count("odtworzenia")
            return
        rel = (ev.get("playlist") or "").replace("\\", "/").strip("/")
        if not rel or ".." in rel.split("/"):
            self._count("zła playlista")
            return
        if "/" not in rel and rel.casefold() in self.special:
            self._count("pominięte (playlista systemowa)")
            return
        path = lib.playlist_path(rel)
        if path is None:
            raise SystemExit("39player nie ma ustawionego folderu playlist")
        if typ in ("pl_create", "pl_add") and not os.path.exists(path):
            folder, _, name = rel.rpartition("/")
            if not self.dry:
                if folder and not os.path.isdir(lib.playlist_path(folder)):
                    os.makedirs(lib.playlist_path(folder), exist_ok=True)
                lib.create_playlist(name, folder)
            self._count("nowe playlisty")
        if typ == "pl_add":
            ids = self._ids(ev)
            if ids and not self.dry:
                lib.add_to_playlist(rel, ids)
            self._count("dodania do playlist")
        elif typ == "pl_remove":
            ids = self._ids(ev)
            if ids and not self.dry and os.path.exists(path):
                lib.remove_from_playlist(rel, ids)
            self._count("usunięcia z playlist")


def run(lib, sync_dir: str, dry_run: bool = False, log=print) -> dict:
    """Stosuje wszystkie oczekujące zmiany. Zwraca zaktualizowane applied."""
    from .snapshot import Rel
    applied = load_applied(sync_dir)
    todo = pending(sync_dir)
    if not todo:
        log("  brak zmian z telefonu")
        return applied
    applier = Applier(lib, Rel(lib), dry_run, log)
    for dev, files in todo.items():
        st = applied.get(dev) or {}
        events, name, ok = read_events(files, int(st.get("seq", 0)), log)
        last = int(st.get("seq", 0))
        for ev in events:
            try:
                applier.apply(ev)
            except SystemExit:
                raise
            except Exception as e:                             # noqa: BLE001 – jedno złe zdarzenie nie blokuje reszty
                log(f"  błąd zdarzenia {ev.get('seq')} ({ev.get('type')}): {e}")
            last = max(last, int(ev["seq"]))
        log(f"  {name or dev}: {len(events)} zdarzeń (do #{last})")
        if dry_run:
            continue
        applied[dev] = {"seq": last, "ts": time.time(), "name": name or st.get("name") or dev}
        save_applied(sync_dir, applied)
        done = os.path.join(sync_dir, "inbox", dev, "done")
        os.makedirs(done, exist_ok=True)
        for p in ok:
            shutil.move(p, os.path.join(done, os.path.basename(p)))
        olds = sorted(glob.glob(os.path.join(done, "*.json")))
        for p in olds[:-200]:
            try:
                os.remove(p)
            except OSError:
                pass
    if applier.stats:
        log("  zastosowano: " + ", ".join(f"{k}: {v}" for k, v in applier.stats.items()))
    return applied
