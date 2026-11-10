package com.iconewegoo.vpngate

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class Server(val ip: String, val port: Int, val proto: String, val country: String,
                  val score: Long, val ping: String, val speedMbps: Double, val config: String,
                  val cc: String = "") {
    val key get() = "$ip:$port:$proto"

    /** Flag emoji built from the 2-letter country code (shows as letters on fonts without flags). */
    val flag: String get() {
        val a = cc.uppercase()
        if (a.length != 2 || !a.all { it in 'A'..'Z' }) return "\uD83C\uDFF3"
        return String(Character.toChars(0x1F1E6 + (a[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (a[1] - 'A')))
    }

    fun toJson() = JSONObject().put("ip", ip).put("port", port).put("proto", proto).put("country", country)
        .put("score", score).put("ping", ping).put("speed", speedMbps).put("config", config).put("cc", cc)

    companion object {
        fun from(o: JSONObject) = Server(o.getString("ip"), o.getInt("port"), o.getString("proto"), o.getString("country"),
            o.getLong("score"), o.getString("ping"), o.getDouble("speed"), o.getString("config"), o.optString("cc", ""))
    }
}

class Store(c: Context) {
    private val p = c.getSharedPreferences("vpngate", 0)
    fun load(k: String): List<Server> = try {
        val a = JSONArray(p.getString(k, "[]")); (0 until a.length()).map { Server.from(a.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }
    fun save(k: String, l: List<Server>) = p.edit().putString(k, JSONArray(l.map { it.toJson() }).toString()).apply()

    /** The server we last asked OpenVPN to connect to. */
    var current: Server?
        get() = try { p.getString("current", null)?.let { Server.from(JSONObject(it)) } } catch (e: Exception) { null }
        set(v) {
            val ed = p.edit()
            if (v == null) ed.remove("current") else ed.putString("current", v.toJson().toString())
            ed.apply()
        }
}

object Api {
    private val ipv4 = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")

    /** Fixed sources. Every one is fetched and merged (their server lists differ). */
    val SOURCES = listOf(
        "https://raw.githubusercontent.com/GeorgeXie2333/vpngate-list-mirror/main/data/vpngate.csv",
        "https://cdn.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@latest/data/vpngate.csv",
        "https://fastly.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@latest/data/vpngate.csv",
        "https://gcore.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@latest/data/vpngate.csv"
    )

    private class Fetched(val src: String, val servers: List<Server>, val note: String)

    private fun shortName(m: String) = m.substringAfter("://").substringBefore("/")

    /** Fetches all sources in parallel, merges them and removes duplicates (same IP + port + protocol). */
    fun fetchAll(sources: List<String>, onProgress: (Int, Int) -> Unit): Pair<List<Server>, String> {
        val pool = Executors.newFixedThreadPool(sources.size.coerceIn(1, 6))
        val done = AtomicInteger(0)
        val tasks = sources.map { src ->
            Callable {
                val r = try { fetch(src) } catch (e: Exception) {
                    Fetched(src, emptyList(), "FAIL ${e.javaClass.simpleName}" + (e.message?.let { ": " + it.take(60) } ?: ""))
                }
                onProgress(done.incrementAndGet(), sources.size)
                r
            }
        }
        val results = try { pool.invokeAll(tasks).map { it.get() } } finally { pool.shutdown() }

        val seen = HashSet<String>()
        val merged = ArrayList<Server>()
        val report = StringBuilder()
        var dups = 0
        for (r in results) {
            var added = 0
            for (s in r.servers) if (seen.add(s.key)) { merged += s; added++ } else dups++
            report.append(shortName(r.src)).append(": ")
                .append(if (r.servers.isNotEmpty()) "${r.servers.size} servers, $added new" else r.note).append('\n')
        }
        report.append("Duplicates removed: $dups\nTotal unique: ${merged.size}")
        return merged.sortedByDescending { it.score } to report.toString()
    }

    private fun download(src: String): String {
        var url = src
        repeat(5) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 8000; c.readTimeout = 25000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = c.responseCode
            if (code in 301..308) {
                val loc = c.getHeaderField("Location"); c.disconnect()
                if (loc == null) throw IOException("HTTP $code")
                url = URL(URL(url), loc).toString()
            } else if (code == 200) {
                val t = c.inputStream.bufferedReader().readText(); c.disconnect(); return t
            } else { c.disconnect(); throw IOException("HTTP $code") }
        }
        throw IOException("too many redirects")
    }

    private fun fetch(src: String): Fetched {
        val text = download(src)
        val out = ArrayList<Server>()
        var rows = 0; var dom = 0; var bad = 0
        for (line in text.lines()) {
            if (line.startsWith("*") || line.startsWith("#") || line.isBlank()) continue
            rows++
            val f = line.split(",")
            if (f.size < 15) { bad++; continue }
            try {
                val cfg = String(Base64.decode(f.last().trim(), Base64.DEFAULT))
                val remote = Regex("(?m)^remote\\s+(\\S+)\\s+(\\d+)").find(cfg)
                if (remote == null) { bad++; continue }
                val host = remote.groupValues[1]
                if (!ipv4.matches(host)) { dom++; continue }          // IP-only, no domains
                val proto = Regex("(?m)^proto\\s+(\\w+)").find(cfg)?.groupValues?.get(1)?.lowercase()
                    ?.let { if (it.startsWith("tcp")) "tcp" else "udp" } ?: "udp"
                out += Server(host, remote.groupValues[2].toInt(), proto, f[5],
                    f[2].toLongOrNull() ?: 0, f[3], (f[4].toLongOrNull() ?: 0) / 1_000_000.0, cfg, f[6].trim())
            } catch (_: Exception) { bad++ }
        }
        val note = when {
            out.isNotEmpty() -> ""
            rows == 0 -> "not a VPN Gate CSV (starts with: " + text.take(40).replace('\n', ' ') + ")"
            else -> "0 usable (rows=$rows, domain-based=$dom, bad=$bad)"
        }
        return Fetched(src, out, note)
    }
}
