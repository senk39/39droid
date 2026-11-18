"""Lustro folderu muzyki PC → NAS: dokładna kopia, z wykrywaniem przeniesień i zabezpieczeniem przed usuwaniem.

Przebieg:
  1. skan źródła (os.scandir; rozmiar i czas modyfikacji każdego pliku),
  2. porównanie z indeksem tego, co już jest na NAS (pierwszy raz – skan NAS),
  3. przeniesienia: plik, który zniknął, i nowy plik o tym samym rozmiarze i czasie modyfikacji (np. zmiana nazwy
     w 39player) – przenoszony na NAS bez ponownego wysyłania,
  4. kopiowanie nowych i zmienionych plików (plik tymczasowy, potem podmiana; czas modyfikacji zachowany),
  5. usuwanie z NAS plików, których nie ma na PC – chyba że naraz miałoby zniknąć więcej niż `max_delete_ratio`
     plików (np. odłączony dysk): wtedy usuwanie czeka na potwierdzenie (`python -m sync39 mirror --confirm`).
Foldery, których nie dało się odczytać, nie są traktowane jak usunięte."""
from __future__ import annotations

import json
import os
import shutil
import threading
import time
import unicodedata
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field

SKIP_NAMES = {"desktop.ini", "thumbs.db", ".ds_store"}
SKIP_SUFFIXES = (".tmp", ".tmp39", ".part", ".39tmp", ".crdownload", ".partial")
SKIP_DIRS = {"$recycle.bin", "system volume information", "#recycle", "@eadir", "#snapshot", ".@__thumb"}
TMP_SUFFIX = ".39tmp"
MTIME_TOLERANCE_NS = 2_000_000_000          # 2 s (różna precyzja czasu na NTFS i w systemie plików NAS)


def key(rel: str) -> str:
    return unicodedata.normalize("NFC", rel)


def skip_file(name: str) -> bool:
    low = name.lower()
    return low in SKIP_NAMES or low.endswith(SKIP_SUFFIXES) or low.startswith("~$")


def scan(root: str) -> tuple[dict[str, tuple[int, int]], set[str]]:
    """({ścieżka względna z „/”: (rozmiar, mtime_ns)}, {foldery względne, których nie dało się odczytać})."""
    files: dict[str, tuple[int, int]] = {}
    unreadable: set[str] = set()
    stack = [""]
    while stack:
        rel = stack.pop()
        path = os.path.join(root, *rel.split("/")) if rel else root
        try:
            it = os.scandir(path)
        except OSError:
            if not rel:
                raise
            unreadable.add(rel)
            continue
        with it:
            for e in it:
                r = f"{rel}/{e.name}" if rel else e.name
                try:
                    if e.is_dir(follow_symlinks=False):
                        if e.name.lower() not in SKIP_DIRS:
                            stack.append(r)
                    elif e.is_file(follow_symlinks=False):
                        if not skip_file(e.name):
                            st = e.stat(follow_symlinks=False)
                            files[key(r)] = (st.st_size, st.st_mtime_ns)
                except OSError:
                    unreadable.add(r)
    return files, unreadable


def _under(rel: str, dirs: set[str]) -> bool:
    for d in dirs:
        if rel == d or rel.startswith(d + "/"):
            return True
    return False


@dataclass
class Plan:
    copies: list[str] = field(default_factory=list)                 # nowe i zmienione
    renames: list[tuple[str, str]] = field(default_factory=list)    # (stara ścieżka na NAS, nowa)
    deletions: list[str] = field(default_factory=list)
    guarded: bool = False                                           # usuwanie wstrzymane (zabezpieczenie)
    reason: str = ""
    copy_bytes: int = 0

    @property
    def empty(self) -> bool:
        return not (self.copies or self.renames or (self.deletions and not self.guarded))

    def summary(self) -> str:
        parts = [f"do skopiowania: {len(self.copies)} ({self.copy_bytes / 1e9:.1f} GB)",
                 f"przeniesienia: {len(self.renames)}", f"do usunięcia: {len(self.deletions)}"]
        if self.guarded:
            parts.append(f"usuwanie WSTRZYMANE – {self.reason}")
        return ", ".join(parts)


