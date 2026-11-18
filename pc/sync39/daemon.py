"""sync39 w tle (autostart po zalogowaniu): lustro muzyki PC → NAS, eksport biblioteki dla telefonu i – gdy 39player
jest zamknięty – zmiany z telefonu.

  * lustro: Windows powiadamia o zmianach w folderze muzyki; przebieg rusza po 20 s ciszy (np. po skopiowaniu
    albumu albo „Zastosuj dane z VocaDB”), dodatkowo co 30 min pełne porównanie,
  * eksport: po zmianie biblioteki 39player (baza, playlisty) – najczęściej co `export_min_interval` s; po
    zastosowaniu zmian z telefonu szybciej (telefon czeka na potwierdzenie),
  * zmiany z telefonu przy zamkniętym 39player – tu; przy działającym – stosuje je sam 39player.
Stan: ~/.39sync/status.json (czyta go 39player – pasek stanu, menu Narzędzia → Telefon), dziennik: sync39.log
w tym samym folderze. Kopia stanu dla telefonu: <sync_dir>/state/pc-status.json (39droid ostrzega, gdy PC długo się
nie odzywa, eksport się nie udaje albo lustro czeka na potwierdzenie usunięcia). Usunięcia wstrzymane przez zabezpieczenie: lista w pending-deletions.txt, potwierdzenie
z 39player (confirm-deletions.json) albo python -m sync39 mirror --confirm."""
from __future__ import annotations

import json
import logging
import logging.handlers
import os
import socket
import subprocess
import sys
import threading
import time

from . import VERSION, inbox, mirror, p39, settings

log = logging.getLogger("sync39")
_status: dict = {}
_status_lock = threading.Lock()


def setup_logging(console: bool) -> None:
    log.setLevel(logging.INFO)
    h = logging.handlers.RotatingFileHandler(settings.state_file("sync39.log"), maxBytes=1_000_000, backupCount=3,
                                             encoding="utf-8")
    h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    log.addHandler(h)
    if console:
        c = logging.StreamHandler(sys.stdout)
        c.setFormatter(logging.Formatter("%(asctime)s %(message)s", "%H:%M:%S"))
        log.addHandler(c)


def set_status(**kw) -> None:
    with _status_lock:
        _status.update(kw)
        _status["ts"] = time.time()
        _status["pid"] = os.getpid()
        tmp = settings.state_file("status.json.tmp")
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(_status, f, ensure_ascii=False)
            os.replace(tmp, settings.state_file("status.json"))
        except OSError:
            pass


# ---------------------------------------------------------------------- pojedyncza instancja
_mutex = None


def single_instance() -> bool:
    global _mutex
    if os.name != "nt":
        return True
    import ctypes
    _mutex = ctypes.windll.kernel32.CreateMutexW(None, False, "Local\\39sync-daemon")
    return ctypes.windll.kernel32.GetLastError() != 183          # ERROR_ALREADY_EXISTS


# ---------------------------------------------------------------------- powiadomienia o zmianach w folderze
class ChangeWatcher:
    def __init__(self, path: str):
        self.path = path
        self.h = None
        if os.name == "nt":
            import ctypes
            from ctypes import wintypes
            k32 = ctypes.windll.kernel32
            k32.FindFirstChangeNotificationW.restype = wintypes.HANDLE
            k32.FindFirstChangeNotificationW.argtypes = [wintypes.LPCWSTR, wintypes.BOOL, wintypes.DWORD]
            k32.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
            k32.FindNextChangeNotification.argtypes = [wintypes.HANDLE]
            k32.FindCloseChangeNotification.argtypes = [wintypes.HANDLE]
            self.k32 = k32
            flags = 0x1 | 0x2 | 0x8 | 0x10                       # nazwy plików i folderów, rozmiar, zapis
            h = k32.FindFirstChangeNotificationW(path, True, flags)
            if h and h != wintypes.HANDLE(-1).value:
                self.h = h

    def wait(self, timeout_s: float) -> bool:
        """True – w folderze coś się zmieniło przed upływem czasu."""
        if self.h is None:
            time.sleep(timeout_s)
            return False
        r = self.k32.WaitForSingleObject(self.h, int(timeout_s * 1000))
        if r == 0:
            self.k32.FindNextChangeNotification(self.h)
            return True
        return False

    def close(self):
        if self.h is not None:
            self.k32.FindCloseChangeNotification(self.h)
            self.h = None


# ---------------------------------------------------------------------- pomocnicze
def unc_host(path: str) -> str | None:
    p = path.replace("/", "\\")
    if p.startswith("\\\\"):
        return p[2:].split("\\", 1)[0]
    return None


def reachable(path: str, timeout: float = 2.0) -> bool:
    host = unc_host(path)
    if host is None:
        return os.path.isdir(path)
    try:
        with socket.create_connection((host, 445), timeout=timeout):
            return True
    except OSError:
        return False


