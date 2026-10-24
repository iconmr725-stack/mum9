package com.mumflorence.app

import android.content.Context
import java.util.Random
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Her own brain, fully on the phone (no internet, no API key).
 * It is trained on the conversation/knowledge set bundled in the app (assets/knowledge.txt) and keeps
 * learning from everything you teach her, your chats with her, and (if allowed) your text messages
 * and chat notifications. It answers by finding the closest thing it has learned (TF-IDF matching).
 */
object LocalMind {
    private class Entry(val replies: List<String>, val vec: Map<String, Double>, val norm: Double, val total: Double, val words: Int, val src: String, val boost: Double)

    private var corpus: List<Pair<List<String>, List<String>>>? = null
    private var entries: List<Entry> = emptyList()
    private var idf: Map<String, Double> = emptyMap()
    private var builtFor = -1
    private val rnd = Random()
    const val STRONG = 1
    const val WEAK = 0

    private fun stem(w: String) = if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1) else w

    private fun tokens(s: String): List<String> {
        val words = s.lowercase().replace("'", "").split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.map { stem(it) }
        val out = ArrayList<String>(words)
        for (i in 0 until words.size - 1) out.add(words[i] + "_" + words[i + 1])
        return out
    }

    private fun load(ctx: Context) {
        val list = ArrayList<Pair<List<String>, List<String>>>()
        try {
            ctx.assets.open("knowledge.txt").bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank() || line.startsWith("#") || !line.contains("=>")) continue
                    val (l, r) = line.split("=>", limit = 2)
                    val prompts = l.split("||").map { it.trim() }.filter { it.isNotEmpty() }
                    val replies = r.split(" | ").map { it.trim() }.filter { it.isNotEmpty() }
                    if (prompts.isNotEmpty() && replies.isNotEmpty()) list.add(prompts to replies)
                }
            }
        } catch (e: Exception) {}
        corpus = list
    }

    @Synchronized
    private fun ensure(ctx: Context, store: Store) {
        if (corpus == null) load(ctx)
        val qc = store.qaCount()
        if (builtFor == qc && entries.isNotEmpty()) return
        data class Doc(val prompt: String, val replies: List<String>, val src: String, val boost: Double)
        val docs = ArrayList<Doc>()
        for ((ps, rs) in corpus!!) for (p in ps) docs.add(Doc(p, rs, "corpus", 1.0))
        for ((p, r, src) in store.qaAll(2500)) docs.add(Doc(p, listOf(r), src, if (src == "you" || src == "me") 1.1 else 1.05))
        val df = HashMap<String, Int>()
        val toks = docs.map { tokens(it.prompt) }
        for (t in toks) for (w in t.toSet()) df[w] = (df[w] ?: 0) + 1
        val n = docs.size.toDouble()
        idf = df.mapValues { ln((n + 1.0) / (it.value + 1.0)) + 1.0 }
        entries = docs.indices.map { i ->
            val tf = HashMap<String, Double>()
            for (w in toks[i]) tf[w] = (tf[w] ?: 0.0) + 1.0
            val vec = tf.mapValues { it.value * (idf[it.key] ?: 1.0) }
            Entry(docs[i].replies, vec, sqrt(vec.values.sumOf { it * it }), vec.values.sum(), toks[i].count { !it.contains("_") }, docs[i].src, docs[i].boost)
        }
        builtFor = qc
    }

    /**
     * The best reply she has learned, or null if she is not sure enough (she would rather say "I don't know"
     * than give a confident wrong answer). Optionally only from some sources (e.g. how YOU reply to people).
     */
    fun best(ctx: Context, store: Store, query: String, mode: Int = WEAK, onlySources: Set<String>? = null): Pair<String, Double>? {
        ensure(ctx, store)
        val qt = tokens(query)
        if (qt.isEmpty()) return null
        val uni = qt.filter { !it.contains("_") }
        val unseen = uni.count { !idf.containsKey(it) }.toDouble() / maxOf(1, uni.size)
        val tf = HashMap<String, Double>()
        for (w in qt) tf[w] = (tf[w] ?: 0.0) + 1.0
        val qv = tf.mapValues { it.value * (idf[it.key] ?: (ln(entries.size + 2.0) + 1.0)) }
        val qn = sqrt(qv.values.sumOf { it * it })
        if (qn == 0.0) return null
        var bestE: Entry? = null
        var bestScore = 0.0
        var bestCos = 0.0
        var bestRecall = 0.0
        for (e in entries) {
            if (onlySources != null && e.src !in onlySources) continue
            if (e.norm == 0.0) continue
            var dot = 0.0
            var covered = 0.0
            for ((w, x) in qv) { val y = e.vec[w]; if (y != null) { dot += x * y; covered += y } }
            val cos = dot / (qn * e.norm)
            val score = cos * e.boost * (1.0 - 0.6 * unseen)
            if (score > bestScore) { bestScore = score; bestE = e; bestCos = cos; bestRecall = covered / e.total }
        }
        val e = bestE ?: return null
        val ok = if (mode == STRONG) bestScore >= 0.72 && bestRecall >= 0.8
        else (bestScore >= 0.5 && bestRecall >= 0.7) || (e.words >= 2 && bestRecall >= 0.9 && bestCos >= 0.3 && unseen <= 0.2)
        if (!ok) return null
        val name = store.fact("name")
        val reply = e.replies[rnd.nextInt(e.replies.size)].replace("{n}", if (name != null) " $name" else "")
        return reply to bestScore
    }

    fun size(ctx: Context, store: Store): Int { ensure(ctx, store); return entries.size }
}
