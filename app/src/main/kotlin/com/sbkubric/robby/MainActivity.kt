package com.sbkubric.robby

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.sbkubric.robby.ui.RobbyApp

class MainActivity : ComponentActivity() {

    // Without this permission the service's notification is hidden from the shade.
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContainer.serviceLauncher.start()
        if (savedInstanceState == null) requestNotificationsIfNeeded()

        enableEdgeToEdge()
        setContent { RobbyApp() }
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestNotifications.launch(permission)
        }
    }
}
