"""Ustawienia sync39 (pc/sync39.json – poza repozytorium; wzór w pc/sync39.example.json)."""
from __future__ import annotations

import json
import os

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))     # folder pc/
DEFAULT_PATH = os.path.join(HERE, "sync39.json")

DEFAULTS = {
    # folder projektu 39player (tam są player39/ i data/)
    "player39_dir": r"D:\Claude Projects\39player",
    # folder synchronizacji na NAS (udział SMB widziany z PC), np. \\DXP4800PLUS\Muzyka\39sync
    "sync_dir": "",
    # kopia biblioteki muzyki na NAS (cel polecenia „music”), np. \\DXP4800PLUS\Muzyka\MUZYKA
    "music_dir": "",
    # miniatury okładek: bok w pikselach i jakość JPEG
    "art_size": 512,
    "art_quality": 82,
    # ile ostatnich migawek zostawić w folderze snapshot/
    "keep_snapshots": 2,
    # jak uruchomić 39player w poleceniu „run” (puste – 39player.pyw albo exe\39player.exe z folderu projektu)
    "player39_command": [],
}


def load(path: str | None = None) -> dict:
    path = path or os.environ.get("SYNC39_CONFIG") or DEFAULT_PATH
    cfg = dict(DEFAULTS)
    if os.path.exists(path):
        with open(path, encoding="utf-8-sig") as f:
            cfg.update(json.load(f))
    cfg["_path"] = path
    return cfg


def require_sync_dir(cfg: dict) -> str:
    d = (cfg.get("sync_dir") or "").strip()
    if not d:
        raise SystemExit(f"Ustaw „sync_dir” (folder synchronizacji na NAS) w {cfg['_path']} "
                         f"– wzór: pc/sync39.example.json")
    os.makedirs(d, exist_ok=True)
    return d
