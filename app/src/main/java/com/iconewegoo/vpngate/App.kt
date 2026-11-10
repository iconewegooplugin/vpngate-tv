package com.iconewegoo.vpngate

import android.app.Application
import de.blinkt.openvpn.core.PRNGFixes
import de.blinkt.openvpn.core.StatusListener

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PRNGFixes.apply()
        StatusListener().init(applicationContext)
    }
}
