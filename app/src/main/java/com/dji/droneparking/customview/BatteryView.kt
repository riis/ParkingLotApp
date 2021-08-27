package com.dji.droneparking.customview

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.dji.droneparking.R
import dji.common.battery.ConnectionState
import dji.ux.model.base.BaseDynamicWidgetAppearances
import dji.ux.widget.BatteryWidget

import android.content.res.TypedArray
import androidx.core.content.ContextCompat

import android.view.animation.LinearInterpolator

import android.R.animator
import android.animation.ValueAnimator
import android.graphics.*


/**
 * Override default battery widget with custom UI resources and logic
 */
class BatteryView @JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) :
    BatteryWidget(context, attrs, defStyle) {
    private var waveView:WaveView? = null
    private var batteryIconErrorRes = 0


    /** Inflate custom layout for this widget  */
    override fun initView(context: Context, attrs: AttributeSet, defStyle: Int) {
        val inflater = LayoutInflater.from(context)
        val view: View = inflater.inflate(R.layout.battery_view, this, true)
        waveView = view.findViewById(R.id.battery_level)
    }

    override fun getWidgetAppearances(): BaseDynamicWidgetAppearances? {
        return null
    }

    /** Called when battery percentage changes  */
    override fun onBatteryPercentageChange(percentage: Int) {
        waveView?.setProgress(percentage.toFloat())
//        batteryValue!!.text = "$percentage%"
//        when (percentage) {
//            in 1..4 -> {
////                batteryIconRes = R.drawable.mapbox_compass_icon
//            }
//            in 5..14 -> {
////                batteryIconRes = R.drawable.mapbox_compass_icon
//            }
//            in 15..24 -> {
////                batteryIconRes = R.drawable.mapbox_compass_icon
//            }
//            in 25..34 -> {
////                batteryIconRes = R.drawable.mapbox_compass_icon
//            }
//            in 35..44 -> {
////                batteryIconRes = R.drawable.mapbox_compass_icon
//            }
//            in 45..54 -> {
////                batteryIconRes = R.drawable.mapbox_compass_icon
//            }
//            in 55..64 -> {
////                batteryIconRes = R.drawable.mapbox_compass_icon
//            }
//            in 65..74 -> {
////                batteryIconRes = R.drawable.mapbox_compass_icon
//            }
//            in 75..84 -> {
////                batteryIconRes = R.drawable.mapbox_compass_icon
//            }
//            in 85..94 -> {
////                batteryIconRes = R.drawable.mapbox_compass_icon
//            }
//            in 95..100 -> {
////                batteryIconRes = R.drawable.mapbox_compass_icon
//            }
//        }
        updateBatteryIcon()
    }

    /** Called when battery state changes from error to normal or vice versa  */
    override fun onBatteryConnectionStateChange(status: ConnectionState?) {
        batteryIconErrorRes = if (status != ConnectionState.NORMAL) {
            1
        } else {
            0
        }
        updateBatteryIcon()
    }

    private fun updateBatteryIcon() {
        if (batteryIconErrorRes != 0) {
//            batteryIcon!!.setImageResource(batteryIconErrorRes)
        } else {
//            batteryIcon!!.setImageResource(batteryIconRes)
        }
    }
}