package com.example.zapalert

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val requiredPermissions = mutableListOf<String>().apply {
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.READ_CALL_LOG)
        }
    }

    private var currentPermissionIndex = 0
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "${requiredPermissions[currentPermissionIndex]} granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "${requiredPermissions[currentPermissionIndex]} denied", Toast.LENGTH_SHORT).show()
        }

        currentPermissionIndex++
        if (currentPermissionIndex < requiredPermissions.size) {
            requestNextPermission()
        } else {
            // All permissions requested, start service
            startService()
            checkNotificationPolicyAccess()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startService()
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Notification permission granted",
                Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Notification permission denied",
                Toast.LENGTH_SHORT).show()
        }
    }

    private val requestCallLogPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Call logs permission granted",
                Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Call logs permission denied",
                Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val settingsButton: Button = findViewById(R.id.settingsButton)
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        currentPermissionIndex = 0
        if (requiredPermissions.isNotEmpty()) {
            requestNextPermission()
        } else {
            startService()
            checkNotificationPolicyAccess()
        }
    }

    private fun requestNextPermission() {
        val permission = requiredPermissions[currentPermissionIndex]
        if (ContextCompat.checkSelfPermission(this,
                permission) == PackageManager.PERMISSION_GRANTED) {
            currentPermissionIndex++
            if (currentPermissionIndex < requiredPermissions.size) {
                requestNextPermission()
            } else {
                startService()
                checkNotificationPolicyAccess()
            }
        } else {
            permissionLauncher.launch(permission)
        }
    }

    private fun checkNotificationPolicyAccess() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            AlertDialog.Builder(this)
                .setTitle("DND Access Required")
                .setMessage("Please enable notification access for proper call alerts")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }
                .show()
        }
    }

    private fun startService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, CallNotificationService::class.java))
        } else {
            startService(Intent(this, CallNotificationService::class.java))
        }
    }
}