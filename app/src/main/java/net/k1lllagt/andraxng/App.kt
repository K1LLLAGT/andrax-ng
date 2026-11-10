package net.k1lllagt.andraxng

import android.app.Application
import android.os.Build
import net.k1lllagt.andraxng.core.Logger
import net.k1lllagt.andraxng.core.NativeLibs
import net.k1lllagt.andraxng.core.Paths
import net.k1lllagt.andraxng.core.Settings

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val p = Paths(this)
        p.ensureDirs()
        paths = p
        log = Logger(p.logsDir)
        settings = Settings(this)
        NativeLibs.prepare(p, log)
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        log.i("ANDRAX-NG $ver started on ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT}), nativeDir=${p.nativeDir}")
    }

    companion object {
        lateinit var paths: Paths
            private set
        lateinit var log: Logger
            private set
        lateinit var settings: Settings
            private set
    }
}
