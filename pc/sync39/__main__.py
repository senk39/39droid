"""Polecenia sync39 (uruchamiane z folderu pc/):

    python -m sync39 export [--no-art] [--art-limit N]   migawka biblioteki i okładki → NAS (bezpieczne w trakcie
                                                           pracy 39player – działa na kopii bazy)
    python -m sync39 export --out D:\\39droid.db.gz        sam plik biblioteki do skopiowania na telefon (bez NAS)
    python -m sync39 import [--dry-run] [--force]         zmiany z telefonu → 39player (przy zamkniętym 39player;
                                                           działający 39player stosuje je sam co 30 s)
    python -m sync39 run                                  import → uruchom 39player → po zamknięciu export
    python -m sync39 status                               stan folderu synchronizacji
    python -m sync39 mirror [--dry-run [--list]] [--confirm]   lustro folderu muzyki PC → NAS (dokładna kopia)
    python -m sync39 watch                                w tle: lustro po każdej zmianie, eksport dla telefonu,
                                                           zmiany z telefonu przy zamkniętym 39player
    python -m sync39 setup --sync-dir … --music-dir …     wspólne ustawienia (%USERPROFILE%\\.39sync\\sync39.json)
    python -m sync39 autostart [--start] [--remove]       sync39 w tle po zalogowaniu (Harmonogram zadań)
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
    except RuntimeError as e:                                  # blokada: zmiany stosuje właśnie 39player
        log(f"Pominięto: {e}")
        return 3
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


def cmd_mirror(cfg, args) -> int:
    """Jeden przebieg lustra PC → NAS (to samo, co robi sync39 w tle po każdej zmianie)."""
    from . import daemon, mirror
    src = daemon.music_source(cfg)
    dst = (cfg.get("music_dir") or "").strip()
    if not src or not dst:
        raise SystemExit("Ustaw „music_dir” (i ewentualnie „music_src”) – python -m sync39 setup")
    log(f"Lustro {src} → {dst}" + (" (próba – bez zmian)" if args.dry_run else ""))
    p, _stats = mirror.run_once(
        src, dst, settings.state_file("mirror-index.json"),
        delete=bool(cfg.get("mirror_delete", True)) and not args.no_delete,
        max_ratio=float(cfg.get("mirror_max_delete_ratio", 0.02)),
        min_guard=int(cfg.get("mirror_min_delete_guard", 50)),
        threads=int(cfg.get("mirror_threads", 4)), dry_run=args.dry_run, confirmed=args.confirm, log=log)
    if args.dry_run and args.list:
        for o, n in p.renames[:200]:
            log(f"  PRZENIEŚ {o} → {n}")
        for r in p.copies[:200]:
            log(f"  KOPIUJ   {r}")
        for r in p.deletions[:200]:
            log(f"  USUŃ     {r}")
    if p.guarded and not args.dry_run:
        log("Usuwanie wstrzymane przez zabezpieczenie. Sprawdź listę (--dry-run --list) i potwierdź: "
            "python -m sync39 mirror --confirm")
    return 0


def cmd_watch(cfg, args) -> int:
    from . import daemon
    return daemon.main(console=True)


def cmd_setup(cfg, args) -> int:
    """Zapisuje wspólne ustawienia (%USERPROFILE%\\.39sync\\sync39.json) – czyta je też 39player."""
    cfg["_path"] = settings.SHARED_PATH
    for k in ("player39_dir", "sync_dir", "music_dir", "music_src"):
        v = getattr(args, k)
        if v is not None:
            cfg[k] = v
    settings.save(cfg)
    log(f"Zapisano {settings.SHARED_PATH}:")
    for k in ("player39_dir", "music_src", "music_dir", "sync_dir"):
        log(f"  {k} = {cfg.get(k) or '(auto)'}")
    return 0


TASK = os.environ.get("SYNC39_TASK", "39sync")


def _ps(script: str) -> int:
    return subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script], check=False).returncode


def _psq(s: str) -> str:
    return s.replace("'", "''")


def cmd_autostart(cfg, args) -> int:
    """Zadanie Harmonogramu zadań uruchamiające sync39 w tle po zalogowaniu (bez okna) – tylko dla bieżącego
    użytkownika, więc bez uprawnień administratora (schtasks /SC ONLOGON ich wymaga)."""
    if args.remove:
        rc = _ps(f"Unregister-ScheduledTask -TaskName '{_psq(TASK)}' -Confirm:$false -ErrorAction Stop")
        log("Usunięto zadanie autostartu." if rc == 0 else "Nie udało się usunąć zadania (może nie istnieje).")
        return rc
    exe = sys.executable
    w = os.path.join(os.path.dirname(exe), "pythonw.exe")
    run = w if os.path.exists(w) else exe
    pyw = os.path.join(settings.HERE, "39sync.pyw")
    script = (
        r'$u = "$env:USERDOMAIN\$env:USERNAME"; '
        f"$a = New-ScheduledTaskAction -Execute '{_psq(run)}' -Argument '\"{_psq(pyw)}\"' "
        f"-WorkingDirectory '{_psq(settings.HERE)}'; "
        "$t = New-ScheduledTaskTrigger -AtLogOn -User $u; $t.Delay = 'PT1M'; "
        "$p = New-ScheduledTaskPrincipal -UserId $u -LogonType Interactive -RunLevel Limited; "
        "$s = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries "
        "-ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew; "
        f"Register-ScheduledTask -TaskName '{_psq(TASK)}' -Action $a -Trigger $t -Principal $p -Settings $s "
        "-Description 'sync39 w tle: lustro muzyki na NAS, biblioteka dla 39droid' -Force -ErrorAction Stop | Out-Null"
    )
    rc = _ps(script)
    if rc != 0:
        log("Nie udało się zarejestrować zadania autostartu.")
        return rc
    log(f"Zadanie „{TASK}” uruchomi sync39 w tle po każdym zalogowaniu (z minutowym opóźnieniem).")
    if args.start:
        _ps(f"Start-ScheduledTask -TaskName '{_psq(TASK)}'")
        log("Uruchomiono teraz.")
    return 0


def _utf8_output() -> None:
    """Wyjście przekierowane do pliku albo do sync39 w tle – UTF-8 (strona kodowa konsoli Windows nie ma np. „→”);
    w konsoli – znaki spoza jej strony kodowej zastępowane, zamiast przerywać polecenie."""
    for stream in (sys.stdout, sys.stderr):
        if stream is None:
            continue
        try:
            if stream.isatty():
                stream.reconfigure(errors="replace")
            else:
                stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError, OSError):
            pass


def main(argv=None) -> int:
    _utf8_output()
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
    for name in ("mirror", "music"):
        m = sub.add_parser(name, help="lustro folderu muzyki PC → NAS (dokładna kopia)")
        m.add_argument("--dry-run", action="store_true", help="tylko pokaż, co zostałoby zrobione")
        m.add_argument("--list", action="store_true", help="z --dry-run: wypisz pliki")
        m.add_argument("--confirm", action="store_true", help="potwierdź usunięcie wstrzymane przez zabezpieczenie")
        m.add_argument("--no-delete", action="store_true", help="tylko kopiuj i przenoś, bez usuwania")
    sub.add_parser("watch", help="działaj w tle: lustro, eksport dla telefonu, zmiany z telefonu")
    s = sub.add_parser("setup", help="zapisz wspólne ustawienia (folder NAS, muzyka)")
    s.add_argument("--player39-dir", dest="player39_dir")
    s.add_argument("--sync-dir", dest="sync_dir", help=r"np. \\192.168.1.20\personal_folder\39sync")
    s.add_argument("--music-dir", dest="music_dir", help=r"kopia na NAS, np. \\192.168.1.20\personal_folder\Music")
    s.add_argument("--music-src", dest="music_src", help=r"folder muzyki na PC (puste – z 39player, np. F:\MUZYKA)")
    a = sub.add_parser("autostart", help="uruchamiaj sync39 w tle po zalogowaniu (Harmonogram zadań)")
    a.add_argument("--remove", action="store_true", help="usuń zadanie autostartu")
    a.add_argument("--start", action="store_true", help="po utworzeniu uruchom od razu")
    args = ap.parse_args(argv)
    cfg = settings.load(args.config)
    return {"export": cmd_export, "import": cmd_import, "run": cmd_run, "status": cmd_status,
            "mirror": cmd_mirror, "music": cmd_mirror, "watch": cmd_watch, "setup": cmd_setup,
            "autostart": cmd_autostart}[args.cmd](cfg, args)


if __name__ == "__main__":
    sys.exit(main())
