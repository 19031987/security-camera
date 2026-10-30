package com.securitycam.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.securitycam.R

/**
 * Event History Gallery displaying captured screenshots and alert delivery status.
 * Fully implemented in Milestone 5.
 */
class EventHistoryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
    }
}
