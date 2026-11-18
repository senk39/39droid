"""Migawka biblioteki 39player dla telefonu (SQLite).

Telefon nie liczy kategorii sam – dostaje gotowe drzewo (foldery, vocaP, Wokaliści, Silniki syntezy), skład playlist,
serduszka, listę „Do odsłuchania” z oznaczeniami, dane VocaDB (ID, nazwy, typ, tagi, artyści z rolami), indeks filtrów
Love Live!, półkę Serie (Anime) i gwiazdki. Ścieżki plików są względne wobec folderu muzyki (separator „/”, NFC),
więc ten sam plik znajdzie się na karcie SD, w pamięci podręcznej telefonu i na NAS."""
from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import time
import unicodedata

from . import FORMAT, VERSION

SCHEMA = """
CREATE TABLE info(key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE track(
    id INTEGER PRIMARY KEY,
    rel TEXT NOT NULL,
    title TEXT, artist TEXT, album TEXT, album_artist TEXT, composer TEXT, grouping TEXT, genre TEXT,
    year INTEGER, comment TEXT, track_no INTEGER, track_total INTEGER, disc_no INTEGER, disc_total INTEGER,
    duration REAL, size INTEGER, mtime REAL, bitrate INTEGER, samplerate INTEGER,
    date_added REAL, play_count INTEGER, last_played REAL,
    heart INTEGER NOT NULL DEFAULT 0,
    album_key TEXT, art TEXT,
    sort_title TEXT, sort_artist TEXT, sort_album TEXT,
    search TEXT
);
CREATE INDEX track_album ON track(album_key);
CREATE INDEX track_added ON track(date_added);
CREATE INDEX track_art ON track(art);
CREATE TABLE vdb(
    track_id INTEGER PRIMARY KEY, song_id INTEGER, album_id INTEGER, song_type TEXT,
    name_ja TEXT, name_romaji TEXT, name_en TEXT, album_ja TEXT, album_romaji TEXT, album_en TEXT,
    roles TEXT, artists TEXT, publish_date TEXT, issue TEXT, tags_fetched INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE vdb_none(track_id INTEGER PRIMARY KEY);
CREATE TABLE tag(id INTEGER PRIMARY KEY, name TEXT NOT NULL, category TEXT, count INTEGER, likes INTEGER);
CREATE TABLE track_tag(track_id INTEGER NOT NULL, tag_id INTEGER NOT NULL, PRIMARY KEY(track_id, tag_id)) WITHOUT ROWID;
CREATE INDEX track_tag_tag ON track_tag(tag_id);
CREATE TABLE node(
    id INTEGER PRIMARY KEY, parent INTEGER, pos INTEGER NOT NULL, path TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL, kind TEXT NOT NULL, count INTEGER NOT NULL, likes INTEGER NOT NULL DEFAULT 0,
    has_tracks INTEGER NOT NULL DEFAULT 1,
    vdb_artist_id INTEGER, vdb_artist_name TEXT, vdb_artist_type TEXT, engines TEXT, art TEXT
);
CREATE INDEX node_parent ON node(parent, pos);
CREATE TABLE node_track(node_id INTEGER NOT NULL, pos INTEGER NOT NULL, track_id INTEGER NOT NULL,
    PRIMARY KEY(node_id, pos)) WITHOUT ROWID;
CREATE INDEX node_track_track ON node_track(track_id);
CREATE TABLE playlist(
    id INTEGER PRIMARY KEY, rel TEXT NOT NULL UNIQUE, name TEXT NOT NULL, folder TEXT NOT NULL,
    kind TEXT NOT NULL, hidden INTEGER NOT NULL DEFAULT 0, pos INTEGER NOT NULL,
    count INTEGER NOT NULL DEFAULT 0, missing INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE playlist_track(playlist_id INTEGER NOT NULL, pos INTEGER NOT NULL, track_id INTEGER NOT NULL,
    PRIMARY KEY(playlist_id, pos)) WITHOUT ROWID;
CREATE INDEX playlist_track_track ON playlist_track(track_id);
CREATE TABLE unlistened(track_id INTEGER PRIMARY KEY, pos INTEGER NOT NULL, added REAL,
    best INTEGER NOT NULL DEFAULT 0, liked INTEGER NOT NULL DEFAULT 0, listened INTEGER NOT NULL DEFAULT 0);
CREATE TABLE commit_log(id INTEGER PRIMARY KEY, ts REAL, items TEXT);
CREATE TABLE ll_track(track_id INTEGER PRIMARY KEY, groups TEXT, chars TEXT, voices TEXT, units TEXT,
    solo INTEGER, inst INTEGER, ost INTEGER);
CREATE TABLE ll_roster(kind TEXT NOT NULL, pos INTEGER NOT NULL, name TEXT NOT NULL, grp TEXT, members TEXT,
    icon TEXT, count INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(kind, pos)) WITHOUT ROWID;
CREATE TABLE franchise(id INTEGER PRIMARY KEY, pos INTEGER NOT NULL, label TEXT NOT NULL, node_path TEXT,
    count INTEGER, art TEXT);
CREATE TABLE franchise_item(franchise_id INTEGER NOT NULL, pos INTEGER NOT NULL, label TEXT NOT NULL,
    node_paths TEXT, count INTEGER, art TEXT, PRIMARY KEY(franchise_id, pos)) WITHOUT ROWID;
CREATE TABLE star(kind TEXT NOT NULL, pos INTEGER NOT NULL, ident TEXT NOT NULL, name TEXT,
    PRIMARY KEY(kind, pos)) WITHOUT ROWID;
CREATE TABLE applied(device TEXT PRIMARY KEY, seq INTEGER NOT NULL, ts REAL);
"""

