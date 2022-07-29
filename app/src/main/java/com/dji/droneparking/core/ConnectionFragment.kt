package com.dji.droneparking.core

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dji.droneparking.R
import dji.common.error.DJIError
import dji.common.error.DJISDKError
import dji.sdk.base.BaseComponent
import dji.sdk.base.BaseProduct
import dji.sdk.sdkmanager.DJISDKInitEvent
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

        registerApp()
    }

    //Function used to register the app with DJI's mobile SDK
    private fun registerApp() {
        val tutorialFragment = TutorialFragment()
        var rcConnection = false
        var droneConnection = false

        //Do this as soon as app starts
        lifecycleScope.launch {
            mTextConnectionStatus.setTextColor(Color.BLACK)
            while(!rcConnection){
                mTextConnectionStatus.text = "Connecting to RC"
                mTextConnectionStatus.startAnimation(animFadeOut)
                delay(2000)
                mTextConnectionStatus.startAnimation(animFadeIn)
                delay(2000)
            }

            while(!droneConnection and rcConnection){
                mTextConnectionStatus.text = "Connecting to drone"
                mTextConnectionStatus.startAnimation(animFadeOut)
                delay(2000)
                mTextConnectionStatus.startAnimation(animFadeIn)
                delay(2000)
            }

            if(rcConnection && droneConnection){
                //After both DJI products are connected, navigate to tutorialFragment.kt
                activity?.supportFragmentManager?.beginTransaction()
                    ?.replace(R.id.frameLayoutFragment, tutorialFragment, "tutorial")?.commit()
            }
        }

        //NOTE: After installation, the app connects to the DJI server via internet and verifies the API key (in AndroidManifest.xml).
        //Subsequent app starts will use locally cached verification information to register the app when the cached information is still valid.
        DJISDKManager.getInstance()
            .registerApp(context, object : DJISDKManager.SDKManagerCallback {

                //Logging the success or failure of the registration
                override fun onRegister(error: DJIError?) {
                    if (error == DJISDKError.REGISTRATION_SUCCESS) {
                        Toast.makeText(context, "Registration is Successful", Toast.LENGTH_SHORT).show()
                        Log.i(TAG, "onRegister: Registration Successful")
                    } else {
                        Log.i(
                            TAG,
                            "onRegister: Registration Failed - ${error?.description}"
                        )
                        Toast.makeText(context, "Registration Failed", Toast.LENGTH_SHORT).show()
                    }

                }

                //Called when the "product" is disconnected
                override fun onProductDisconnect() {
                    Log.i(TAG, "onProductDisconnect: Product Disconnected")
                    Toast.makeText(context, "The RC is Disconnected", Toast.LENGTH_SHORT).show()
                }

                //Called when the "product" is connected
                override fun onProductConnect(baseProduct: BaseProduct?) {
                    Log.i(TAG, "onProductConnect: Product Connected")
                    rcConnection = true
                    droneConnection = true
                    //do this right after connecting to the RC
                    lifecycleScope.launch {
                        mTextConnectionStatus.setTextColor(Color.GREEN)
                        mTextConnectionStatus.text = "Connected to RC ✓"
                        delay(2000)
                        mTextConnectionStatus.setTextColor(Color.BLACK)
                        mTextConnectionStatus.text = "Connecting to drone"
                    }
                }

                //Called when the "product" is changed
                override fun onProductChanged(baseProduct: BaseProduct?) {
                    Log.i(TAG, "onProductChanged: Product Changed - $baseProduct")

                    if(baseProduct?.model?.displayName != null){
                        droneConnection = true
                        lifecycleScope.launch {
                            mTextConnectionStatus.setTextColor(Color.GREEN)
                            mTextConnectionStatus.text = "Connected to ${baseProduct?.model?.displayName}  ✓"
                            delay(2000)
                        }
                    }
                }

                //Called when a component object changes
                override fun onComponentChange(
                    componentKey: BaseProduct.ComponentKey?,
                    oldComponent: BaseComponent?,
                    newComponent: BaseComponent?
                ) {
                    Log.i(TAG, "onComponentChange key: $componentKey, oldComponent: $oldComponent, newComponent: $newComponent")
                    newComponent?.let { component ->
                        component.setComponentListener { connected ->
                            Log.i(
                                TAG,
                                "onComponentConnectivityChange: $connected"
                            )

                            if (connected){
                                //After both DJI products are connected, navigate to tutorialFragment.kt
                                activity?.supportFragmentManager?.beginTransaction()
                                    ?.replace(R.id.frameLayoutFragment, tutorialFragment, "tutorial")?.commit()
                            }
                        }
                    }
                }

                override fun onInitProcess(p0: DJISDKInitEvent?, p1: Int) {}

                override fun onDatabaseDownloadProgress(p0: Long, p1: Long) {}

            })


    }
}