package com.shieldblock.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        UpdateJobService.schedule(ctx)
        val p = Prefs(ctx)
        if (p.enabled && p.autostart && VpnService.prepare(ctx) == null) {
            ctx.startForegroundService(
                Intent(ctx, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_START)
            )
        }
    }
}
