package com.securitycam.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.securitycam.R

/**
 * Live Camera Preview & Main Arm/Disarm UI.
 * Fully implemented in Milestone 5.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
