package fr.husi.shiftvps

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/** Local-only classification. Installed app names are never sent to the portal. */
@Suppress("DEPRECATION")
internal fun installedGamingPackages(packageManager: PackageManager): Set<String> {
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return runCatching {
        packageManager.queryIntentActivities(launcher, 0).mapNotNullTo(mutableSetOf()) { entry ->
            val app = entry.activityInfo?.applicationInfo ?: return@mapNotNullTo null
            app.packageName.takeIf {
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && app.category == ApplicationInfo.CATEGORY_GAME) ||
                    app.flags and ApplicationInfo.FLAG_IS_GAME != 0
            }
        }
    }.getOrDefault(emptySet()) + gamingAndroidPackages
}
