"""Polecenia sync39 (uruchamiane z folderu pc/):

    python -m sync39 export [--no-art] [--art-limit N]   migawka biblioteki i okładki → NAS (bezpieczne w trakcie
                                                           pracy 39player – działa na kopii bazy)
    python -m sync39 export --out D:\\39droid.db.gz        sam plik biblioteki do skopiowania na telefon (bez NAS)
    python -m sync39 import [--dry-run] [--force]         zmiany z telefonu → 39player (39player musi być zamknięty)
    python -m sync39 run                                  import → uruchom 39player → po zamknięciu export
    python -m sync39 status                               stan folderu synchronizacji
    python -m sync39 music [--dry-run] [--purge]          kopiowanie nowych/zmienionych plików muzyki na NAS (robocopy)
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

from . import FORMAT, VERSION, art, inbox, p39, settings, snapshot


def log(*a):
    print(*a, flush=True)


def _atomic_write(path: str, data: bytes) -> None:
    tmp = path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def export_to_file(cfg, out: str) -> int:
    """Sam plik biblioteki (.db.gz) do skopiowania na telefon – bez NAS (39droid: Wczytaj bibliotekę z pliku)."""
    pdir = cfg["player39_dir"]
    if os.path.isdir(out):
        out = os.path.join(out, f"39droid-biblioteka-{time.strftime('%Y%m%d-%H%M')}.db.gz")
    elif not out.lower().endswith(".gz"):
        out += ".gz"
    sync_dir = (cfg.get("sync_dir") or "").strip()
    applied = inbox.load_applied(sync_dir) if sync_dir and os.path.isdir(sync_dir) else {}
    log(f"Eksport biblioteki 39player → {out}")
    data = p39.copy_data(pdir)
    lib = None
    work = tempfile.mkdtemp(prefix="sync39-out-")
    try:
        lib, pcfg = p39.load_library(pdir, data)
        db = os.path.join(work, "library.db")
        snapshot.build(lib, pcfg, db, applied, log)
        tmp = out + ".tmp"
        with open(db, "rb") as src, gzip.open(tmp, "wb", compresslevel=6) as dst:
            shutil.copyfileobj(src, dst, 1 << 20)
        os.replace(tmp, out)
        log(f"  zapisano {out} ({os.path.getsize(out) / 1e6:.1f} MB) – skopiuj go na telefon i w 39droid wybierz "
            f"„Wczytaj z pliku”")
    finally:
        if lib is not None:
            lib.con.close()
        shutil.rmtree(work, ignore_errors=True)
        shutil.rmtree(data, ignore_errors=True)
    return 0


def cmd_export(cfg, args) -> int:
    if args.out:
        return export_to_file(cfg, args.out)
    sync_dir = settings.require_sync_dir(cfg)
    pdir = cfg["player39_dir"]
    t0 = time.time()
    log(f"Eksport biblioteki 39player → {sync_dir}")
    data = p39.copy_data(pdir)
    lib = None
    try:
        lib, pcfg = p39.load_library(pdir, data)
        applied = inbox.load_applied(sync_dir)
        work = tempfile.mkdtemp(prefix="sync39-out-")
        try:
            db = os.path.join(work, "library.db")
            stats = snapshot.build(lib, pcfg, db, applied, log)
            stamp = time.strftime("%Y%m%d-%H%M%S")
            name = f"library-{stamp}.db.gz"
            gz = os.path.join(work, name)
            with open(db, "rb") as src, gzip.open(gz, "wb", compresslevel=6) as dst:
                shutil.copyfileobj(src, dst, 1 << 20)
            raw = open(gz, "rb").read()
            snap_dir = os.path.join(sync_dir, "snapshot")
            os.makedirs(snap_dir, exist_ok=True)
            _atomic_write(os.path.join(snap_dir, name), raw)
            manifest = {
                "format": FORMAT, "generator": f"sync39 {VERSION}", "created": time.time(),
                "snapshot": f"snapshot/{name}", "size": len(raw), "db_size": os.path.getsize(db),
                "sha256": hashlib.sha256(raw).hexdigest(), "tracks": stats["tracks"],
                "applied": {k: v.get("seq", 0) for k, v in applied.items()},
            }
            _atomic_write(os.path.join(sync_dir, "manifest.json"),
                          json.dumps(manifest, ensure_ascii=False, indent=1).encode("utf-8"))
            log(f"  opublikowano {name} ({len(raw) / 1e6:.1f} MB)")
            keep = max(1, int(cfg.get("keep_snapshots", 2)))
            olds = sorted(x for x in os.listdir(snap_dir) if x.startswith("library-") and x.endswith(".db.gz"))
            for x in olds[:-keep]:
                try:
                    os.remove(os.path.join(snap_dir, x))
                except OSError:
                    pass
            if not args.no_art:
                art.export(lib, sync_dir, stats["arts"], int(cfg.get("art_size", 512)),
                           int(cfg.get("art_quality", 82)), args.refresh_art, args.art_limit, log)
        finally:
            shutil.rmtree(work, ignore_errors=True)
    finally:
        if lib is not None:
            lib.con.close()
        shutil.rmtree(data, ignore_errors=True)
    log(f"Gotowe w {time.time() - t0:.0f} s.")
    return 0


def _guard_running(force: bool) -> None:
    procs = p39.running_instances()
    if procs and not force:
        log("39player jest uruchomiony – zamknij go przed importem zmian z telefonu:")
        for p in procs:
            log("   ", p[:160])
        raise SystemExit(2)


def cmd_import(cfg, args) -> int:
    sync_dir = settings.require_sync_dir(cfg)
    if not inbox.pending(sync_dir):
        log("Brak zmian z telefonu.")
        return 0
    _guard_running(args.force or args.dry_run)
    log("Import zmian z telefonu → 39player" + (" (próba – bez zapisu)" if args.dry_run else ""))
    lib, _pcfg = p39.load_library(cfg["player39_dir"])
    try:
        inbox.run(lib, sync_dir, args.dry_run, log)
    finally:
        try:
            lib.con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        except Exception:                                      # noqa: BLE001
            pass
        lib.con.close()
    return 0


def cmd_run(cfg, args) -> int:
    sync_dir = settings.require_sync_dir(cfg)
    if inbox.pending(sync_dir) and not p39.running_instances():
        # osobny proces – zamyka bazę przed startem 39player
        subprocess.run([sys.executable, "-m", "sync39", "import"], cwd=settings.HERE, check=False)
    cmd = p39.launch_command(cfg)
    log("Uruchamiam 39player:", " ".join(cmd))
    subprocess.run(cmd, cwd=cfg["player39_dir"], check=False)
    log("39player zamknięty – eksport dla telefonu.")
    return subprocess.run([sys.executable, "-m", "sync39", "export"], cwd=settings.HERE, check=False).returncode


def cmd_status(cfg, args) -> int:
    sync_dir = settings.require_sync_dir(cfg)
    try:
        with open(os.path.join(sync_dir, "manifest.json"), encoding="utf-8") as f:
            m = json.load(f)
        log(f"Migawka: {m['snapshot']} z {time.strftime('%Y-%m-%d %H:%M', time.localtime(m['created']))}, "
            f"{m['tracks']} utworów, {m['size'] / 1e6:.1f} MB")
    except (OSError, ValueError, KeyError):
        log("Brak migawki – uruchom: python -m sync39 export")
    for dev, st in inbox.load_applied(sync_dir).items():
        log(f"Urządzenie {st.get('name') or dev}: zastosowano zmiany do #{st.get('seq')} "
            f"({time.strftime('%Y-%m-%d %H:%M', time.localtime(st.get('ts') or 0))})")
    for dev, files in inbox.pending(sync_dir).items():
        log(f"Oczekuje z {dev}: {len(files)} plików dziennika")
    procs = p39.running_instances()
    log("39player: " + ("uruchomiony" if procs else "zamknięty"))
    return 0


def cmd_music(cfg, args) -> int:
    dst = (cfg.get("music_dir") or "").strip()
    if not dst:
        raise SystemExit("Ustaw „music_dir” (kopia biblioteki na NAS) w sync39.json")
    p39.add_path(cfg["player39_dir"])
    data = p39.copy_data(cfg["player39_dir"])
    try:
        with open(os.path.join(data, "config.json"), encoding="utf-8-sig") as f:
            roots = json.load(f).get("foldery_muzyki") or []
    finally:
        shutil.rmtree(data, ignore_errors=True)
    if len(roots) != 1:
        raise SystemExit(f"Polecenie „music” obsługuje jeden folder muzyki (jest: {roots})")
    src = roots[0]
    cmd = ["robocopy", src, dst, "/E", "/XO", "/FFT", "/R:1", "/W:2", "/MT:8", "/NP", "/NDL",
           "/XF", "*.tmp", "desktop.ini", "Thumbs.db"]
    if args.purge:
        cmd.append("/PURGE")                                  # usuwa z NAS pliki, których nie ma na PC
    if args.dry_run:
        cmd.append("/L")
    log("Kopiowanie muzyki na NAS:", " ".join(f'"{c}"' if " " in c else c for c in cmd))
    rc = subprocess.run(cmd, check=False).returncode
    # robocopy: 0–7 oznacza sukces (1 – skopiowano pliki), ≥8 – błędy
    log("Gotowe." if rc < 8 else f"robocopy zgłosił błędy (kod {rc})")
    return 0 if rc < 8 else rc


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="sync39", description="Synchronizacja 39player ↔ 39droid przez NAS")
    ap.add_argument("--config", help="ścieżka sync39.json")
    sub = ap.add_subparsers(dest="cmd", required=True)
    e = sub.add_parser("export", help="migawka biblioteki i okładki → NAS")
    e.add_argument("--no-art", action="store_true", help="bez miniatur okładek")
    e.add_argument("--out", help="zapisz tylko plik biblioteki (.db.gz) do podanej ścieżki lub folderu – bez NAS")
    e.add_argument("--refresh-art", action="store_true", help="przelicz wszystkie miniatury")
    e.add_argument("--art-limit", type=int, default=None, help="najwyżej tyle nowych miniatur (test)")
    i = sub.add_parser("import", help="zmiany z telefonu → 39player")
    i.add_argument("--dry-run", action="store_true", help="tylko pokaż, co zostałoby zastosowane")
    i.add_argument("--force", action="store_true", help="nie sprawdzaj, czy 39player działa (niezalecane)")
    sub.add_parser("run", help="import → 39player → export")
    sub.add_parser("status", help="stan synchronizacji")
    m = sub.add_parser("music", help="kopiuj nowe/zmienione pliki muzyki na NAS (robocopy)")
    m.add_argument("--dry-run", action="store_true", help="tylko lista (robocopy /L)")
    m.add_argument("--purge", action="store_true", help="usuń z NAS pliki, których nie ma już na PC")
    args = ap.parse_args(argv)
    cfg = settings.load(args.config)
    return {"export": cmd_export, "import": cmd_import, "run": cmd_run, "status": cmd_status,
            "music": cmd_music}[args.cmd](cfg, args)


if __name__ == "__main__":
    sys.exit(main())