SEP = "\x1f"          # separator kluczy w ścieżce węzła (klucze same mogą zawierać „/”)
# węzły zbiorcze – pełna lista utworów nie jest zapisywana (to suma podkategorii, liczona na telefonie)
AGGREGATE_KINDS = {"vocap_root", "vocal_root", "vocal_section", "engine_root"}


def nfc(s: str | None) -> str:
    return unicodedata.normalize("NFC", s or "")


def art_key(album_key: str, album: str | None) -> str:
    """Klucz miniatury: folder albumu + nazwa albumu z tagu (folder singli ma wiele okładek)."""
    base = album_key + "\x1f" + " ".join(unicodedata.normalize("NFKC", album or "").casefold().split())
    return hashlib.sha1(base.encode("utf-8")).hexdigest()[:16]


class Rel:
    """Ścieżki względne wobec folderów muzyki (przy kilku folderach – z nazwą folderu na początku)."""

    def __init__(self, lib):
        roots = [r for r in lib.cfg.get("foldery_muzyki", []) if r]
        self.multi = len(roots) > 1
        from player39 import db as DB
        self.roots = sorted(((DB.pathkey(r).rstrip(os.sep) + os.sep, r.rstrip("\\/")) for r in roots),
                            key=lambda x: -len(x[0]))

    def of(self, path: str, pathkey: str) -> str | None:
        for rk, root in self.roots:
            if pathkey.startswith(rk):
                rest = path[len(rk):]
                if self.multi:
                    rest = os.path.basename(root) + os.sep + rest
                return nfc(rest).replace("\\", "/")
        return None

    def of_dir(self, d: str) -> str | None:
        """Folder względem folderu muzyki, bez „/” na końcu („” – sam folder muzyki)."""
        from player39 import db as DB
        k = DB.pathkey(d).rstrip(os.sep) + os.sep
        r = self.of(d.rstrip("\\/") + os.sep, k)
        return r.rstrip("/") if r is not None else None


