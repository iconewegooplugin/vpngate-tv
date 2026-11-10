package com.iconewegoo.vpngate

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Server(val ip: String, val port: Int, val proto: String, val country: String,
                  val score: Long, val ping: String, val speedMbps: Double, val config: String) {
    val key get() = "$ip:$port:$proto"
    fun toJson() = JSONObject().put("ip", ip).put("port", port).put("proto", proto).put("country", country)
        .put("score", score).put("ping", ping).put("speed", speedMbps).put("config", config)
    companion object {
        fun from(o: JSONObject) = Server(o.getString("ip"), o.getInt("port"), o.getString("proto"), o.getString("country"),
            o.getLong("score"), o.getString("ping"), o.getDouble("speed"), o.getString("config"))
    }
}

class Store(c: Context) {
    private val p = c.getSharedPreferences("vpngate", 0)
    fun load(k: String): List<Server> = try {
        val a = JSONArray(p.getString(k, "[]")); (0 until a.length()).map { Server.from(a.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }
    fun save(k: String, l: List<Server>) = p.edit().putString(k, JSONArray(l.map { it.toJson() }).toString()).apply()
    var mirrors: String
        get() = p.getString("mirrors", "http://") ?: "http://"
        set(v) = p.edit().putString("mirrors", v).apply()
}

object Api {
    private val ipv4 = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")

    fun mirrorList(raw: String) = raw.lines().map { it.trim() }
        .filter { it.startsWith("http://") && it.length > 10 }

    /** Tries each mirror in order; first one that returns a valid list wins. */
    fun fetchFirstWorking(mirrors: List<String>, onTry: (String) -> Unit): List<Server> {
        for (m in mirrors) {
            onTry(m)
            try {
                val r = fetch(m)
                if (r.isNotEmpty()) return r
            } catch (_: Exception) { }
        }
        throw Exception("No mirror responded")
    }

    private fun fetch(mirror: String): List<Server> {
        val c = URL(mirror.trimEnd('/') + "/api/iphone/").openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 20000
        val text = c.inputStream.bufferedReader().readText()
        val out = ArrayList<Server>()
        for (line in text.lines()) {
            if (line.startsWith("*") || line.startsWith("#") || line.isBlank()) continue
            val f = line.split(",")
            if (f.size < 15) continue
            try {
                val cfg = String(Base64.decode(f.last().trim(), Base64.DEFAULT))
                val remote = Regex("(?m)^remote\\s+(\\S+)\\s+(\\d+)").find(cfg) ?: continue
                val host = remote.groupValues[1]
                if (!ipv4.matches(host)) continue            // IP-only, no domains
                val proto = Regex("(?m)^proto\\s+(\\w+)").find(cfg)?.groupValues?.get(1)?.lowercase()
                    ?.let { if (it.startsWith("tcp")) "tcp" else "udp" } ?: "udp"
                out += Server(host, remote.groupValues[2].toInt(), proto, f[5],
                    f[2].toLongOrNull() ?: 0, f[3], (f[4].toLongOrNull() ?: 0) / 1_000_000.0, cfg)
            } catch (_: Exception) { }
        }
        return out.sortedByDescending { it.score }
    }

    /** Default credentials vpn/vpn, so the user is never asked. */
    fun withCreds(cfg: String): String =
        if (Regex("(?m)^auth-user-pass").containsMatchIn(cfg))
            cfg.replace(Regex("(?m)^auth-user-pass.*$"), "") + "\n<auth-user-pass>\nvpn\nvpn\n</auth-user-pass>\n"
        else cfg
}