def run_cli(*args: str, timeout: int = 3600) -> int:
    """Polecenie sync39 w osobnym procesie (pamięć biblioteki 39player wraca do systemu po zakończeniu)."""
    exe = sys.executable
    w = os.path.join(os.path.dirname(exe), "pythonw.exe")
    if os.path.exists(w):
        exe = w
    flags = 0x08000000 if os.name == "nt" else 0                 # bez okna konsoli
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    out = subprocess.run([exe, "-m", "sync39", *args], cwd=settings.HERE, capture_output=True, text=True,
                         encoding="utf-8", errors="replace", timeout=timeout, creationflags=flags, env=env)
    for line in (out.stdout or "").splitlines()[-15:]:
        log.info("  %s", line)
    if out.returncode:
        for line in (out.stderr or "").splitlines()[-15:]:
            log.warning("  %s", line)
    return out.returncode


def library_signature(cfg: dict) -> float:
    """Najpóźniejsza zmiana danych 39player istotnych dla telefonu (baza, ustawienia, playlisty)."""
    pdir = cfg["player39_dir"]
    data = p39.data_dir_of(pdir)
    sig = 0.0
    for name in ("library.db", "library.db-wal", "config.json"):
        try:
            sig = max(sig, os.path.getmtime(os.path.join(data, name)))
        except OSError:
            pass
    try:
        with open(os.path.join(data, "config.json"), encoding="utf-8-sig") as f:
            pl = json.load(f).get("folder_playlist") or ""
        if pl and os.path.isdir(pl):
            for dirpath, _d, names in os.walk(pl):
                for n in names:
                    if n.lower().endswith((".m3u8", ".m3u")):
                        sig = max(sig, os.path.getmtime(os.path.join(dirpath, n)))
    except (OSError, ValueError):
        pass
    return sig


def music_source(cfg: dict) -> str:
    src = (cfg.get("music_src") or "").strip()
    if src:
        return src
    data = p39.data_dir_of(cfg["player39_dir"])
    try:
        with open(os.path.join(data, "config.json"), encoding="utf-8-sig") as f:
            roots = json.load(f).get("foldery_muzyki") or []
        return roots[0] if roots else ""
    except (OSError, ValueError):
        return ""


CONFIRM_FILE = "confirm-deletions.json"
PENDING_FILE = "pending-deletions.txt"
EXPORT_TIMEOUT_S = 3 * 3600          # pierwszy eksport tworzy miniatury wszystkich okładek
EXPORT_RETRY_S = 300                 # po nieudanym eksporcie – nie częściej (każda próba kopiuje bazę 39player)
HEARTBEAT_S = 30                     # znak życia w status.json (39player uznaje sync39 za działający do 180 s)
PC_STATUS_FILE = "pc-status.json"    # stan dla telefonu w <sync_dir>/state/
PC_STATUS_EVERY_S = 300              # odświeżenie na NAS co tyle s (od razu – gdy zmieni się stan, eksport, lustro)


def take_confirmation() -> int | None:
    """Potwierdzenie usunięcia z 39player: liczba plików, które widział użytkownik (plik jest zużywany)."""
    path = settings.state_file(CONFIRM_FILE)
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        return None
    try:
        os.remove(path)
    except OSError:
        pass
    if time.time() - float(data.get("ts") or 0) > 3600:
        log.info("Pominięto stare potwierdzenie usunięcia")
        return None
    return int(data.get("count") or 0)