def build(lib, cfg: dict, out_path: str, applied: dict | None = None, log=print) -> dict:
    """Zapisuje migawkę do `out_path`. Zwraca {"tracks", "arts": {klucz: (folder albumu, [id utworów])}, …}."""
    from player39 import franchises as F
    from player39 import lovelive as L
    from player39 import names as N
    from player39 import stars as S
    from player39 import vocadb as V
    from player39.categories import KIND_ALBUM

    t0 = time.time()
    if os.path.exists(out_path):
        os.remove(out_path)
    con = sqlite3.connect(out_path)
    con.executescript("PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;")
    con.executescript(SCHEMA)
    rel = Rel(lib)

    # ------------------------------------------------------------------ utwory
    best = lib.playlist_contains("playlista_najlepsze")
    hearted: set[int] = set()
    rows, arts, outside = [], {}, 0
    track_art: dict[int, str] = {}
    for t in sorted(lib.tracks.values(), key=lambda x: x.pathkey):
        r = rel.of(t.path, t.pathkey)
        if r is None:
            outside += 1
            continue
        ad = rel.of_dir(t.album_dir) or ""
        album_key = ad.casefold()
        ak = art_key(album_key, t.album)
        track_art[t.id] = ak
        arts.setdefault(ak, (t.album_dir, []))[1].append(t.id)
        heart = 2 if t.id in best else (1 if t.loved else 0)
        if heart:
            hearted.add(t.id)
        rows.append((t.id, r, t.title, t.artist, t.album, t.album_artist, t.composer, t.grouping, t.genre,
                     t.year, t.comment, t.track_no, t.track_total, t.disc_no, t.disc_total,
                     t.duration, t.size, t.mtime, t.bitrate, t.samplerate, t.date_added, t.play_count or 0,
                     t.last_played, heart, album_key, ak,
                     N.natural_key(t.title), N.natural_key(t.artist), N.natural_key(t.album), t.search_text))
    con.executemany(f"INSERT INTO track VALUES({','.join('?' * 30)})", rows)
    exported = {r[0] for r in rows}
    log(f"  utwory: {len(rows)} (poza folderem muzyki: {outside})")

    # ------------------------------------------------------------------ VocaDB
    vrows, trows = [], []
    for tid, info in lib.vdb.items():
        if tid not in exported:
            continue
        song = info.song or {}
        artists = [[name, roles] for name, roles in V.artist_roles(song)] if song else []
        try:
            issue = lib.vdb_issue(tid)
        except Exception:                                      # noqa: BLE001 – niezgodność to tylko podpowiedź
            issue = ""
        vrows.append((tid, info.song_id, info.album_id, V.song_type_text(song) if song else "",
                      info.names.get("ja", ""), info.names.get("romaji", ""), info.names.get("en", ""),
                      info.album_names.get("ja", ""), info.album_names.get("romaji", ""),
                      info.album_names.get("en", ""), info.roles,
                      json.dumps(artists, ensure_ascii=False) if artists else None,
                      (song.get("publishDate") or "")[:10], issue, 0 if info.tags is None else 1))
    con.executemany(f"INSERT INTO vdb VALUES({','.join('?' * 15)})", vrows)
    con.executemany("INSERT INTO vdb_none VALUES(?)", [(i,) for i in lib.vdb_none if i in exported])
    index = lib.vdb_tag_index()
    for cid, tg in index.items():
        ids = [i for i in tg["ids"] if i in exported]
        con.execute("INSERT INTO tag VALUES(?,?,?,?,?)",
                    (cid, tg["name"], tg["category"], len(ids), sum(1 for i in ids if i in hearted)))
        trows.extend((i, cid) for i in ids)
    con.executemany("INSERT OR IGNORE INTO track_tag VALUES(?,?)", trows)
    log(f"  VocaDB: {len(vrows)} podpiętych, tagi: {len(index)}")

    # ------------------------------------------------------------------ kategorie
    from player39 import engines as E
    node_rows, nt_rows = [], []
    path_of: dict[int, str] = {}
    counter = [0]

    def rep_art(node) -> str | None:
        tid = F.representative(lib, [node])
        return track_art.get(tid) if tid is not None else None

    def walk(nodes, parent_id, parent_path):
        for pos, n in enumerate(nodes):
            counter[0] += 1
            nid = counter[0]
            path = (parent_path + SEP if parent_path else "") + n.key
            path_of[id(n)] = path
            ids = [i for i in n.track_ids() if i in exported]
            aid = aname = atype = None
            link = lib.vdb_vocap.get(n.key) if n.kind == "vocap" else (
                lib.vdb_vocal.get(n.key) if n.kind in ("vocal", "variant") else None)
            if link:
                aid = link[0]
                data = link[1] or {}
                aname, atype = data.get("name"), data.get("artistType")
            engines = None
            if n.kind in ("vocal", "variant"):
                try:
                    engines = "|".join(lbl for lbl, _e in E.node_pills(lib, n)) or None
                except Exception:                              # noqa: BLE001
                    engines = None
            agg = n.kind in AGGREGATE_KINDS
            node_rows.append((nid, parent_id, pos, path, n.name, n.kind, len(ids),
                              sum(1 for i in ids if i in hearted), 0 if agg else 1, aid, aname, atype, engines,
                              None if agg else rep_art(n)))
            if not agg:
                nt_rows.extend((nid, k, tid) for k, tid in enumerate(ids))
            walk(n.children, nid, path)

    walk(lib.categories, None, "")
    con.executemany(f"INSERT INTO node VALUES({','.join('?' * 14)})", node_rows)
    con.executemany("INSERT INTO node_track VALUES(?,?,?)", nt_rows)
    log(f"  kategorie: {len(node_rows)} węzłów, {len(nt_rows)} powiązań")

    # ------------------------------------------------------------------ playlisty
    specials = [("playlista_najlepsze", "best"), ("playlista_polubione", "liked"),
                ("playlista_polubione_tel", "liked_tel"), ("playlista_nieprzesluchane", "unlistened")]
    special_kind = {(cfg.get(k) or "").casefold(): kind for k, kind in specials if cfg.get(k)}
    entries = list(lib.playlist_entries())
    have = {r for _k, r in entries}
    for key, _kind in specials:
        name = cfg.get(key)
        if name and name not in have and lib.playlist_path(name) and os.path.exists(lib.playlist_path(name)):
            entries.append(("pl", name))
    pl_rows, pt_rows = [], []
    for pos, (kind, r) in enumerate(entries):
        folder, _, name = r.rpartition("/")
        pid = pos + 1
        if kind == "dir":
            pl_rows.append((pid, r, name, folder, "dir", 0, pos, 0, 0))
            continue
        ids, missing = lib.playlist_ids(r)
        ids = [i for i in ids if i in exported]
        sk = special_kind.get(r.casefold()) if not folder else None
        hidden = 1 if sk else 0
        title = name.rsplit(".", 1)[0]
        pl_rows.append((pid, r, title, folder, sk or "pl", hidden, pos, len(ids), missing))
        pt_rows.extend((pid, k, tid) for k, tid in enumerate(ids))
    con.executemany(f"INSERT INTO playlist VALUES({','.join('?' * 9)})", pl_rows)
    con.executemany("INSERT INTO playlist_track VALUES(?,?,?)", pt_rows)
    log(f"  playlisty: {sum(1 for p in pl_rows if p[4] != 'dir')} ({len(pt_rows)} pozycji)")

    # ------------------------------------------------------------------ Do odsłuchania
    urows = []
    for pos, tid in enumerate(lib.unlistened_ids()):
        if tid in exported:
            b, li, x = lib.marks_of(tid)
            urows.append((tid, pos, lib.unlistened.get(tid), b, li, x))
    con.executemany("INSERT INTO unlistened VALUES(?,?,?,?,?,?)", urows)
    try:
        for c in lib.recent_commits(100):
            con.execute("INSERT INTO commit_log VALUES(?,?,?)",
                        (c["id"], c["ts"], json.dumps([[i, lv] for i, lv in c["items"] if i in exported])))
    except Exception as e:                                     # noqa: BLE001
        log(f"  (historia zatwierdzeń pominięta: {e})")

    # ------------------------------------------------------------------ Anime: Serie, Love Live!
    anime = F.anime_root(lib)
    if anime is not None:
        for fpos, f in enumerate(F.build(lib, anime)):
            fid = fpos + 1
            fnode = f["nodes"][0] if f["nodes"] else None
            con.execute("INSERT INTO franchise VALUES(?,?,?,?,?,?)",
                        (fid, fpos, f["label"], path_of.get(id(fnode)) if fnode else None, f["count"],
                         track_art.get(f["track"]) if f.get("track") else None))
            for ipos, it in enumerate(f["items"]):
                paths = [path_of[id(n)] for n in it["nodes"] if id(n) in path_of]
                con.execute("INSERT INTO franchise_item VALUES(?,?,?,?,?,?)",
                            (fid, ipos, it["label"], json.dumps(paths, ensure_ascii=False), it["count"],
                             track_art.get(it["track"]) if it.get("track") else None))
        ll = F.match_child(anime, "Love Live!")
        if ll is not None:
            idx = L.build_index(lib, ll)
            lrows = []
            for tid, x in idx.info.items():
                if tid in exported:
                    lrows.append((tid, "|".join(sorted(x.groups)), "|".join(sorted(x.chars)),
                                  "|".join(sorted(x.voices)), "|".join(sorted(x.units)),
                                  int(x.solo), int(x.inst), int(x.ost)))
            con.executemany("INSERT INTO ll_track VALUES(?,?,?,?,?,?,?,?)", lrows)
            roster = []
            gcount = dict(idx.groups())
            ccount = {c: n for _g, c, n in idx.chars()}
            ucount = dict(idx.units())
            for gpos, g in enumerate(L.GROUPS):
                roster.append(("group", gpos, g["name"], None, None, _icon(L, g["name"]), gcount.get(g["name"], 0)))
            pos = 0
            for g in L.GROUPS:
                for m in g["members"]:
                    roster.append(("char", pos, m, g["name"], None, _icon(L, m), ccount.get(m, 0)))
                    pos += 1
            pos = 0
            seen = set()
            for g in L.GROUPS:
                for u in g["units"]:
                    if u in seen:
                        continue
                    seen.add(u)
                    roster.append(("unit", pos, u, "|".join(L.ROSTER.unit_groups.get(u, [])),
                                   "|".join(L.UNIT_MEMBERS.get(u, [])), _icon(L, u), ucount.get(u, 0)))
                    pos += 1
            for u, gs in L.CROSS_UNITS.items():
                if u not in seen:
                    roster.append(("unit", pos, u, "|".join(gs), "|".join(L.UNIT_MEMBERS.get(u, [])), _icon(L, u),
                                   ucount.get(u, 0)))
                    pos += 1
            for fpos, (node, g) in enumerate(L.group_folders(ll).items()):
                if id(node) in path_of:
                    roster.append(("folder", fpos, path_of[id(node)], g, None, None, 0))
            con.executemany("INSERT INTO ll_roster VALUES(?,?,?,?,?,?,?)", roster)
            info_ll = path_of.get(id(ll))
            if info_ll:
                con.execute("INSERT INTO info VALUES('ll_node', ?)", (info_ll,))
            log(f"  Love Live!: {len(lrows)} utworów w indeksie filtrów")

    # ------------------------------------------------------------------ gwiazdki (ulubione tagi, vocaP, albumy)
    srows = []
    for kind, _title in S.KINDS:
        for pos, e in enumerate(S.items(cfg, kind)):
            ident = e["id"]
            if kind == "vocap":
                keys = list(ident)[1:] if ident and ident[0] == "cat" else list(ident)
                ident = SEP.join(keys)
            elif kind == "album":
                r = rel.of_dir(str(ident))
                ident = (r or str(ident)).casefold()
            srows.append((kind, pos, str(ident), e.get("name") or ""))
    con.executemany("INSERT INTO star VALUES(?,?,?,?)", srows)

    # ------------------------------------------------------------------ potwierdzenia dziennika telefonu
    for dev, st in (applied or {}).items():
        con.execute("INSERT INTO applied VALUES(?,?,?)", (dev, int(st.get("seq", 0)), st.get("ts")))

    from player39 import config as C
    info = {
        "format": FORMAT, "generator": f"sync39 {VERSION}", "player39_version": C.APP_VERSION,
        "created": time.time(), "tracks": len(rows), "multi_root": int(rel.multi),
        "roots": json.dumps([r for _k, r in rel.roots], ensure_ascii=False),
        "vocap_name": cfg.get("nazwa_vocap", "vocaP"), "vocal_name": cfg.get("nazwa_wokalisci", "Wokaliści"),
        "vdb_style": cfg.get("styl_vocadb", "fill"), "recent_days": cfg.get("ostatnio_dodane_dni", 60),
        "sep": SEP,
    }
    con.executemany("INSERT OR REPLACE INTO info VALUES(?,?)", [(k, str(v)) for k, v in info.items()])
    con.commit()
    con.execute("VACUUM")
    con.close()
    log(f"  migawka gotowa w {time.time() - t0:.1f} s ({os.path.getsize(out_path) / 1e6:.1f} MB)")
    return {"tracks": len(rows), "arts": arts}


