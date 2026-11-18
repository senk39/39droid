"""Ustawienia sync39.

Wspólny plik ustawień dla sync39 i 39player: %USERPROFILE%\\.39sync\\sync39.json (39player czyta z niego folder
synchronizacji z telefonem). Starsza lokalizacja pc/sync39.json jest nadal brana pod uwagę.
Stan pracy (indeks lustra, status, dziennik) – w tym samym folderze.

Nie %LOCALAPPDATA%: Python ze Sklepu Microsoft przekierowuje zapisy w AppData do własnego folderu pakietu,
więc 39player.exe nie widziałby tych plików."""
from __future__ import annotations

import json
import os

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))     # folder pc/
STATE_DIR = os.path.join(os.path.expanduser("~"), ".39sync")
SHARED_PATH = os.path.join(STATE_DIR, "sync39.json")
LEGACY_PATH = os.path.join(HERE, "sync39.json")

DEFAULTS = {
    # folder projektu 39player (tam są player39/ i data/)
    "player39_dir": r"D:\Claude Projects\39player",
    # folder synchronizacji na NAS (udział SMB widziany z PC), np. \\192.168.1.20\personal_folder\39sync
    "sync_dir": "",
    # źródło lustra – folder muzyki na PC (puste: pierwszy folder muzyki z ustawień 39player, np. F:\MUZYKA)
    "music_src": "",
    # kopia biblioteki muzyki na NAS – dokładna kopia music_src, np. \\192.168.1.20\personal_folder\Music
    "music_dir": "",
    # lustro: usuwaj z NAS pliki, których nie ma na PC (dokładna kopia)
    "mirror_delete": True,
    # zabezpieczenie: gdy w jednym przebiegu miałoby zniknąć więcej plików – czekaj na potwierdzenie
    "mirror_max_delete_ratio": 0.02,
    "mirror_min_delete_guard": 50,
    # równoległe kopiowanie plików na NAS
    "mirror_threads": 4,
    # eksport biblioteki dla telefonu: najczęściej co tyle sekund (zmiany z telefonu – szybciej)
    "export_min_interval": 600,
    # miniatury okładek: bok w pikselach i jakość JPEG
    "art_size": 512,
    "art_quality": 82,
    # ile ostatnich migawek zostawić w folderze snapshot/
    "keep_snapshots": 2,
    # jak uruchomić 39player w poleceniu „run” (puste – 39player.pyw albo exe\39player.exe z folderu projektu)
    "player39_command": [],
}


def config_path(path: str | None = None) -> str:
    if path:
        return path
    env = os.environ.get("SYNC39_CONFIG")
    if env:
        return env
    if os.path.exists(SHARED_PATH) or not os.path.exists(LEGACY_PATH):
        return SHARED_PATH
    return LEGACY_PATH


def load(path: str | None = None) -> dict:
    path = config_path(path)
    cfg = dict(DEFAULTS)
    if os.path.exists(path):
        with open(path, encoding="utf-8-sig") as f:
            cfg.update(json.load(f))
    cfg["_path"] = path
    return cfg


def save(cfg: dict) -> None:
    path = cfg.get("_path") or SHARED_PATH
    os.makedirs(os.path.dirname(path), exist_ok=True)
    clean = {k: v for k, v in cfg.items() if not k.startswith("_")}
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(clean, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def require_sync_dir(cfg: dict) -> str:
    d = (cfg.get("sync_dir") or "").strip()
    if not d:
        raise SystemExit(f"Ustaw „sync_dir” (folder synchronizacji na NAS) w {cfg['_path']} "
                         f"– np. python -m sync39 setup")
    os.makedirs(d, exist_ok=True)
    return d


def state_file(name: str) -> str:
    os.makedirs(STATE_DIR, exist_ok=True)
    return os.path.join(STATE_DIR, name)