# ---------------------------------------------------------------------- pętla główna
class Daemon:
    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.src = music_source(cfg)
        self.dst = (cfg.get("music_dir") or "").strip()
        self.sync_dir = (cfg.get("sync_dir") or "").strip()
        self.stop = threading.Event()
        self.last_export = 0.0
        self.exported_sig = 0.0
        self.seen_sig = library_signature(cfg)
        self.sig_changed_at = 0.0
        self.applied_mtime = self._applied_mtime()
        self.force_export = True                                   # pierwszy eksport po starcie
        self.export_retry_at = 0.0
        self.last_full_mirror = 0.0
        self.started = time.time()
        self._pc_key = None
        self._pc_at = 0.0

    def _applied_mtime(self) -> float:
        if not self.sync_dir:
            return 0.0
        try:
            return os.path.getmtime(inbox.state_path(self.sync_dir))
        except OSError:
            return 0.0

    # ------------------------------------------------------------------ lustro
    def _run_mirror(self, dry_run: bool = False, confirmed: bool = False):
        return mirror.run_once(
            self.src, self.dst, settings.state_file("mirror-index.json"),
            delete=bool(self.cfg.get("mirror_delete", True)),
            max_ratio=float(self.cfg.get("mirror_max_delete_ratio", 0.02)),
            min_guard=int(self.cfg.get("mirror_min_delete_guard", 50)),
            threads=int(self.cfg.get("mirror_threads", 4)), dry_run=dry_run, confirmed=confirmed,
            log=lambda s: log.info(s), status=lambda **kw: set_status(**kw), stop=self.stop.is_set)

    def mirror_cycle(self, confirmed_count: int | None = None) -> None:
        """Przebieg lustra. `confirmed_count` – użytkownik potwierdził usunięcie tylu plików (z 39player)."""
        if not (self.src and self.dst):
            return
        if not reachable(self.dst):
            set_status(state="offline", message="NAS niedostępny – lustro czeka")
            return
        set_status(state="mirror", message="Porównywanie folderu muzyki z NAS…")
        try:
            confirmed = False
            if confirmed_count is not None:
                plan, _ = self._run_mirror(dry_run=True)
                confirmed = len(plan.deletions) <= confirmed_count
                if not confirmed:
                    log.warning("Do usunięcia jest %s plików, potwierdzono %s – czekam na nowe potwierdzenie",
                                len(plan.deletions), confirmed_count)
            p, stats = self._run_mirror(confirmed=confirmed)
            self.last_full_mirror = time.time()
            if p.guarded:
                self._write_pending(p.deletions)
                set_status(state="waiting_confirmation", pending_deletions=len(p.deletions), reason=p.reason,
                           message=f"Lustro: {len(p.deletions)} plików do usunięcia z NAS czeka na potwierdzenie "
                                   f"({p.reason}) – 39player: Narzędzia → Telefon albo python -m sync39 mirror "
                                   f"--confirm")
                log.warning("Usuwanie wstrzymane: %s", p.reason)
            else:
                if confirmed:
                    log.info("Usunięcie potwierdzone – usunięto %s plików z NAS", stats.get("deleted", 0))
                self._write_pending([])
                set_status(state="idle", pending_deletions=0, reason="", last_mirror=time.time(),
                           message=f"Lustro aktualne (skopiowano {stats.get('copied', 0)}, "
                                   f"przeniesiono {stats.get('renamed', 0)}, usunięto {stats.get('deleted', 0)})")
        except Exception as e:                                     # noqa: BLE001 – demon działa dalej
            log.exception("Lustro: %s", e)
            set_status(state="error", message=f"Lustro: {e}")

    def _write_pending(self, rels: list[str]) -> None:
        path = settings.state_file(PENDING_FILE)
        try:
            if not rels:
                if os.path.exists(path):
                    os.remove(path)
                return
            with open(path + ".tmp", "w", encoding="utf-8") as f:
                f.write(f"# sync39: {len(rels)} plików do usunięcia z {self.dst} – nie ma ich już w {self.src}\n")
                f.write("# Potwierdzenie: 39player → Narzędzia → Telefon (39droid) albo python -m sync39 mirror "
                        "--confirm\n\n")
                for r in rels:
                    f.write(r + "\n")
            os.replace(path + ".tmp", path)
        except OSError as e:
            log.warning("Lista plików do usunięcia: %s", e)

    # ------------------------------------------------------------------ telefon
    def phone_cycle(self) -> None:
        if not self.sync_dir or not reachable(self.sync_dir):
            return
        try:
            if inbox.pending(self.sync_dir) and not p39.running_instances():
                log.info("Zmiany z telefonu (39player zamknięty) – import")
                run_cli("import")
        except Exception as e:                                     # noqa: BLE001
            log.exception("Import zmian z telefonu: %s", e)
        now = time.time()
        sig = library_signature(self.cfg)
        if sig != self.seen_sig:
            self.seen_sig = sig
            self.sig_changed_at = now
        applied = self._applied_mtime()
        ack_needed = applied > self.applied_mtime
        quiet = now - self.sig_changed_at >= 45
        due = now - self.last_export >= float(self.cfg.get("export_min_interval", 600))
        if now < self.export_retry_at:
            return
        if self.force_export or ack_needed or (sig > self.exported_sig and quiet and due):
            set_status(state="export", message="Eksport biblioteki dla telefonu…")
            log.info("Eksport biblioteki dla telefonu")
            try:
                rc = run_cli("export", timeout=int(self.cfg.get("export_timeout", EXPORT_TIMEOUT_S)))
            except Exception as e:                                 # noqa: BLE001 – np. przekroczony czas; demon działa dalej
                log.exception("Eksport dla telefonu: %s", e)
                rc = -1
            self.last_export = time.time()
            if rc == 0:
                self.exported_sig = sig
                self.applied_mtime = applied
                self.force_export = False
                self.export_retry_at = 0.0
                set_status(state="idle", last_export=self.last_export, message="Biblioteka dla telefonu aktualna")
            else:
                self.export_retry_at = self.last_export + EXPORT_RETRY_S
                set_status(state="error", message="Eksport dla telefonu nie powiódł się – ponowna próba za "
                                                  f"{EXPORT_RETRY_S // 60} min, zob. sync39.log")

    # ------------------------------------------------------------------ stan dla telefonu
    def pc_status(self) -> dict:
        """Stan sync39 dla 39droid (bez ścieżek PC i postępu – tylko to, co telefon pokazuje)."""
        with _status_lock:
            st = dict(_status)
        pending_export = self.seen_sig > self.exported_sig or self.force_export
        return {
            "format": 1, "generator": f"sync39 {VERSION}", "ts": time.time(), "started": self.started,
            "state": st.get("state") or "", "message": st.get("message") or "", "reason": st.get("reason") or "",
            "pending_deletions": int(st.get("pending_deletions") or 0),
            "last_mirror": st.get("last_mirror") or 0, "last_export": st.get("last_export") or 0,
            # od kiedy biblioteka 39player zmieniła się bez nowego eksportu dla telefonu (0 – eksport aktualny)
            "export_pending_since": (self.sig_changed_at or self.started) if pending_export else 0,
            "mirror": bool(self.src and self.dst),
        }

    def publish_pc_status(self, force: bool = False) -> bool:
        """<sync_dir>/state/pc-status.json – od razu po zmianie stanu, poza tym co PC_STATUS_EVERY_S."""
        if not self.sync_dir:
            return False
        st = self.pc_status()
        key = (st["state"], st["pending_deletions"], st["last_export"], st["last_mirror"],
               bool(st["export_pending_since"]))
        now = time.time()
        if not force and key == self._pc_key and now - self._pc_at < PC_STATUS_EVERY_S:
            return False
        if not reachable(self.sync_dir):
            return False
        path = os.path.join(self.sync_dir, "state", PC_STATUS_FILE)
        try:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path + ".tmp", "w", encoding="utf-8") as f:
                json.dump(st, f, ensure_ascii=False)
            os.replace(path + ".tmp", path)
        except OSError as e:
            log.warning("Stan dla telefonu (%s): %s", PC_STATUS_FILE, e)
            return False
        self._pc_key, self._pc_at = key, now
        return True

    # ------------------------------------------------------------------ pętla
    def _heartbeat(self) -> None:
        """Znak życia także w trakcie długich operacji (pierwszy skan NAS, eksport wszystkich okładek)."""
        while not self.stop.wait(HEARTBEAT_S):
            set_status()

    def run(self) -> None:
        log.info("sync39 w tle: %s → %s, telefon: %s", self.src or "(brak źródła)", self.dst or "(brak celu)",
                 self.sync_dir or "(brak)")
        set_status(state="starting", message="Start")
        threading.Thread(target=self._heartbeat, name="sync39-heartbeat", daemon=True).start()
        watcher = ChangeWatcher(self.src) if self.src and os.path.isdir(self.src) else None
        pending_change = True                                      # pierwszy przebieg po starcie
        changed_at = 0.0
        last_phone = 0.0
        try:
            while not self.stop.is_set():
                timeout = 5 if pending_change else 30
                if watcher is not None:
                    changed = watcher.wait(timeout)
                else:
                    self.stop.wait(timeout)
                    changed = False
                now = time.time()
                if changed:                                        # czekaj na ciszę (zmiany przychodzą seriami)
                    pending_change = True
                    changed_at = now
                    self.stop.wait(1)
                set_status()                                       # znak życia dla 39player
                confirmed = take_confirmation()
                if confirmed is not None:
                    log.info("39player: potwierdzono usunięcie %s plików z NAS", confirmed)
                    pending_change = False
                    self.mirror_cycle(confirmed_count=confirmed)
                elif (pending_change and now - changed_at >= 20) or now - self.last_full_mirror > 1800:
                    pending_change = False
                    self.mirror_cycle()
                if time.time() - last_phone >= 30:
                    last_phone = time.time()
                    self.phone_cycle()
                self.publish_pc_status()
        finally:
            self.stop.set()                                        # koniec znaku życia
            if watcher is not None:
                watcher.close()
            set_status(state="stopped", message="Zatrzymano")
            try:
                self.publish_pc_status(force=True)
            except Exception:                                      # noqa: BLE001 – zamykanie
                pass


def main(console: bool = True) -> int:
    setup_logging(console)
    if not single_instance():
        log.info("sync39 w tle już działa – kończę")
        return 0
    cfg = settings.load()
    if not (cfg.get("music_dir") or cfg.get("sync_dir")):
        log.error("Brak ustawień – uruchom: python -m sync39 setup")
        return 2
    Daemon(cfg).run()
    return 0