# ---------------------------------------------------------------------- stan (mała migawka)
# Często zmieniane części migawki: serduszka, odtworzenia, playlisty, „Do odsłuchania”, gwiazdki, potwierdzenia
# zmian z telefonu. Gdy zmieniło się tylko to, telefon pobiera mały plik stanu (kilkaset kB) zamiast całej biblioteki
# (kilkanaście MB) – także przez sieć komórkową.
STATE_TABLES = ("playlist", "playlist_track", "unlistened", "commit_log", "star", "applied")
STATE_COLUMNS = {"track": ("heart", "play_count", "last_played"), "node": ("likes",), "tag": ("likes",)}
STATE_FORMAT = 1


def _state_schema() -> str:
    """Tabele stanu: te same definicje co w pełnej migawce + kolumny stanu utworów, węzłów i tagów."""
    out = ["CREATE TABLE info(key TEXT PRIMARY KEY, value TEXT)",
           "CREATE TABLE track_state(id INTEGER PRIMARY KEY, heart INTEGER NOT NULL DEFAULT 0, play_count INTEGER, "
           "last_played REAL)",
           "CREATE TABLE node_likes(id INTEGER PRIMARY KEY, likes INTEGER NOT NULL)",
           "CREATE TABLE tag_likes(id INTEGER PRIMARY KEY, likes INTEGER NOT NULL)"]
    for stmt in SCHEMA.split(";"):
        st = stmt.strip()
        if st.startswith("CREATE TABLE ") and st[len("CREATE TABLE "):].split("(", 1)[0].strip() in STATE_TABLES:
            out.append(st)
    return ";\n".join(out) + ";"


