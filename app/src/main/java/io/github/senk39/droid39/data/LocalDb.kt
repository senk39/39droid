package io.github.senk39.droid39.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Lokalny stan telefonu: dziennik zmian (nakładka na migawkę z PC), indeks plików lokalnych, kolejka. */
class LocalDb(context: Context) : SQLiteOpenHelper(context, "local.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE op(seq INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, type TEXT NOT NULL,
               track_id INTEGER, rel TEXT, playlist TEXT, value INTEGER NOT NULL DEFAULT 0, extra TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE file(rel_key TEXT PRIMARY KEY, path TEXT NOT NULL, source TEXT NOT NULL,
               size INTEGER NOT NULL DEFAULT 0, added INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX file_source ON file(source)")
        db.execSQL("CREATE TABLE queue(pos INTEGER PRIMARY KEY, track_id INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE kv(key TEXT PRIMARY KEY, value TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------------------------------------------------------------- dziennik zmian
    data class Op(
        val seq: Long, val ts: Long, val type: String, val trackId: Int?, val rel: String?,
        val playlist: String?, val value: Int, val extra: String?,
    )

    fun addOp(type: String, trackId: Int?, rel: String?, playlist: String?, value: Int, extra: String? = null): Long {
        val cv = ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("type", type)
            if (trackId != null) put("track_id", trackId) else putNull("track_id")
            put("rel", rel)
            put("playlist", playlist)
            put("value", value)
            put("extra", extra)
        }
        return writableDatabase.insertOrThrow("op", null, cv)
    }

    fun ops(afterSeq: Long = 0): List<Op> {
        val out = ArrayList<Op>()
        readableDatabase.rawQuery(
            "SELECT seq, ts, type, track_id, rel, playlist, value, extra FROM op WHERE seq > ? ORDER BY seq",
            arrayOf(afterSeq.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += Op(
                    c.getLong(0), c.getLong(1), c.getString(2), if (c.isNull(3)) null else c.getInt(3),
                    c.getString(4), c.getString(5), c.getInt(6), c.getString(7)
                )
            }
        }
        return out
    }

    fun maxSeq(): Long = readableDatabase.rawQuery("SELECT COALESCE(MAX(seq), 0) FROM op", null).use {
        it.moveToFirst(); it.getLong(0)
    }

    /** Usuwa zmiany już zawarte w migawce z PC. */
    fun dropOpsUpTo(seq: Long): Int = writableDatabase.delete("op", "seq <= ?", arrayOf(seq.toString()))

    fun deleteOp(seq: Long) {
        writableDatabase.delete("op", "seq = ?", arrayOf(seq.toString()))
    }

    // ---------------------------------------------------------------- pliki lokalne
    data class LocalFile(val relKey: String, val path: String, val source: String, val size: Long)

    fun files(): List<LocalFile> {
        val out = ArrayList<LocalFile>()
        readableDatabase.rawQuery("SELECT rel_key, path, source, size FROM file", null).use { c ->
            while (c.moveToNext()) out += LocalFile(c.getString(0), c.getString(1), c.getString(2), c.getLong(3))
        }
        return out
    }

    fun putFile(f: LocalFile) {
        val cv = ContentValues().apply {
            put("rel_key", f.relKey); put("path", f.path); put("source", f.source); put("size", f.size)
            put("added", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("file", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeFile(relKey: String) {
        writableDatabase.delete("file", "rel_key = ?", arrayOf(relKey))
    }

    /** Podmienia wszystkie wpisy danego źródła (np. po skanowaniu folderu z kopią biblioteki). */
    fun replaceSource(source: String, files: List<LocalFile>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("file", "source = ?", arrayOf(source))
            val cv = ContentValues()
            for (f in files) {
                cv.clear()
                cv.put("rel_key", f.relKey); cv.put("path", f.path); cv.put("source", f.source)
                cv.put("size", f.size); cv.put("added", System.currentTimeMillis())
                db.insertWithOnConflict("file", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------------------------------------------------------------- kolejka i drobne wartości
    fun saveQueue(ids: List<Int>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("queue", null, null)
            val st = db.compileStatement("INSERT INTO queue(pos, track_id) VALUES(?, ?)")
            ids.forEachIndexed { i, id ->
                st.bindLong(1, i.toLong()); st.bindLong(2, id.toLong()); st.executeInsert(); st.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun loadQueue(): List<Int> {
        val out = ArrayList<Int>()
        readableDatabase.rawQuery("SELECT track_id FROM queue ORDER BY pos", null).use { c ->
            while (c.moveToNext()) out += c.getInt(0)
        }
        return out
    }

    fun put(key: String, value: String?) {
        val cv = ContentValues().apply { put("key", key); put("value", value) }
        writableDatabase.insertWithOnConflict("kv", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun get(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM kv WHERE key = ?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
}
