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

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {

    val product: MutableLiveData<BaseProduct?> by lazy {
        MutableLiveData<BaseProduct?>()
    }

    val connectionStatus: MutableLiveData<Boolean> = MutableLiveData(false)
    var rCConnected : Boolean = false
    var droneConnected : Boolean = false
    var droneName : String = ""

    fun registerApp() {
        DJISDKManager.getInstance()
            .registerApp(getApplication(), object : DJISDKManager.SDKManagerCallback {
                override fun onRegister(error: DJIError?) {
                    if (error == DJISDKError.REGISTRATION_SUCCESS) {
                        Log.i(ConnectionFragment.TAG, "onRegister: Registration Successful")
//                    DJISDKManager.getInstance().enableBridgeModeWithBridgeAppIP(BuildConfig.IP_ADDRESS)
                    } else {
                        Log.i(
                            ConnectionFragment.TAG,
                            "onRegister: Registration Failed - ${error?.description}"
                        )
                    }
                }

                override fun onProductDisconnect() {
                    Log.i(ConnectionFragment.TAG, "onProductDisconnect: Product Disconnected")
                    connectionStatus.postValue(false)
                }

                override fun onProductConnect(baseProduct: BaseProduct?) {
                    Log.i(ConnectionFragment.TAG, "onProductConnect: Product Connected")
                    product.postValue(baseProduct)
                    connectionStatus.postValue(true)
                }

                override fun onProductChanged(baseProduct: BaseProduct?) {
                    Log.i(
                        ConnectionFragment.TAG,
                        "onProductChanged: Product Changed - $baseProduct"
                    )
                    product.postValue(baseProduct)

                }

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