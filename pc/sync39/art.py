"""Miniatury okładek dla telefonu: <sync_dir>/art/<2 znaki>/<klucz>.jpg (przyrostowo).

Klucz to folder albumu + nazwa albumu (snapshot.art_key). Źródło: okładka wbudowana w pierwszy utwór albumu,
a gdy jej brak – plik folder.jpg / cover.jpg w folderze. Telefon pobiera miniatury leniwie (tylko oglądane
albumy) i razem z utworami zapisywanymi offline."""
from __future__ import annotations

import io
import json
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

SAVE_EVERY = 500                      # postęp (indeks) zapisywany co tyle okładek – przerwany eksport nie zaczyna od zera


def art_path(sync_dir: str, key: str) -> str:
    return os.path.join(sync_dir, "art", key[:2], key + ".jpg")


def _all_embedded(path: str) -> list[bytes]:
    """Wszystkie obrazy z tagów pliku (okładka przednia najpierw) – gdy pierwszy jest uszkodzony, kolejne."""
    try:
        import mutagen
        from mutagen.id3 import ID3
        from mutagen.mp4 import MP4
    except ImportError:
        return []
    try:
        f = mutagen.File(path)
    except Exception:                                          # noqa: BLE001
        f = None
    if f is None:                                              # np. uszkodzone ramki audio – same tagi ID3 są czytelne
        try:
            f = SimpleNamespace(tags=ID3(path))
        except Exception:                                      # noqa: BLE001
            return []
    out: list[tuple[int, bytes]] = []
    tags = getattr(f, "tags", None)
    if isinstance(f, MP4):
        out += [(3, bytes(c)) for c in (tags or {}).get("covr", [])]
    for p in getattr(f, "pictures", None) or []:
        out.append((p.type, p.data))
    if isinstance(tags, ID3):
        out += [(int(a.type), a.data) for a in tags.getall("APIC")]
    out.sort(key=lambda x: x[0] != 3)                          # 3 = okładka przednia
    return [d for _t, d in out if d]


def _sources(lib, tids):
    """Kolejne kandydatki na okładkę albumu: (dane obrazu, czas modyfikacji). Najpierw okładka, którą wybiera
    39player, potem pozostałe obrazy z tagów pierwszych utworów, na końcu plik obrazu w folderze."""
    from player39 import tags as T
    tracks = sorted((lib.tracks[i] for i in tids if i in lib.tracks),
                    key=lambda t: (t.disc_no or 0, t.track_no or 0, t.pathkey))
    seen: set[int] = set()
    for t in tracks[:3]:
        try:
            first = T.embedded_artwork(t.path)
        except Exception:                                      # noqa: BLE001
            first = None
        for data in ([first] if first else []) + _all_embedded(t.path):
            h = hash(data)
            if h not in seen:
                seen.add(h)
                yield data, t.mtime or 0
    if tracks:
        try:
            p = T.folder_artwork(os.path.dirname(tracks[0].path))
        except Exception:                                      # noqa: BLE001
            p = None
        if p:
            with open(p, "rb") as f:
                yield f.read(), os.path.getmtime(p)


def _thumb(data: bytes, size: int, quality: int) -> bytes:
    from PIL import Image, ImageFile
    ImageFile.LOAD_TRUNCATED_IMAGES = True                     # obraz ucięty o kilka bajtów – reszta jest dobra
    im = Image.open(io.BytesIO(data))
    im.draft("RGB", (size * 2, size * 2))                      # szybkie dekodowanie dużych JPEG
    im = im.convert("RGB")
    im.thumbnail((size, size), Image.LANCZOS)
    out = io.BytesIO()
    im.save(out, "JPEG", quality=quality, optimize=True, progressive=False)
    return out.getvalue()


def export(lib, sync_dir: str, arts: dict, size: int = 512, quality: int = 82, refresh: bool = False,
           limit: int | None = None, log=print) -> dict:
    """Tworzy brakujące miniatury. `arts` – {klucz: (folder albumu, [id utworów])} z snapshot.build.
    Zwraca statystykę."""
    idx_path = os.path.join(sync_dir, "art", "index.json")
    os.makedirs(os.path.dirname(idx_path), exist_ok=True)
    try:
        with open(idx_path, encoding="utf-8") as f:
            index = json.load(f)
    except (OSError, ValueError):
        index = {}
    todo = []
    for key, (_d, tids) in arts.items():
        known = index.get(key)
        if refresh or known is None:
            todo.append(key)
    if limit is not None:
        todo = todo[:limit]
    stats = {"total": len(arts), "todo": len(todo), "written": 0, "none": 0, "errors": 0}
    if not todo:
        log(f"  okładki: wszystkie {len(arts)} aktualne")
        return stats
    log(f"  okładki: do przygotowania {len(todo)} z {len(arts)}…")
    lock = threading.Lock()
    t0 = time.time()

    def one(key):
        folder, tids = arts[key]
        try:
            jpg, mt, last_err, found = None, 0, None, False
            for data, when in _sources(lib, tids):
                found = True
                try:
                    jpg, mt = _thumb(data, size, quality), when
                    break
                except Exception as e:                         # noqa: BLE001 – uszkodzony obraz: następny kandydat
                    last_err = e
            if not found:
                with lock:
                    index[key] = 0                             # brak okładki – nie sprawdzaj przy każdym eksporcie
                    stats["none"] += 1
                return
            if jpg is None:
                raise ValueError(f"żaden obraz okładki nie jest poprawny ({last_err})")
            p = art_path(sync_dir, key)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            tmp = p + ".tmp"
            with open(tmp, "wb") as f:
                f.write(jpg)
            os.replace(tmp, p)
            with lock:
                index[key] = int(mt) or 1
                stats["written"] += 1
        except Exception as e:                                 # noqa: BLE001
            with lock:
                stats["errors"] += 1
                if stats["errors"] <= 5:
                    log(f"    błąd okładki – {folder or key}: {e}")

    done = 0
    with ThreadPoolExecutor(max_workers=4) as ex:
        for _ in ex.map(one, todo):
            done += 1
            if done % SAVE_EVERY == 0:
                log(f"    {done}/{len(todo)} ({time.time() - t0:.0f} s)")
                with lock:                                     # wątki dopisują do indeksu w trakcie zapisu
                    snap = dict(index)
                _save_index(idx_path, snap)
    _save_index(idx_path, index)
    log(f"  okładki: zapisano {stats['written']}, bez okładki {stats['none']}, błędy {stats['errors']} "
        f"({time.time() - t0:.0f} s)")
    return stats


def _save_index(path: str, index: dict) -> None:
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(index, f, separators=(",", ":"))
    os.replace(tmp, path)
