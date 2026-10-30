package com.securitycam.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.securitycam.R

/**
 * Configuration Screen for motion sensitivity, recipients, SMTP, and test alerts.
 * Fully implemented in Milestone 5.
 */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
    }
}
