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


def art_path(sync_dir: str, key: str) -> str:
    return os.path.join(sync_dir, "art", key[:2], key + ".jpg")


def _source(lib, tids) -> tuple[bytes | None, float]:
    from player39 import tags as T
    tracks = sorted((lib.tracks[i] for i in tids if i in lib.tracks),
                    key=lambda t: (t.disc_no or 0, t.track_no or 0, t.pathkey))
    for t in tracks[:3]:
        try:
            data = T.embedded_artwork(t.path)
        except Exception:                                      # noqa: BLE001
            data = None
        if data:
            return data, t.mtime or 0
    if tracks:
        try:
            p = T.folder_artwork(os.path.dirname(tracks[0].path))
        except Exception:                                      # noqa: BLE001
            p = None
        if p:
            with open(p, "rb") as f:
                return f.read(), os.path.getmtime(p)
    return None, 0


def _thumb(data: bytes, size: int, quality: int) -> bytes:
    from PIL import Image
    im = Image.open(io.BytesIO(data))
    im.draft("RGB", (size * 2, size * 2))                      # szybkie dekodowanie dużych JPEG
    im = im.convert("RGB")
    im.thumbnail((size, size), Image.LANCZOS)
    out = io.BytesIO()
    im.save(out, "JPEG", quality=quality, optimize=True, progressive=False)
    return out.getvalue()


def export(lib, sync_dir: str, arts: dict, size: int = 360, quality: int = 84, refresh: bool = False,
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
        tids = arts[key][1]
        try:
            data, mt = _source(lib, tids)
            if not data:
                with lock:
                    index[key] = 0                             # brak okładki – nie sprawdzaj przy każdym eksporcie
                    stats["none"] += 1
                return
            jpg = _thumb(data, size, quality)
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
                    log(f"    błąd okładki {key}: {e}")

    done = 0
    with ThreadPoolExecutor(max_workers=4) as ex:
        for _ in ex.map(one, todo):
            done += 1
            if done % 500 == 0:
                log(f"    {done}/{len(todo)} ({time.time() - t0:.0f} s)")
                _save_index(idx_path, index)
    _save_index(idx_path, index)
    log(f"  okładki: zapisano {stats['written']}, bez okładki {stats['none']}, błędy {stats['errors']} "
        f"({time.time() - t0:.0f} s)")
    return stats


def _save_index(path: str, index: dict) -> None:
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(index, f, separators=(",", ":"))
    os.replace(tmp, path)
