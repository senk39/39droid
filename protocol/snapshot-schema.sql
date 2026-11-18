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
