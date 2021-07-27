package com.dji.droneparking

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.util.Log
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJICameraError
import dji.common.error.DJIError
import dji.common.util.CommonCallbacks.CompletionCallback
import dji.common.util.CommonCallbacks.CompletionCallbackWithTwoParam
import dji.sdk.media.FetchMediaTaskScheduler
import dji.sdk.media.MediaFile
import dji.sdk.media.MediaManager
import dji.sdk.products.Aircraft
import java.io.File
import java.util.*
import java.util.concurrent.CountDownLatch

/*
 * Downloads the batch of images from the drone after it flies.
 * */
class DJIImageDownloader(
    private val context: Context,
    sdkManager: SDKManager,
    messageHandler: Handler
) {
    enum class DroneMessage(val value: Int) {
        DRONE_IMG_PROGRESS_UPDATE(0), DRONE_IMG_DOWNLOAD_DONE(1), DRONE_IMG_DOWNLOAD_FAILED(2), DRONE_IMG_COUNT(
            3
        );

    }

    private val manager: SDKManager
    private val handler: Handler
    private var mediaManager: MediaManager? = null
    private var scheduler: FetchMediaTaskScheduler? = null
    private val absoluteFilePaths: List<String>
    private val mediaPreviewBitmapList: MutableList<Bitmap> = ArrayList()
    private var imgCount = 0
    private val refreshFileListCallback: CompletionCallback<*> = object : CompletionCallback<Any?> {
        override fun onResult(djiError: DJIError?) {
            imgCount = mediaManager!!.sdCardFileListSnapshot!!.size
        }
    }

    /* Delete up the files on the phone's storage */
    fun cleanPhone() {
        for (s in absoluteFilePaths) {
            val f = File(s)
            f.delete()
        }
    }

    /* Delete all of the images on the drone. Blocks until complete. */
    fun cleanDrone() {
        val filesToDelete = mediaManager!!.sdCardFileListSnapshot
        val latch = CountDownLatch(1)
        mediaManager!!.deleteFiles(
            filesToDelete,
            object : CompletionCallbackWithTwoParam<List<MediaFile?>?, DJICameraError?> {
                override fun onSuccess(
                    mediaFiles: List<MediaFile?>?,
                    djiCameraError: DJICameraError?
                ) {
                    latch.countDown()
                }

                override fun onFailure(djiError: DJIError) {
                    latch.countDown()
                }
            })
        try {
            latch.await()
        } catch (e: Exception) {
        }
    }

    private fun setMediaDownloadMode() {
        val craft: Aircraft = manager.getAircraftInstance() ?: return
        craft.camera.setMode(
            SettingsDefinitions.CameraMode.MEDIA_DOWNLOAD,
            object : CompletionCallback<Any?> {
                override fun onResult(djiError: DJIError?) {
                    Log.d("DJIImageDownloader", "Camera mode change failed: $djiError")
                    mediaManager = manager.getAircraftInstance().getCamera().getMediaManager()
                    if (mediaManager != null) {
                        mediaManager!!.refreshFileListOfStorageLocation(
                            SettingsDefinitions.StorageLocation.SDCARD, refreshFileListCallback
                        )
                        scheduler = mediaManager!!.scheduler
                    }
                }
            })
    }

    // TODO: Implement useFullResolution
    /* Downloads previews of all images on the drone and stores them in memory. */
    fun startFileDownloads(useFullResolution: Boolean) {
        // We'll do it in a separate thread so the UI doesn't lock up while this executes.
        val t = Thread {
            val filesToDownload = mediaManager!!.sdCardFileListSnapshot
            sendMessage(DroneMessage.DRONE_IMG_COUNT, filesToDownload!!.size)
            for (file in filesToDownload) {
                val callbackLatch = CountDownLatch(1)
                file.fetchPreview(object : CompletionCallback<Any?> {
                    override fun onResult(djiError: DJIError?) {
                        callbackLatch.countDown()
                        if (djiError == null) {
                            sendMessage(DroneMessage.DRONE_IMG_DOWNLOAD_DONE, null)
                            addPreview(file.preview)
                        } else {
                            sendMessage(DroneMessage.DRONE_IMG_DOWNLOAD_FAILED, null)
                        }
                        //                            Log.d(TAG, "fetchPreview: ");
                    }
                })
                try {
                    callbackLatch.await()
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
            }
            //                getPreviews(filesToDownload);
        }
        t.start()
    }

    /* Do not call until you have received the same number of image download messages (success or fail)
       as there were original images.
     */
    val imageList: List<Bitmap>
        get() = mediaPreviewBitmapList

    private fun addPreview(preview: Bitmap) {
        mediaPreviewBitmapList.add(preview)
    }

    private fun getPreviews(filesToDownload: List<MediaFile>) {
        for (file in filesToDownload) {
            val preview = file.preview
            if (preview != null) mediaPreviewBitmapList.add(file.preview)
        }
    }

    private fun sendMessage(message: DroneMessage, companion: Any?) {
        val msg = handler.obtainMessage()
        msg.what = message.value
        if (companion != null) msg.obj = companion
        msg.sendToTarget()
    }

    init {
        manager = sdkManager
        handler = messageHandler
        absoluteFilePaths = ArrayList()
        setMediaDownloadMode()
    }
}