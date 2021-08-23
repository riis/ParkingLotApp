package com.dji.droneparking.core

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.dji.droneparking.R
import com.dji.droneparking.viewmodel.ConnectionViewModel
import dji.sdk.sdkmanager.DJISDKManager
import kotlinx.coroutines.*

class ConnectionFragment : Fragment() {
    private lateinit var mTextConnectionStatus: TextView
//    private lateinit var mBtnOpen: Button
    private lateinit var mVersionTv: TextView
    private lateinit var animFadeIn: Animation
    private lateinit var animFadeOut: Animation

    private val model: ConnectionViewModel by viewModels()

    companion object {
        const val TAG = "ConnectionActivity"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_connection, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        animFadeIn = AnimationUtils.loadAnimation(context, R.anim.fadein)
        animFadeOut = AnimationUtils.loadAnimation(context, R.anim.fadeout)


        mTextConnectionStatus = view.findViewById(R.id.text_connection_status)
//        mBtnOpen = view.findViewById(R.id.btn_open)
        mVersionTv = view.findViewById(R.id.textView2)


        mVersionTv.text =
            resources.getString(R.string.sdk_version, DJISDKManager.getInstance().sdkVersion)
//        mBtnOpen.isEnabled = false
//        mBtnOpen.setOnClickListener {
//            val intent = Intent(context, FlightPlanActivity::class.java)
//            this.startActivity(intent)
//        }

        initUI()
        model.registerApp()
        observers()
    }

    private fun initUI() {

        var flag = false
        var job : Job? = null
        job  = lifecycleScope.launch(Dispatchers.IO) {
//            delay(10000)
            val tutorialFragment = TutorialFragment()
//            activity?.supportFragmentManager?.beginTransaction()
//                ?.replace(R.id.frameLayoutFragment, tutorialFragment, "tutorial")?.commit()
//
//            job?.cancel()

            while (true) {
                if (!model.rCConnected) {
                    withContext(Dispatchers.Main) {
                        mTextConnectionStatus.startAnimation(animFadeOut)
                        delay(2000)
                        mTextConnectionStatus.startAnimation(animFadeIn)
                        delay(2000)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        mTextConnectionStatus.setTextColor(Color.GREEN)
                        mTextConnectionStatus.text = "Connected to RC ✓"
                        delay(1000)
                        mTextConnectionStatus.setTextColor(Color.BLACK)
                        flag = true
                    }
                    break
                }
            }
            while (true) {
                if (!model.droneConnected) {
                    withContext(Dispatchers.Main) {
                        mTextConnectionStatus.text = "Connecting to Drone"
                        mTextConnectionStatus.startAnimation(animFadeOut)
                        delay(2000)
                        mTextConnectionStatus.startAnimation(animFadeIn)
                        delay(2000)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        mTextConnectionStatus.setTextColor(Color.GREEN)
                        mTextConnectionStatus.text = "Connected to ${model.droneName}  ✓"
                        delay(1000)
                        mTextConnectionStatus.setTextColor(Color.BLACK)
                    }
                    break
                }
            }
            activity?.supportFragmentManager?.beginTransaction()
                ?.replace(R.id.frameLayoutFragment, tutorialFragment, "tutorial")?.commit()
        }
    }

    private fun observers() {
        //this handles connections to the rc controller
        model.connectionStatus.observe(viewLifecycleOwner, { isConnected ->
            if (isConnected) {
                mTextConnectionStatus.setTextColor(Color.GREEN)
                mTextConnectionStatus.text = "Connected to RC ✓"
//                mTextConnectionStatus.setTextColor(Color.BLACK)
//                mBtnOpen.isEnabled = true
                model.rCConnected = true
            } else {
                mTextConnectionStatus.text = "Connecting to RC"
                model.rCConnected = false
//                mBtnOpen.isEnabled = false
            }
        })

        //this handles connection to the drone
        model.product.observe(viewLifecycleOwner, { baseProduct ->
            if (baseProduct != null && baseProduct.isConnected) {
//                mTextConnectionStatus.text = "Connected to Drone ✓"
                model.droneConnected = true
//                mTextModelAvailable.text = baseProduct.firmwarePackageVersion
                model.droneName = baseProduct.model.displayName
            } else {
                mTextConnectionStatus.text = "Connecting to Drone"
            }
        })
    }
}