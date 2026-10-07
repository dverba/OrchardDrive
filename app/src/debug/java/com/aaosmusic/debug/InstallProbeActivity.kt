package com.aaosmusic.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Debug-only: hands this app's own APK to the system installer exactly like a file manager does
 * (ACTION_VIEW + content URI), to see what AAOS does with sideload requests.
 * adb shell am start -n com.aaosmusic/.debug.InstallProbeActivity
 */
class InstallProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val apk = File(cacheDir, "probe/probe.apk").apply { parentFile!!.mkdirs() }
        File(applicationInfo.sourceDir).copyTo(apk, overwrite = true)
        Log.i("AAOSMusic/APP", "InstallProbe: canRequestPackageInstalls=${packageManager.canRequestPackageInstalls()}")
        val uri = FileProvider.getUriForFile(this, "$packageName.probe", apk)
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        finish()
    }
}
