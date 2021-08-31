package com.dji.droneparking.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.dji.droneparking.core.ConnectionFragment
import dji.common.error.DJIError
import dji.common.error.DJISDKError
import dji.sdk.base.BaseComponent
import dji.sdk.base.BaseProduct
import dji.sdk.sdkmanager.DJISDKInitEvent
import dji.sdk.sdkmanager.DJISDKManager

/**
 * Utilized by ConnectionFragment.kt, this viewModel is used to register the app with the DJI mobile SDK,
 * store the current drone and RC controller connection statuses, and to listen to any DJI product hardware or connectivity changes.
 */
class ConnectionViewModel(application: Application) : AndroidViewModel(application) {

    //variable used to store an instance of the DJI product
    val product: MutableLiveData<BaseProduct?> by lazy {
        MutableLiveData<BaseProduct?>()
    }

    //connection variables
    val connectionStatus: MutableLiveData<Boolean> = MutableLiveData(false)
    var rCConnected : Boolean = false
    var droneConnected : Boolean = false
    var droneName : String = ""

    //Function used to register the app with DJI's mobile SDK
    fun registerApp() {
        //NOTE: After installation, the app connects to the DJI server via internet and verifies the API key (in AndroidManifest.xml).
        //Subsequent app starts will use locally cached verification information to register the app when the cached information is still valid.
        DJISDKManager.getInstance()
            .registerApp(getApplication(), object : DJISDKManager.SDKManagerCallback {

                //Logging the success or failure of the registration
                override fun onRegister(error: DJIError?) {
                    if (error == DJISDKError.REGISTRATION_SUCCESS) {
                        Log.i(ConnectionFragment.TAG, "onRegister: Registration Successful")
                    } else {
                        Log.i(
                            ConnectionFragment.TAG,
                            "onRegister: Registration Failed - ${error?.description}"
                        )
                    }
                }

                //Called when the "product" is disconnected
                override fun onProductDisconnect() {
                    Log.i(ConnectionFragment.TAG, "onProductDisconnect: Product Disconnected")
                    connectionStatus.postValue(false)
                }

                //Called when the "product" is connected
                override fun onProductConnect(baseProduct: BaseProduct?) {
                    Log.i(ConnectionFragment.TAG, "onProductConnect: Product Connected")
                    product.postValue(baseProduct)
                    connectionStatus.postValue(true)
                }

                //Called when the "product" is changed
                override fun onProductChanged(baseProduct: BaseProduct?) {
                    Log.i(
                        ConnectionFragment.TAG,
                        "onProductChanged: Product Changed - $baseProduct"
                    )
                    product.postValue(baseProduct)

                }

                //Called when a component object changes
                override fun onComponentChange(
                    componentKey: BaseProduct.ComponentKey?,
                    oldComponent: BaseComponent?,
                    newComponent: BaseComponent?
                ) {
                    Log.i(
                        ConnectionFragment.TAG,
                        "onComponentChange key: $componentKey, oldComponent: $oldComponent, newComponent: $newComponent"
                    )
                    newComponent?.let { component ->
                        component.setComponentListener { connected ->
                            Log.i(
                                ConnectionFragment.TAG,
                                "onComponentConnectivityChange: $connected"
                            )
                        }
                    }
                }

                override fun onInitProcess(p0: DJISDKInitEvent?, p1: Int) {}

                override fun onDatabaseDownloadProgress(p0: Long, p1: Long) {}

            })
    }

}