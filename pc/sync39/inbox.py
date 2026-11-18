"""Zmiany z telefonu (<sync_dir>/inbox/<urządzenie>/*.json).

Stosowanie zmian to kod 39player (player39/droid_link.py) – ten sam przy działającym 39player (robi to okno
programu co 30 s) i przy zamkniętym (sync39 import, sync39 w tle). Zastosowane numery zdarzeń trafiają do
<sync_dir>/state/applied.json, a stamtąd do migawki – telefon wie wtedy, które jego zmiany są już w danych z PC.
Tu: ścieżki i odczyt stanu bez wczytywania biblioteki (demon)."""
from __future__ import annotations

import glob
import json
import os


def state_path(sync_dir: str) -> str:
    return os.path.join(sync_dir, "state", "applied.json")


def load_applied(sync_dir: str) -> dict:
    try:
        with open(state_path(sync_dir), encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def pending(sync_dir: str) -> dict[str, list[str]]:
    """{urządzenie: [pliki dziennika]} – nieprzetworzone pliki."""
    out = {}
    for d in sorted(glob.glob(os.path.join(sync_dir, "inbox", "*"))):
        if os.path.isdir(d):
            files = sorted(glob.glob(os.path.join(d, "*.json")))
            if files:
                out[os.path.basename(d)] = files
    return out


def run(lib, sync_dir: str, dry_run: bool = False, log=print) -> dict:
    """Stosuje wszystkie oczekujące zmiany (39player zamknięty). Zwraca zaktualizowane potwierdzenia."""
    try:
        from player39 import droid_link
    except ImportError as e:
        raise SystemExit(f"39player bez modułu droid_link – zaktualizuj 39player ({e})")
    return droid_link.apply_all(lib, sync_dir, dry_run=dry_run, log=log)
