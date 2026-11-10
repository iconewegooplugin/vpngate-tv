package com.iconewegoo.vpngate

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import de.blinkt.openvpn.LaunchVPN
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VpnStatus
import java.io.StringReader

class MainActivity : AppCompatActivity(), VpnStatus.StateListener {
    private lateinit var store: Store
    private lateinit var status: TextView
    private lateinit var connView: TextView
    private lateinit var adapter: Adapter
    private var all = listOf<Server>()
    private var favs = mutableListOf<Server>()
    private var showFav = false
    private var svc: IOpenVPNServiceInternal? = null
    private var pending: Server? = null
    private var connected: Server? = null
    private var lastReport = "No refresh yet"

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, s: IBinder?) { svc = IOpenVPNServiceInternal.Stub.asInterface(s) }
        override fun onServiceDisconnected(n: ComponentName?) { svc = null }
    }

    override fun updateState(state: String?, msg: String?, res: Int, level: de.blinkt.openvpn.core.ConnectionStatus?, i: Intent?) {
        runOnUiThread {
            when (state) {
                "CONNECTED" -> { connected = store.current; status.text = "VPN: CONNECTED" }
                "NOPROCESS", "EXITING" -> { connected = null; status.text = "VPN: disconnected" }
                else -> status.text = "VPN: $state ${msg ?: ""}"
            }
            render()
        }
    }
    override fun setConnectedVPN(uuid: String?) {}

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.vg_main)
        store = Store(this)
        all = store.load("list"); favs = store.load("favs").toMutableList()   // no auto refresh
        status = findViewById(R.id.status)
        connView = findViewById(R.id.conn)
        adapter = Adapter()
        findViewById<RecyclerView>(R.id.list).apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = this@MainActivity.adapter }
        findViewById<View>(R.id.bRefresh).setOnClickListener { refresh() }
        findViewById<View>(R.id.bOvpn).setOnClickListener {
            try { startActivity(Intent().setClassName(packageName, "de.blinkt.openvpn.activities.MainActivity")) }
            catch (e: Exception) { status.text = "Cannot open the OpenVPN screen" }
        }
        findViewById<Button>(R.id.bFav).setOnClickListener { showFav = !showFav; (it as Button).text = if (showFav) "All" else "Favorites"; render() }
        findViewById<View>(R.id.bStop).setOnClickListener { try { svc?.stopVPN(false) } catch (_: Exception) { } }
        findViewById<View>(R.id.tg).setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/iconewegooplugin"))) }
        status.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Sources report").setMessage(lastReport).setPositiveButton("OK", null).show()
        }
        render()
        showLastCrash()
        VpnStatus.addStateListener(this)
        bindService(Intent(this, OpenVPNService::class.java).setAction(OpenVPNService.START_SERVICE), conn, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() { super.onDestroy(); VpnStatus.removeStateListener(this); try { unbindService(conn) } catch (_: Exception) { } }

    private fun render() {
        val c = connected
        val base = if (showFav) favs else all
        // the connected server is always pinned on top so it never gets lost
        adapter.items = if (c == null) base
            else if (showFav) (if (favs.any { it.key == c.key }) listOf(c) + favs.filter { it.key != c.key } else favs)
            else listOf(c) + all.filter { it.key != c.key }
        adapter.notifyDataSetChanged()
        if (c != null) {
            connView.text = "\u25CF Connected: ${c.flag} ${c.country}  ${c.ip}  ${c.proto.uppercase()}:${c.port}"
            connView.visibility = View.VISIBLE
        } else connView.visibility = View.GONE
        if (!showFav && all.isEmpty() && c == null) status.text = "List is empty - press Refresh"
    }

    private fun showLastCrash() {
        val t = CrashLog.read(this) ?: return
        AlertDialog.Builder(this).setTitle("Last crash (send this text)")
            .setMessage(t.take(2500))
            .setPositiveButton("Clear") { _, _ -> CrashLog.clear(this) }
            .setNegativeButton("Keep", null).show()
    }

    private fun refresh() {
        status.text = "Refreshing ..."
        Thread {
            val (r, report) = try {
                Api.fetchAll(Api.SOURCES) { d, n -> runOnUiThread { status.text = "Refreshing... $d/$n sources" } }
            } catch (e: Exception) { Pair(emptyList<Server>(), "Error: ${e.javaClass.simpleName} ${e.message}") }
            runOnUiThread {
                lastReport = report
                if (r.isEmpty()) status.text = "No servers (old list kept) - press here for the report"
                else {
                    all = r; store.save("list", r); render()
                    status.text = "${r.size} unique servers from ${Api.SOURCES.size} sources - press here for the report"
                }
            }
        }.start()
    }

    private fun connect(s: Server) {
        val prep = VpnService.prepare(this)
        if (prep != null) { pending = s; startActivityForResult(prep, 1); return }
        try {
            val cp = ConfigParser()
            cp.parseConfig(StringReader(s.config))
            val vp: VpnProfile = cp.convertProfile()
            vp.mUsername = "vpn"; vp.mPassword = "vpn"      // default VPN Gate credentials
            val pm = ProfileManager.getInstance(this)
            try { pm.getProfileByName("VPNGate TV")?.let { pm.removeProfile(this, it) } } catch (_: Exception) { }
            vp.mName = "VPNGate TV"
            pm.addProfile(vp)
            ProfileManager.saveProfile(this, vp)
            pm.saveProfileList(this)
            store.current = s
            val i = Intent(this, LaunchVPN::class.java)
            i.putExtra(LaunchVPN.EXTRA_KEY, vp.uuidString)
            i.putExtra(LaunchVPN.EXTRA_HIDELOG, true)
            i.action = Intent.ACTION_MAIN
            startActivity(i)
            status.text = "Connecting to ${s.flag} ${s.ip} (${s.proto.uppercase()}:${s.port})"
        } catch (e: Exception) { status.text = "Error: ${e.message}" }
    }

    override fun onActivityResult(rq: Int, rs: Int, d: Intent?) {
        super.onActivityResult(rq, rs, d)
        if (rs == Activity.RESULT_OK) pending?.let { connect(it) } else status.text = "Permission denied"
        pending = null
    }

    private fun toggleFav(s: Server) {
        if (favs.any { it.key == s.key }) favs.removeAll { it.key == s.key } else favs.add(s)
        store.save("favs", favs); render()
    }

    inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        var items: List<Server> = emptyList()
        inner class VH(v: View) : RecyclerView.ViewHolder(v) { val t: TextView = v.findViewById(R.id.t); val s: ImageButton = v.findViewById(R.id.s) }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.vg_item, p, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val s = items[i]
            val isConn = connected?.key == s.key
            h.t.text = (if (isConn) "\u25CF CONNECTED   " else "") +
                "${s.flag} ${s.country}   ${s.ip}   ${s.proto.uppercase()}:${s.port}   ${"%.1f".format(s.speedMbps)} Mbps   ${s.ping}ms"
            h.t.setTextColor(if (isConn) 0xFF69F0AE.toInt() else 0xFFFFFFFF.toInt())
            h.s.setImageResource(if (favs.any { it.key == s.key }) R.drawable.vg_star_on else R.drawable.vg_star_off)
            h.t.setOnClickListener { connect(s) }
            h.s.setOnClickListener { toggleFav(s) }
        }
    }
}