def same(a: tuple[int, int], b: tuple[int, int]) -> bool:
    return a[0] == b[0] and abs(a[1] - b[1]) <= MTIME_TOLERANCE_NS


def plan(src: dict, unreadable: set, index: dict, delete: bool, max_ratio: float, min_guard: int,
         last_count: int | None, confirmed: bool = False) -> Plan:
    p = Plan()
    new_or_changed = [r for r, v in src.items() if r not in index or not same(tuple(index[r]), v)]
    gone = [r for r in index if r not in src and not _under(r, unreadable)]
    # przeniesienia: ten sam rozmiar i czas modyfikacji (a gdy kandydatów jest kilku – ta sama nazwa pliku)
    by_sig: dict[tuple[int, int], list[str]] = {}
    for r in gone:
        by_sig.setdefault((index[r][0], index[r][1] // 1_000_000_000), []).append(r)
    moved_from: set[str] = set()
    for r in new_or_changed:
        if r in index:
            continue                                                  # zmieniony w miejscu – kopiowanie
        cands = [c for c in by_sig.get((src[r][0], src[r][1] // 1_000_000_000), []) if c not in moved_from]
        if not cands:
            continue
        name = r.rsplit("/", 1)[-1].casefold()
        best = next((c for c in cands if c.rsplit("/", 1)[-1].casefold() == name), cands[0])
        moved_from.add(best)
        p.renames.append((best, r))
    renamed_to = {n for _o, n in p.renames}
    p.copies = sorted(r for r in new_or_changed if r not in renamed_to)
    p.copy_bytes = sum(src[r][0] for r in p.copies)
    if delete:
        p.deletions = sorted(r for r in gone if r not in moved_from)
        limit = max(min_guard, int(len(index) * max_ratio))
        if not confirmed and p.deletions:
            if last_count and len(src) < last_count * (1 - max_ratio):
                p.guarded, p.reason = True, (f"na PC jest nagle {len(src)} plików zamiast {last_count} "
                                             f"– odłączony dysk?")
            elif len(p.deletions) > limit:
                p.guarded, p.reason = True, f"{len(p.deletions)} plików naraz (próg {limit})"
    return p


class Mirror:
    """Stan lustra (indeks) i wykonywanie planu."""

    def __init__(self, src_root: str, dst_root: str, index_path: str, threads: int = 4, log=print,
                 status=None):
        self.src_root = src_root
        self.dst_root = dst_root
        self.index_path = index_path
        self.threads = max(1, int(threads))
        self.log = log
        self.status = status or (lambda **kw: None)
        self.index: dict[str, list[int]] = {}
        self.meta: dict = {}
        self._lock = threading.Lock()
        self._dirty = 0
        self.load()

    # ------------------------------------------------------------------ indeks
    def load(self) -> None:
        try:
            with open(self.index_path, encoding="utf-8") as f:
                data = json.load(f)
            if data.get("src") == self.src_root and data.get("dst") == self.dst_root:
                self.index = data.get("files") or {}
                self.meta = data.get("meta") or {}
        except (OSError, ValueError):
            self.index, self.meta = {}, {}

    def save(self) -> None:
        with self._lock:
            data = {"src": self.src_root, "dst": self.dst_root, "meta": self.meta, "files": self.index}
            tmp = self.index_path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
            os.replace(tmp, self.index_path)
            self._dirty = 0

    @property
    def initialized(self) -> bool:
        return bool(self.meta.get("initialized"))

    def bootstrap_from_destination(self, src: dict, save: bool = True) -> int:
        """Pierwsze uruchomienie: indeks z tego, co już jest na NAS (pliki zgodne rozmiarem i czasem).
        `save` = False (próba): indeks tylko w pamięci – prawdziwy przebieg zbuduje go od nowa."""
        self.log(f"  pierwsze uruchomienie – skanowanie kopii na NAS ({self.dst_root})…")
        t0 = time.time()
        dst, _unreadable = scan(self.dst_root)
        by_fold = {k.casefold(): k for k in dst}
        idx: dict[str, list[int]] = {}
        matched = size_only = 0
        for r, v in src.items():
            d = dst.get(r) or dst.get(by_fold.get(r.casefold(), ""))
            if d is None:
                continue
            # kopia z innego narzędzia mogła nie zachować czasu modyfikacji – wtedy wystarcza ten sam rozmiar
            # (lepsze niż ponowne wysyłanie setek GB; kolejne zmiany na PC i tak zmienią czas modyfikacji)
            if same(v, d) or d[0] == v[0]:
                idx[r] = [v[0], v[1]]
                matched += 1
                if not same(v, d):
                    size_only += 1
        # pliki tylko na NAS – do indeksu z własnym czasem (lustro je usunie, z zabezpieczeniem)
        src_fold = {k.casefold() for k in src}
        extra = 0
        for r, v in dst.items():
            if r.casefold() not in src_fold:
                idx[r] = [v[0], v[1]]
                extra += 1
        self.index = idx
        self.meta["initialized"] = True
        if save:
            self.save()
        self.log(f"  na NAS: {len(dst)} plików, zgodnych z PC: {matched} (w tym tylko rozmiarem: {size_only}), "
                 f"tylko na NAS: {extra} ({time.time() - t0:.0f} s)")
        return matched

    # ------------------------------------------------------------------ wykonanie
    def _dst(self, rel: str) -> str:
        return os.path.join(self.dst_root, *rel.split("/"))

    def _src(self, rel: str) -> str:
        return os.path.join(self.src_root, *rel.split("/"))

    def _mark(self, rel: str, v) -> None:
        with self._lock:
            if v is None:
                self.index.pop(rel, None)
            else:
                self.index[rel] = [v[0], v[1]]
            self._dirty += 1
        if self._dirty >= 200:
            self.save()

    def _copy_one(self, rel: str, v) -> int:
        src = self._src(rel)
        dst = self._dst(rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        tmp = dst + TMP_SUFFIX
        with open(src, "rb") as fi, open(tmp, "wb") as fo:
            shutil.copyfileobj(fi, fo, 4 << 20)
        st = os.stat(src)
        os.utime(tmp, ns=(st.st_atime_ns, st.st_mtime_ns))
        os.replace(tmp, dst)
        self._mark(rel, (st.st_size, st.st_mtime_ns))
        return st.st_size

    def apply(self, p: Plan, src: dict, stop=None) -> dict:
        stats = {"renamed": 0, "copied": 0, "bytes": 0, "deleted": 0, "errors": 0}
        # 1) przeniesienia (najpierw – zwalniają nazwy i oszczędzają wysyłanie)
        for old, new in p.renames:
            if stop and stop():
                break
            try:
                d_new = self._dst(new)
                os.makedirs(os.path.dirname(d_new), exist_ok=True)
                os.replace(self._dst(old), d_new)
                self._mark(old, None)
                self._mark(new, src[new])
                stats["renamed"] += 1
                self._prune_empty(os.path.dirname(self._dst(old)))
            except OSError as e:
                stats["errors"] += 1
                self.log(f"    przeniesienie {old} → {new}: {e}")
                p.copies.append(new)                          # nie wyszło – zwykłe kopiowanie
        # 2) kopiowanie (równolegle)
        total = len(p.copies)
        done = 0
        t0 = time.time()
        if p.copies:
            with ThreadPoolExecutor(max_workers=self.threads) as ex:
                futs = {}
                for rel in p.copies:
                    if stop and stop():
                        break
                    futs[ex.submit(self._copy_one, rel, src.get(rel))] = rel
                for f in as_completed(futs):
                    rel = futs[f]
                    try:
                        stats["bytes"] += f.result()
                        stats["copied"] += 1
                    except OSError as e:
                        stats["errors"] += 1
                        if stats["errors"] <= 20:
                            self.log(f"    kopiowanie {rel}: {e}")
                    done += 1
                    if done % 50 == 0 or done == total:
                        el = max(1e-6, time.time() - t0)
                        self.status(state="copying", done=done, total=total,
                                    mb_s=round(stats["bytes"] / el / 1e6, 1))
                    if stop and stop():
                        for other in futs:
                            other.cancel()
        # 3) usuwanie (gdy nie wstrzymane)
        if p.deletions and not p.guarded:
            for rel in p.deletions:
                if stop and stop():
                    break
                path = self._dst(rel)
                try:
                    if os.path.exists(path):
                        os.remove(path)
                    self._mark(rel, None)
                    stats["deleted"] += 1
                    self._prune_empty(os.path.dirname(path))
                except OSError as e:
                    stats["errors"] += 1
                    self.log(f"    usuwanie {rel}: {e}")
        self.save()
        return stats

    def _prune_empty(self, d: str) -> None:
        """Usuwa puste foldery na NAS w górę drzewa (tylko te, których nie ma już na PC)."""
        root = os.path.normcase(os.path.abspath(self.dst_root))
        while True:
            ad = os.path.normcase(os.path.abspath(d))
            if ad == root or not ad.startswith(root):
                return
            rel = os.path.relpath(d, self.dst_root)
            if os.path.isdir(self._src(rel.replace(os.sep, "/"))):
                return
            try:
                with os.scandir(d) as it:
                    if next(it, None) is not None:
                        return
                os.rmdir(d)
            except OSError:
                return
            d = os.path.dirname(d)

    def cleanup_temp(self) -> int:
        """Pozostałości przerwanego kopiowania (*.39tmp) na NAS."""
        n = 0
        for dirpath, _dirs, names in os.walk(self.dst_root):
            for name in names:
                if name.endswith(TMP_SUFFIX):
                    try:
                        os.remove(os.path.join(dirpath, name))
                        n += 1
                    except OSError:
                        pass
        return n


def run_once(src_root: str, dst_root: str, index_path: str, *, delete: bool = True, max_ratio: float = 0.02,
             min_guard: int = 50, threads: int = 4, dry_run: bool = False, confirmed: bool = False,
             log=print, status=None, stop=None) -> tuple[Plan, dict]:
    if not os.path.isdir(src_root):
        raise RuntimeError(f"Brak folderu muzyki {src_root} – dysk odłączony?")
    if not os.path.isdir(dst_root):
        if dry_run:
            raise RuntimeError(f"Brak folderu {dst_root} na NAS")
        os.makedirs(dst_root, exist_ok=True)
    m = Mirror(src_root, dst_root, index_path, threads, log, status)
    t0 = time.time()
    src, unreadable = scan(src_root)
    if not src:
        raise RuntimeError(f"Folder {src_root} jest pusty – nic nie robię")
    log(f"  PC: {len(src)} plików ({time.time() - t0:.1f} s)" +
        (f", nieczytelne foldery: {len(unreadable)}" if unreadable else ""))
    if not m.initialized:
        m.bootstrap_from_destination(src, save=not dry_run)
    p = plan(src, unreadable, m.index, delete, max_ratio, min_guard, m.meta.get("last_count"), confirmed)
    log("  " + p.summary())
    if dry_run:
        return p, {}
    stats = m.apply(p, src, stop)
    if not p.guarded and not unreadable:
        m.meta["last_count"] = len(src)
    m.meta["last_run"] = time.time()
    m.save()
    log(f"  gotowe: skopiowano {stats['copied']} ({stats['bytes'] / 1e9:.2f} GB), przeniesiono {stats['renamed']}, "
        f"usunięto {stats['deleted']}, błędy {stats['errors']} ({time.time() - t0:.0f} s)")
    return p, stats
