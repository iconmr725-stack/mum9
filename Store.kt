package com.mumflorence.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

fun cosDist(a: DoubleArray, b: DoubleArray): Double {
    val n = minOf(a.size, b.size)
    var d = 0.0; var na = 0.0; var nb = 0.0
    for (i in 0 until n) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
    return if (na == 0.0 || nb == 0.0) 1.0 else 1.0 - d / (Math.sqrt(na) * Math.sqrt(nb))
}

/** Settings (SharedPreferences) + Mum Florence's own memory (SQLite). */
class Store(ctx: Context) : SQLiteOpenHelper(ctx, "florence.db", null, 3) {
    private val prefs = ctx.getSharedPreferences("florence", Context.MODE_PRIVATE)

    private fun createAll(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS taught(phrase TEXT PRIMARY KEY, reply TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS facts(k TEXT PRIMARY KEY, v TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS contacts(name TEXT PRIMARY KEY, number TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS notes(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT, ts INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS convo(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, role TEXT, text TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS commands(phrase TEXT PRIMARY KEY, kind TEXT, payload TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS voices(name TEXT PRIMARY KEY, vec TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS qa(id INTEGER PRIMARY KEY AUTOINCREMENT, prompt TEXT, response TEXT, src TEXT, ts INTEGER)")
    }

    override fun onCreate(db: SQLiteDatabase) = createAll(db)
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = createAll(db)

    // ---- settings
    fun get(k: String, d: String = ""): String = prefs.getString(k, d) ?: d
    fun put(k: String, v: String) { prefs.edit().putString(k, v).apply() }
    fun on(k: String, d: Boolean = true): Boolean = get(k, if (d) "on" else "off") == "on"

    // ---- taught replies, contacts
    fun teach(phrase: String, reply: String) =
        writableDatabase.execSQL("INSERT OR REPLACE INTO taught VALUES(?,?)", arrayOf<Any>(phrase, reply))
    fun taught(phrase: String): String? = one("SELECT reply FROM taught WHERE phrase=?", phrase)
    fun saveContact(name: String, number: String) =
        writableDatabase.execSQL("INSERT OR REPLACE INTO contacts VALUES(?,?)", arrayOf<Any>(name, number))
    fun savedContacts(): Map<String, String> {
        val m = HashMap<String, String>()
        readableDatabase.rawQuery("SELECT name, number FROM contacts", null).use {
            while (it.moveToNext()) m[it.getString(0)] = it.getString(1)
        }
        return m
    }

    // ---- facts about the user
    fun setFact(k: String, v: String) =
        writableDatabase.execSQL("INSERT OR REPLACE INTO facts VALUES(?,?)", arrayOf<Any>(k.lowercase().trim(), v.trim()))
    fun fact(k: String): String? = one("SELECT v FROM facts WHERE k=?", k.lowercase().trim())
    fun deleteFact(k: String) { writableDatabase.execSQL("DELETE FROM facts WHERE k=?", arrayOf<Any>(k.lowercase().trim())) }
    fun allFacts(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        readableDatabase.rawQuery("SELECT k, v FROM facts ORDER BY k", null).use {
            while (it.moveToNext()) m[it.getString(0)] = it.getString(1)
        }
        return m
    }

    // ---- free notes ("remember that ...")
    fun addNote(t: String) =
        writableDatabase.execSQL("INSERT INTO notes(text, ts) VALUES(?,?)", arrayOf<Any>(t, System.currentTimeMillis()))
    fun notes(limit: Int = 100): List<String> {
        val l = ArrayList<String>()
        readableDatabase.rawQuery("SELECT text FROM notes ORDER BY id DESC LIMIT $limit", null).use {
            while (it.moveToNext()) l.add(it.getString(0))
        }
        return l
    }
    fun clearNotes() { writableDatabase.execSQL("DELETE FROM notes") }

    // ---- everything we said to each other
    fun log(role: String, text: String) {
        if (text.isBlank()) return
        writableDatabase.execSQL("INSERT INTO convo(ts, role, text) VALUES(?,?,?)",
            arrayOf<Any>(System.currentTimeMillis(), role, text.take(500)))
        writableDatabase.execSQL("DELETE FROM convo WHERE id <= (SELECT MAX(id) FROM convo) - 1000")
    }
    fun recentConvo(n: Int): List<Pair<String, String>> {
        val l = ArrayList<Pair<String, String>>()
        readableDatabase.rawQuery("SELECT role, text FROM convo ORDER BY id DESC LIMIT $n", null).use {
            while (it.moveToNext()) l.add(it.getString(0) to it.getString(1))
        }
        l.reverse()
        return l
    }
    fun clearConvo() { writableDatabase.execSQL("DELETE FROM convo") }

    // ---- what we have learned from talking together (prompt -> response)
    fun addQA(prompt: String, response: String, src: String) {
        if (prompt.isBlank() || response.isBlank()) return
        writableDatabase.execSQL("INSERT INTO qa(prompt, response, src, ts) VALUES(?,?,?,?)",
            arrayOf<Any>(prompt.take(300), response.take(500), src, System.currentTimeMillis()))
        writableDatabase.execSQL("DELETE FROM qa WHERE id <= (SELECT MAX(id) FROM qa) - 3000")
    }
    fun qaAll(limit: Int): List<Triple<String, String, String>> {
        val l = ArrayList<Triple<String, String, String>>()
        readableDatabase.rawQuery("SELECT prompt, response, src FROM qa ORDER BY id DESC LIMIT $limit", null).use {
            while (it.moveToNext()) l.add(Triple(it.getString(0), it.getString(1), it.getString(2)))
        }
        return l
    }
    fun qaCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM qa", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun clearQA() { writableDatabase.execSQL("DELETE FROM qa") }

    // ---- custom commands you add in the app
    fun saveCommand(phrase: String, kind: String, payload: String) =
        writableDatabase.execSQL("INSERT OR REPLACE INTO commands VALUES(?,?,?)", arrayOf<Any>(phrase.lowercase().trim(), kind, payload))
    fun deleteCommand(phrase: String) { writableDatabase.execSQL("DELETE FROM commands WHERE phrase=?", arrayOf<Any>(phrase.lowercase().trim())) }
    fun allCommands(): List<Triple<String, String, String>> {
        val l = ArrayList<Triple<String, String, String>>()
        readableDatabase.rawQuery("SELECT phrase, kind, payload FROM commands", null).use {
            while (it.moveToNext()) l.add(Triple(it.getString(0), it.getString(1), it.getString(2)))
        }
        return l
    }

    // ---- enrolled voices (speaker fingerprints)
    fun saveVoice(name: String, vec: DoubleArray) {
        val a = JSONArray()
        for (d in vec) a.put(d)
        writableDatabase.execSQL("INSERT OR REPLACE INTO voices VALUES(?,?)", arrayOf<Any>(name, a.toString()))
    }
    fun voices(): Map<String, DoubleArray> {
        val m = LinkedHashMap<String, DoubleArray>()
        readableDatabase.rawQuery("SELECT name, vec FROM voices", null).use {
            while (it.moveToNext()) {
                val a = JSONArray(it.getString(1))
                m[it.getString(0)] = DoubleArray(a.length()) { i -> a.getDouble(i) }
            }
        }
        return m
    }
    fun deleteVoice(name: String) { writableDatabase.execSQL("DELETE FROM voices WHERE name=?", arrayOf<Any>(name)) }

    private fun one(sql: String, arg: String): String? =
        readableDatabase.rawQuery(sql, arrayOf(arg)).use { if (it.moveToFirst()) it.getString(0) else null }
}
