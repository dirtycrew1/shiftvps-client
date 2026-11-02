@file:OptIn(KoinDelicateAPI::class, KoinExperimentalAPI::class)

package fr.husi.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import fr.husi.bg.SagerConnection
import fr.husi.compose.theme.AppTheme
import fr.husi.database.DataStore
import fr.husi.permission.LocalPermissionPlatform
import fr.husi.permission.rememberAndroidPermissionPlatform
import fr.husi.repository.resolveRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.koin.android.scope.AndroidScopeComponent
import org.koin.androidx.scope.activityRetainedScope
import org.koin.compose.scope.UnboundKoinScope
import org.koin.core.annotation.KoinDelicateAPI
import org.koin.core.annotation.KoinExperimentalAPI

class MainActivity : ComposeActivity(), AndroidScopeComponent {

    override val scope by activityRetainedScope()
    private val serviceConnection = SagerConnection(listenForDeath = true)

    override fun onCreate(savedInstanceState: Bundle?) {
        if (DataStore.nightTheme.getBlocking() != 1) DataStore.nightTheme.setBlocking(1)
        super.onCreate(savedInstanceState)

        serviceConnection.connect(applicationContext)
        lifecycleScope.launch(Dispatchers.IO) {
            DataStore.serviceMode.flow()
                .drop(1)
                .collect {
                    if (DataStore.serviceState.started) {
                        resolveRepository().reloadService()
                        serviceConnection.reconnect(applicationContext)
                    }
                }
        }

        setContent {
            val permissionPlatform = rememberAndroidPermissionPlatform()
            UnboundKoinScope(scope) {
                CompositionLocalProvider(
                    LocalPermissionPlatform provides permissionPlatform,
                ) {
                    AppTheme {
                        MainScreen()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceConnection.disconnect(applicationContext)
    }

}
