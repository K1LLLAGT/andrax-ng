package net.k1lllagt.andraxng.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import net.k1lllagt.andraxng.App
import net.k1lllagt.andraxng.core.DeviceInfo
import net.k1lllagt.andraxng.core.Diagnostics
import net.k1lllagt.andraxng.core.NativeLibs
import net.k1lllagt.andraxng.core.Profile
import net.k1lllagt.andraxng.core.Profiles
import net.k1lllagt.andraxng.core.RootfsInstaller
import net.k1lllagt.andraxng.core.SafeFs

class SettingsActivity : Activity() {
    private lateinit var col: LinearLayout
    private lateinit var modeGroup: RadioGroup
    private lateinit var shared: EditText
    private lateinit var dns1: EditText
    private lateinit var dns2: EditText
    private lateinit var shell: EditText
    private lateinit var hostname: EditText
    private lateinit var keep: Switch
    private lateinit var wake: Switch
    private lateinit var info: TextView
    private val modeIds = HashMap<Int, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Settings"
        val pg = Ui.page(this)
        col = pg.col
        setContentView(pg.scroll)
        build()
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    private fun field(label: String, value: String, hint: String): EditText {
        col.addView(Ui.body(this, label))
        val e = EditText(this)
        e.setSingleLine(true)
        e.hint = hint
        e.setText(value)
        col.addView(e)
        return e
    }

    private fun build() {
        val s = App.settings
        col.addView(Ui.title(this, "Settings"))

        col.addView(Ui.heading(this, "Mode"))
        modeGroup = RadioGroup(this)
        for ((value, label) in listOf(
            "auto" to "Auto (chroot if root works, otherwise proot)",
            "proot" to "Always proot (no root needed)",
            "chroot" to "Always chroot (needs root)"
        )) {
            val rb = RadioButton(this)
            rb.id = View.generateViewId()
            rb.text = label
            modeGroup.addView(rb)
            modeIds[rb.id] = value
            if (s.modeOverride == value) rb.isChecked = true
        }
        col.addView(modeGroup)

        col.addView(Ui.heading(this, "Rootfs"))
        shared = field("Shared folder (appears as /sdcard inside; empty = none)", s.sharedFolder, "/storage/emulated/0/ANDRAX-NG-share")
        dns1 = field("DNS server 1", s.dns1, "1.1.1.1")
        dns2 = field("DNS server 2", s.dns2, "8.8.8.8")
        shell = field("Shell inside the rootfs", s.shell, "/bin/bash")
        hostname = field("Hostname", s.hostname, "andrax")

        keep = Switch(this)
        keep.text = "Keep downloaded archive after install"
        keep.isChecked = s.keepArchive
        col.addView(keep)
        wake = Switch(this)
        wake.text = "Hold a wake lock while sessions run"
        wake.isChecked = s.wakeLock
        col.addView(wake)

        col.addView(Ui.button(this, "Save") {
            save()
            Toast.makeText(this, "Saved. New values apply to the next session.", Toast.LENGTH_SHORT).show()
        })

        col.addView(Ui.heading(this, "Tools"))
        info = Ui.mono(this, "")
        col.addView(info)
        col.addView(Ui.button(this, "Re-detect root") {
            info.text = "Checking root..."
            Thread {
                val r = DeviceInfo.probeRoot()
                runOnUiThread { info.text = "root: $r  su=${DeviceInfo.suPath}" }
            }.start()
        })
        col.addView(Ui.button(this, "Test proot helper") {
            info.text = "Testing..."
            Thread {
                val r = NativeLibs.selfTest(App.paths)
                runOnUiThread { info.text = r }
            }.start()
        })
        col.addView(Ui.button(this, "Unmount leftover chroot mounts") { unmountLeftovers() })
        col.addView(Ui.button(this, "Export logs") { exportLogs() })
        col.addView(Ui.button(this, "Allow background running (battery)") { askBattery() })
        col.addView(Ui.button(this, "Phantom process killer help") {
            AlertDialog.Builder(this).setTitle("Background running").setMessage(HelpText.BACKGROUND)
                .setPositiveButton("Close", null).show()
        })

        col.addView(Ui.heading(this, "Danger zone"))
        col.addView(Ui.button(this, "Reset / delete a rootfs...") { chooseRootfsToDelete() })
    }

    private fun save() {
        if (!::modeGroup.isInitialized) return
        val s = App.settings
        s.modeOverride = modeIds[modeGroup.checkedRadioButtonId] ?: "auto"
        s.sharedFolder = shared.text.toString().trim()
        s.dns1 = dns1.text.toString().trim().ifEmpty { "1.1.1.1" }
        s.dns2 = dns2.text.toString().trim().ifEmpty { "8.8.8.8" }
        s.shell = shell.text.toString().trim().ifEmpty { "/bin/bash" }
        s.hostname = hostname.text.toString().trim().ifEmpty { "andrax" }
        s.keepArchive = keep.isChecked
        s.wakeLock = wake.isChecked
    }

    private fun askBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already allowed", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun unmountLeftovers() {
        info.text = "Looking for leftover mounts..."
        Thread {
            val lines = ArrayList<String>()
            for (p in Profiles.all) {
                val root = App.paths.rootfs(p)
                if (!root.exists()) continue
                val before = SafeFs.mountsUnder(root)
                if (before.isNotEmpty()) SafeFs.unmountAll(root, App.log)
                val after = SafeFs.mountsUnder(root)
                lines.add("${p.id}: ${before.size} mount(s) found, ${after.size} left")
            }
            runOnUiThread { info.text = if (lines.isEmpty()) "No rootfs installed." else lines.joinToString("\n") }
        }.start()
    }

    private fun exportLogs() {
        info.text = "Building report..."
        Thread {
            try {
                val f = Diagnostics.buildReport(App.paths, App.settings, App.log)
                val uri = FileProvider.getUriForFile(this, "net.k1lllagt.andraxng.logs", f)
                runOnUiThread {
                    info.text = "Report: ${f.path}"
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(send, "Export ANDRAX-NG logs"))
                }
            } catch (e: Exception) {
                App.log.e("export failed", e)
                runOnUiThread { info.text = "Export failed: ${e.message}" }
            }
        }.start()
    }

    private fun chooseRootfsToDelete() {
        val installed = Profiles.all.filter { App.paths.isInstalled(it) || App.paths.partial(it).exists() }
        if (installed.isEmpty()) {
            Toast.makeText(this, "Nothing installed", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete which rootfs?")
            .setItems(installed.map { it.title }.toTypedArray()) { _, i -> confirmDelete(installed[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(p: Profile) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${p.title}?")
            .setMessage("Everything inside it is removed, including files you created there. The shared folder is not touched. You can install it again from setup.")
            .setPositiveButton("Delete") { _, _ -> deleteRootfs(p) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteRootfs(p: Profile) {
        info.text = "Deleting ${p.title}..."
        Thread {
            val msg = try {
                RootfsInstaller(applicationContext, App.paths, App.settings, App.log).remove(p)
                "Deleted ${p.title}. Run setup to install again."
            } catch (e: Exception) {
                App.log.e("delete failed", e)
                "Delete failed: ${e.message}"
            }
            runOnUiThread { info.text = msg }
        }.start()
    }
}
