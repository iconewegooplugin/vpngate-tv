package com.iconewegoo.vpngate

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.net.VpnService
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VpnStatus
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.R
import java.io.StringReader

class MainActivity : AppCompatActivity(), VpnStatus.StateListener {
    private lateinit var store: Store
    private lateinit var status: TextView
    private lateinit var adapter: Adapter
    private var all = listOf<Server>()
    private var favs = mutableListOf<Server>()
    private var showFav = false
    private var svc: IOpenVPNServiceInternal? = null
    private var pending: Server? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, s: IBinder?) { svc = IOpenVPNServiceInternal.Stub.asInterface(s) }
        override fun onServiceDisconnected(n: ComponentName?) { svc = null }
    }

    override fun updateState(state: String?, msg: String?, res: Int, level: de.blinkt.openvpn.core.ConnectionStatus?, i: Intent?) {
        runOnUiThread { status.text = "VPN: $state ${msg ?: ""}" }
    }
    override fun setConnectedVPN(uuid: String?) {}

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.vg_main)
        store = Store(this)
        all = store.load("list"); favs = store.load("favs").toMutableList()   // no auto refresh
        status = findViewById(R.id.status)
        adapter = Adapter()
        findViewById<RecyclerView>(R.id.list).apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = this@MainActivity.adapter }
        findViewById<View>(R.id.bRefresh).setOnClickListener { refresh() }
        findViewById<View>(R.id.bMirrors).setOnClickListener { editMirrors() }
        findViewById<Button>(R.id.bFav).setOnClickListener { showFav = !showFav; (it as Button).text = if (showFav) "All" else "Favorites"; render() }
        findViewById<View>(R.id.bStop).setOnClickListener { try { svc?.stopVPN(false) } catch (_: Exception) { } }
        findViewById<View>(R.id.tg).setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/iconewegooplugin"))) }
        render()
        VpnStatus.addStateListener(this)
        bindService(Intent(this, OpenVPNService::class.java).setAction(OpenVPNService.START_SERVICE), conn, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() { super.onDestroy(); VpnStatus.removeStateListener(this); try { unbindService(conn) } catch (_: Exception) { } }

    private fun render() {
        adapter.items = if (showFav) favs else all
        adapter.notifyDataSetChanged()
        if (!showFav && all.isEmpty()) status.text = "List is empty — press Refresh"
    }

    private fun editMirrors() {
        val et = EditText(this).apply {
            setText(store.mirrors); minLines = 4; gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
            setSelection(text.length)
        }
        AlertDialog.Builder(this).setTitle("Mirrors (one per line: http://IP:PORT)").setView(et)
            .setPositiveButton("Save") { _, _ -> store.mirrors = et.text.toString() }
            .setNeutralButton("Add line") { _, _ -> store.mirrors = et.text.toString().trimEnd() + "\nhttp://"; editMirrors() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun refresh() {
        val mirrors = Api.mirrorList(store.mirrors)
        if (mirrors.isEmpty()) { status.text = "Add a mirror first"; editMirrors(); return }
        status.text = "Refreshing ..."
        Thread {
            try {
                val r = Api.fetchFirstWorking(mirrors) { m -> runOnUiThread { status.text = "Trying $m ..." } }
                runOnUiThread { all = r; store.save("list", r); render(); status.text = "${r.size} servers (IP-only)" }
            } catch (e: Exception) {
                runOnUiThread { status.text = "All mirrors failed - old list kept" }
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
            vp.mName = "VPNGate ${s.ip}"
            vp.mUsername = "vpn"; vp.mPassword = "vpn"      // default VPN Gate credentials
            ProfileManager.setTemporaryProfile(this, vp)
            VPNLaunchHelper.startOpenVPN(vp, applicationContext)
            status.text = "Connecting to ${s.ip} (${s.proto.uppercase()}:${s.port})"
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
            h.t.text = "${s.country}   ${s.ip}   ${s.proto.uppercase()}:${s.port}   ${"%.1f".format(s.speedMbps)} Mbps   ${s.ping}ms"
            h.s.setImageResource(if (favs.any { it.key == s.key }) R.drawable.vg_star_on else R.drawable.vg_star_off)
            h.t.setOnClickListener { connect(s) }
            h.s.setOnClickListener { toggleFav(s) }
        }
    }
}
