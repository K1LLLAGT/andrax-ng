package net.k1lllagt.andraxng

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import net.k1lllagt.andraxng.core.DeviceInfo
import net.k1lllagt.andraxng.core.InstallState
import net.k1lllagt.andraxng.core.Mode
import net.k1lllagt.andraxng.core.Profiles
import net.k1lllagt.andraxng.core.RootStatus
import net.k1lllagt.andraxng.ui.LimitsActivity
import net.k1lllagt.andraxng.ui.SettingsActivity
import net.k1lllagt.andraxng.ui.TerminalActivity
import net.k1lllagt.andraxng.ui.Ui
import net.k1lllagt.andraxng.ui.WizardActivity

class MainActivity : Activity() {
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ANDRAX-NG"
        val pg = Ui.page(this)
        col = pg.col
        setContentView(pg.scroll)
        if (DeviceInfo.rootStatus == RootStatus.UNKNOWN) {
            Thread {
                DeviceInfo.probeRoot()
                runOnUiThread { if (!isFinishing) render() }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        val none = Profiles.all.none { App.paths.isInstalled(it) }
        if (none && !autoShown && !InstallState.snapshot.running) {
            autoShown = true
            startActivity(Intent(this, WizardActivity::class.java))
        }
    }

    private fun modeLabel(): String {
        val m = DeviceInfo.effectiveMode(App.settings)
        val how = if (App.settings.modeOverride == "auto") "auto" else "manual"
        return (if (m == Mode.CHROOT) "chroot" else "proot") + " ($how)"
    }

    private fun render() {
        col.removeAllViews()
        col.addView(Ui.title(this, "ANDRAX-NG"))
        col.addView(Ui.body(this, "${DeviceInfo.model}  |  mode: ${modeLabel()}  |  root: ${DeviceInfo.rootStatus.name.lowercase()}"))

        val installed = Profiles.all.filter { App.paths.isInstalled(it) }
        col.addView(Ui.heading(this, "Open a terminal"))
        if (installed.isEmpty()) {
            col.addView(Ui.body(this, "No rootfs is installed yet. Use setup below."))
        }
        for (p in installed) {
            col.addView(Ui.button(this, "Open " + p.title) {
                startActivity(Intent(this, TerminalActivity::class.java).putExtra(TerminalActivity.EXTRA_PROFILE, p.id))
            })
        }

        if (InstallState.snapshot.running) {
            col.addView(Ui.heading(this, "Install in progress"))
            col.addView(Ui.button(this, "Show progress") { startActivity(Intent(this, WizardActivity::class.java)) })
        }

        col.addView(Ui.heading(this, "Manage"))
        col.addView(Ui.button(this, "Set up or reinstall a rootfs") { startActivity(Intent(this, WizardActivity::class.java)) })
        col.addView(Ui.button(this, "Settings") { startActivity(Intent(this, SettingsActivity::class.java)) })
        col.addView(Ui.button(this, "Honest limits") { startActivity(Intent(this, LimitsActivity::class.java)) })
    }

    companion object {
        private var autoShown = false
    }
}
