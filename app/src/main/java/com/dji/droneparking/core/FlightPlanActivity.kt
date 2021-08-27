package com.dji.droneparking.core


import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.dji.droneparking.R
import androidx.fragment.app.FragmentManager


class FlightPlanActivity : AppCompatActivity(){

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_DroneParking)
        setContentView(R.layout.activity_flight_plan_mapbox)

        val mapViewFragment = MapViewFragment()
        val videoStreamFragment = VideoStreamFragment()
        val manager: FragmentManager = supportFragmentManager
        val transaction = manager.beginTransaction()
        transaction.add(R.id.map_view_layout, mapViewFragment)
        transaction.add(R.id.video_view_layout, videoStreamFragment).addToBackStack(null)
        transaction.commit()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }

    }

}