package com.danav.chat

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danav.chat.call.CallManager
import com.danav.chat.data.Realtime
import com.danav.chat.data.Repo
import com.danav.chat.push.Notifier
import com.danav.chat.ui.DanavApp
import com.danav.chat.ui.theme.DanavTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var incomingPeer by mutableStateOf<String?>(null)
    private var incomingGroup by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Repo.init(applicationContext)
        CallManager.init(applicationContext)
        Notifier.createChannels(this)
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            val theme by Repo.themeMode.collectAsStateWithLifecycle()
            DanavTheme(theme) { DanavApp(incomingPeer, incomingGroup, onOpenHandled = { incomingPeer = null }, onGroupHandled = { incomingGroup = null }) }
        }
        lifecycleScope.launch { if (Repo.loggedIn) Repo.refreshMe() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        incomingPeer = intent?.getStringExtra("openChat") ?: intent?.getStringExtra("peer")
        incomingGroup = intent?.getStringExtra("openGroup")
        if (intent?.action == com.danav.chat.call.CallActivity.ACTION_ACCEPT) {
            lifecycleScope.launch { kotlinx.coroutines.delay(300); CallManager.accept() }
        }
    }

    override fun onStart() {
        super.onStart()
        Repo.foreground = true
        if (Repo.loggedIn) {
            Realtime.hold("fg")
            lifecycleScope.launch { Repo.sync(); Repo.registerFcm() }
        }
    }

    override fun onStop() {
        Repo.foreground = false
        Repo.openChat.value = null
        Realtime.release("fg")
        super.onStop()
    }
}
