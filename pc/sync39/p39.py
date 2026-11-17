"""Biblioteka 39player bez okien: import modułów player39, kopia danych, wykrywanie działającego programu."""
from __future__ import annotations

import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
from urllib.parse import quote


def add_path(player_dir: str) -> None:
    if not os.path.isdir(os.path.join(player_dir, "player39")):
        raise SystemExit(f"Nie znaleziono 39player w {player_dir} – popraw „player39_dir” w sync39.json")
    if player_dir not in sys.path:
        sys.path.insert(0, player_dir)


def data_dir_of(player_dir: str) -> str:
    """Folder danych 39player tak jak liczy go program (PLAYER39_DATA albo data\\ w folderze projektu)."""
    env = os.environ.get("PLAYER39_DATA")
    if env:
        return env
    d = os.path.join(player_dir, "data")
    if os.path.isdir(d):
        return d
    return os.path.join(os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"), "39player")


def _ro_uri(path: str) -> str:
    return "file:" + quote(path.replace("\\", "/"), safe="/:") + "?mode=ro"


def copy_data(player_dir: str) -> str:
    """Spójna kopia library.db (backup API SQLite – bezpieczna także, gdy 39player właśnie zapisuje)
    i config.json w folderze tymczasowym. Zwraca ścieżkę folderu."""
    src = data_dir_of(player_dir)
    db = os.path.join(src, "library.db")
    if not os.path.exists(db):
        raise SystemExit(f"Brak bazy biblioteki {db}")
    tmp = tempfile.mkdtemp(prefix="sync39-")
    s = sqlite3.connect(_ro_uri(db), uri=True, timeout=60)
    d = sqlite3.connect(os.path.join(tmp, "library.db"))
    try:
        s.backup(d)
    finally:
        d.close()
        s.close()
    for name in ("config.json",):
        p = os.path.join(src, name)
        if os.path.exists(p):
            shutil.copy2(p, os.path.join(tmp, name))
    return tmp


_APP = None


def load_library(player_dir: str, data_dir: str | None = None):
    """(Library, cfg) – biblioteka wczytana jak w programie (kategorie, VocaDB, playlisty).
    `data_dir` – folder danych (kopia z copy_data); None – prawdziwe dane programu."""
    global _APP
    add_path(player_dir)
    if data_dir:
        os.environ["PLAYER39_DATA"] = data_dir
    from PyQt6.QtCore import QCoreApplication
    _APP = QCoreApplication.instance() or QCoreApplication([])
    from player39 import config as C
    cfg = C.load()
    if cfg.get("_config_problem"):
        print("Uwaga (config.json):", cfg["_config_problem"])
    from player39.library import Library
    lib = Library(cfg)
    lib.load()
    return lib, cfg


def running_instances() -> list[str]:
    """Działające procesy 39player (wersja .exe albo skryptowa) – opis „PID: polecenie”."""
    if os.name != "nt":
        return []
    ps = ("Get-CimInstance Win32_Process | Where-Object { $_.Name -eq '39player.exe' -or "
          "($_.CommandLine -and ($_.CommandLine -match '39player\\.pyw' -or $_.CommandLine -match '-m\\s+player39')) } "
          "| ForEach-Object { \"$($_.ProcessId): $($_.CommandLine)\" }")
    try:
        out = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
                             capture_output=True, text=True, timeout=60, creationflags=0x08000000)
    except (OSError, subprocess.SubprocessError):
        return []
    me = str(os.getpid())
    return [ln.strip() for ln in out.stdout.splitlines() if ln.strip() and not ln.startswith(me + ":")]


def launch_command(cfg: dict) -> list[str]:
    """Polecenie uruchamiające 39player (dla „run”)."""
    cmd = cfg.get("player39_command") or []
    if cmd:
        return list(cmd)
    pdir = cfg["player39_dir"]
    pyw = os.path.join(pdir, "39player.pyw")
    if os.path.exists(pyw):
        exe = sys.executable
        w = os.path.join(os.path.dirname(exe), "pythonw.exe")
        return [w if os.path.exists(w) else exe, pyw]
    exe = os.path.join(pdir, "exe", "39player.exe")
    if os.path.exists(exe):
        return [exe]
    raise SystemExit("Nie wiem, jak uruchomić 39player – ustaw „player39_command” w sync39.json")