def build_state(full_path: str, out_path: str) -> None:
    """Plik stanu wycięty z pełnej migawki `full_path` (tylko utwory, węzły i tagi z niezerowymi wartościami)."""
    if os.path.exists(out_path):
        os.remove(out_path)
    con = sqlite3.connect(out_path)
    try:
        con.executescript("PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;")
        con.executescript(_state_schema())
        con.execute("ATTACH DATABASE ? AS f", (full_path,))
        con.execute("INSERT INTO track_state SELECT id, heart, play_count, last_played FROM f.track "
                    "WHERE heart <> 0 OR COALESCE(play_count, 0) <> 0 OR last_played IS NOT NULL")
        con.execute("INSERT INTO node_likes SELECT id, likes FROM f.node WHERE likes <> 0")
        con.execute("INSERT INTO tag_likes SELECT id, likes FROM f.tag WHERE COALESCE(likes, 0) <> 0")
        for t in STATE_TABLES:
            con.execute(f"INSERT INTO {t} SELECT * FROM f.{t}")
        con.executemany("INSERT INTO info VALUES(?,?)", [("kind", "state"), ("state_format", str(STATE_FORMAT)),
                                                          ("created", str(time.time()))])
        con.commit()
        con.execute("DETACH DATABASE f")
        con.execute("VACUUM")
    finally:
        con.close()


