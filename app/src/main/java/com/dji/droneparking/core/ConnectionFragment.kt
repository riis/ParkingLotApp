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

/**
* Hosted by ConnectionActivity, this is the first view the user will be able to see. Its purpose is to
 * ensure that the DJI RC controller is connected to the user's mobile device and the DJI drone is connected
 * to the RC controller before navigating to TutorialFragment.kt.
 **/
class ConnectionFragment : Fragment() {
    //UI Variables
    private lateinit var mTextConnectionStatus: TextView
    private lateinit var mVersionTv: TextView
    private lateinit var animFadeIn: Animation
    private lateinit var animFadeOut: Animation

    //viewModel used for SDK registration, and listening to product connectivity and hardware changes
    private val model: ConnectionViewModel by viewModels()

    companion object {
        const val TAG = "ConnectionActivity"
    }

    //creating the fragment view from the fragment_connection.xml layout
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_connection, container, false)
    }

    //initializing UI elements and observers, and registering the app with DJI's mobile SDK
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        //Loading animations
        animFadeIn = AnimationUtils.loadAnimation(context, R.anim.fadein)
        animFadeOut = AnimationUtils.loadAnimation(context, R.anim.fadeout)

        //referencing layout views using their resource ids
        mTextConnectionStatus = view.findViewById(R.id.text_connection_status)
        mVersionTv = view.findViewById(R.id.textView2)

        //displaying the current DJI mobile SDK version to user
        mVersionTv.text =
            resources.getString(R.string.sdk_version, DJISDKManager.getInstance().sdkVersion)

        initUI()
        model.registerApp()
        observers()
    }

    //Function used to start displaying the dynamic UI elements
    private fun initUI() {
        val tutorialFragment = TutorialFragment()

        //To bypass this fragment and access the rest of the app without connecting to the DJI products, uncomment the line below:
        //activity?.supportFragmentManager?.beginTransaction()?.replace(R.id.frameLayoutFragment, tutorialFragment, "tutorial")?.commit()

        //Coroutine used to display animations until the DJI drone and RC controller are connected
        lifecycleScope.launch(Dispatchers.IO) {

            //Looping until the DJI RC controller is connected to the user's mobile device
            while (true) {
                if (!model.rCConnected) {
                    withContext(Dispatchers.Main) {
                        mTextConnectionStatus.startAnimation(animFadeOut)
                        delay(2000)
                        mTextConnectionStatus.startAnimation(animFadeIn)
                        delay(2000)
                    }
                } else {
                    //When connected, break loop and update UI
                    withContext(Dispatchers.Main) {
                        mTextConnectionStatus.setTextColor(Color.GREEN)
                        mTextConnectionStatus.text = "Connected to RC ✓"
                        delay(1000)
                        mTextConnectionStatus.setTextColor(Color.BLACK)
                    }
                    break
                }
            }

            //Looping until the DJI drone is connected to its RC controller
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
                    //When connected, break loop and update UI
                    withContext(Dispatchers.Main) {
                        mTextConnectionStatus.setTextColor(Color.GREEN)
                        mTextConnectionStatus.text = "Connected to ${model.droneName}  ✓"
                        delay(1000)
                        mTextConnectionStatus.setTextColor(Color.BLACK)
                    }
                    break
                }
            }

            //After both DJI products are connected, navigate to tutorialFragment.kt
            activity?.supportFragmentManager?.beginTransaction()
                ?.replace(R.id.frameLayoutFragment, tutorialFragment, "tutorial")?.commit()
        }
    }

    //Function used to setup observers
    private fun observers() {
        //Listens to any connection changes to the rc controller and updates UI accordingly
        model.connectionStatus.observe(viewLifecycleOwner, { isConnected ->
            if (isConnected) {
                mTextConnectionStatus.setTextColor(Color.GREEN)
                mTextConnectionStatus.text = "Connected to RC ✓"
                model.rCConnected = true
            } else {
                mTextConnectionStatus.text = "Connecting to RC"
                model.rCConnected = false
            }
        })

        //Listens to any connection changes to the drone and updates UI accordingly
        model.product.observe(viewLifecycleOwner, { baseProduct ->
            if (baseProduct != null && baseProduct.isConnected) {
                model.droneConnected = true
                model.droneName = baseProduct.model.displayName
            } else {
                mTextConnectionStatus.text = "Connecting to Drone"
            }
        })
    }
}