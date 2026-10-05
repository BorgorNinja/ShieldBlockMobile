package com.shieldblock.mobile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var toggle: Button
    private lateinit var updateBtn: Button
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(24))
            setBackgroundColor(Color.parseColor("#0B1220"))
        }

        col.addView(TextView(this).apply {
            text = "ShieldBlock Mobile"
            textSize = 26f
            setTextColor(Color.WHITE)
        })
        col.addView(TextView(this).apply {
            text = "uBlock Origin filter lists, applied through a local VPN"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(4), 0, dp(24))
        })

        status = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
        }
        col.addView(status)

        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        col.addView(toggle)

        stats = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, dp(20), 0, dp(12))
        }
        col.addView(stats)

        updateBtn = Button(this).apply {
            text = "Update filter lists now"
            setOnClickListener { updateNow() }
        }
        col.addView(updateBtn)

        col.addView(Switch(this).apply {
            text = "Update lists on Wi-Fi only"
            setTextColor(Color.WHITE)
            isChecked = prefs.wifiOnly
            setPadding(0, dp(16), 0, dp(4))
            setOnCheckedChangeListener { _, on ->
                prefs.wifiOnly = on
                UpdateJobService.schedule(this@MainActivity, true)
            }
        })
        col.addView(Switch(this).apply {
            text = "Start protection on boot"
            setTextColor(Color.WHITE)
            isChecked = prefs.autostart
            setPadding(0, dp(8), 0, dp(4))
            setOnCheckedChangeListener { _, on -> prefs.autostart = on }
        })

        col.addView(Switch(this).apply {
            text = "YouTube ads (experimental)"
            setTextColor(Color.WHITE)
            isChecked = prefs.youtubeAds
            setPadding(0, dp(8), 0, dp(4))
            setOnCheckedChangeListener { _, on ->
                prefs.youtubeAds = on
                applyListChange()
            }
        })
        col.addView(TextView(this).apply {
            text = "Blocks known YouTube ad-server hostnames. Some ads will still play, and a video " +
                "may occasionally fail to load. If that happens, switch this off or add the " +
                "failing hostname to the allowlist."
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        })

        col.addView(Button(this).apply {
            text = "Allowlist (domains never blocked)"
            setOnClickListener { editAllowlist() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        col.addView(TextView(this).apply {
            text = "How it works: DNS lookups are checked against uBlock's network filters " +
                "(||domain^ rules and host lists). Blocked names resolve to nothing. " +
                "Cosmetic filters, scriptlets and URL-path rules need a browser extension and " +
                "are not applied.\n\nIf blocking seems inactive: turn off Private DNS " +
                "(Settings > Network) and \"Secure DNS\" in Chrome, and disable other VPN apps."
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(24), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B1220"))
            addView(col)
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }

        UpdateJobService.schedule(this)
        if (!Updater.hasLists(this)) updateNow()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun refresh() {
        val on = ShieldVpnService.running
        status.text = if (on) "● Protection ON" else "○ Protection OFF"
        status.setTextColor(Color.parseColor(if (on) "#22C55E" else "#EF4444"))
        toggle.text = if (on) "Stop protection" else "Start protection"
        val last = prefs.lastUpdate
        val lastText = if (last == 0L) "never" else DateFormat.getDateTimeInstance().format(Date(last))
        stats.text = "Blocked domains in lists: ${prefs.ruleCount}\n" +
            "Filter lists: ${prefs.listCount}\n" +
            "Last update: $lastText\n" +
            "Blocked this session: ${ShieldVpnService.blocked.get()} of ${ShieldVpnService.queries.get()} DNS queries"
    }

    @Suppress("DEPRECATION")
    private fun onToggle() {
        if (ShieldVpnService.running) {
            prefs.enabled = false
            startService(Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_STOP))
        } else {
            val i = VpnService.prepare(this)
            if (i != null) {
                startActivityForResult(i, 1)
            } else {
                startVpn()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) startVpn()
    }

    private fun startVpn() {
        prefs.enabled = true
        startForegroundService(Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_START))
    }

    private fun updateNow() {
        updateBtn.isEnabled = false
        updateBtn.text = "Updating…"
        Thread({
            val r = Updater.update(this, true)
            val svc = ShieldVpnService.instance
            if (svc != null) svc.reload() else Lists.load(this) // refreshes the rule counter
            runOnUiThread {
                updateBtn.isEnabled = true
                updateBtn.text = "Update filter lists now"
                val msg = r.error
                    ?: "Updated ${r.updated}, up to date ${r.unchanged}, failed ${r.failed}"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }, "shield-update").start()
    }

    /** Re-syncs downloaded lists with the toggles (downloads or removes the optional list). */
    private fun applyListChange() {
        Thread({
            Updater.update(this, false)
            val svc = ShieldVpnService.instance
            if (svc != null) svc.reload() else Lists.load(this)
            runOnUiThread {
                Toast.makeText(this, "Filter lists updated", Toast.LENGTH_SHORT).show()
            }
        }, "shield-apply").start()
    }

    private fun editAllowlist() {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(prefs.allowlist)
            hint = "example.com\nanother.org"
            minLines = 5
            gravity = Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle("Allowlist (one domain per line)")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                prefs.allowlist = et.text.toString()
                ShieldVpnService.instance?.reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