def fingerprint(path: str, skip_tables=(), skip_columns: dict | None = None) -> str:
    """Skrót treści migawki bez chwili utworzenia – ta sama treść daje ten sam skrót (eksport bez zmian nie jest
    publikowany, więc telefon nie pobiera drugi raz tego samego). `skip_tables` / `skip_columns` – części pominięte."""
    skip_columns = skip_columns or {}
    con = sqlite3.connect(path)
    h = hashlib.sha256()
    try:
        for (t,) in con.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").fetchall():
            if t in skip_tables:
                continue
            cols = [r[1] for r in con.execute(f'PRAGMA table_info("{t}")') if r[1] not in skip_columns.get(t, ())]
            h.update(repr((t, cols)).encode("utf-8"))
            q = f'SELECT {", ".join(chr(34) + c + chr(34) for c in cols)} FROM "{t}"' + (
                " WHERE key <> 'created'" if t == "info" else "")
            for row in con.execute(q):
                h.update(repr(row).encode("utf-8"))
    finally:
        con.close()
    return h.hexdigest()


def base_fingerprint(path: str) -> str:
    """Skrót części rzadko zmienianej (utwory, VocaDB, kategorie…) – bez stanu."""
    return fingerprint(path, STATE_TABLES, STATE_COLUMNS)


def _icon(L, name: str) -> str | None:
    return L.icon_name(name) if L.icon_path(name) else None
