package com.dji.droneparking.util

import android.app.ProgressDialog
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dji.droneparking.MainActivity
import dji.common.camera.SettingsDefinitions
import dji.log.DJILog
import dji.sdk.media.FetchMediaTaskScheduler
import dji.sdk.media.MediaFile
import dji.sdk.media.MediaManager
import java.io.File

class PhotoStitcher(context: Context) {

    //class variables
    private val activity: AppCompatActivity
    private var mediaFileList: MutableList<MediaFile> =
        mutableListOf() //empty list of MediaFile objects
    private var mMediaManager: MediaManager? = null //uninitialized media manager
    private var currentFileListState =
        MediaManager.FileListState.UNKNOWN //variable for the current state of the MediaManager's file list
    private var scheduler: FetchMediaTaskScheduler? =
        null //used to queue and download small content types of media
    private var currentProgress = -1 //integer variable for the current download progress
    private var photoStorageDir: File
    private var mLoadingDialog: ProgressDialog? = null
    private var mDownloadDialog: ProgressDialog? = null

    init {
        initUI()
        initMediaManager()
        activity = context as AppCompatActivity
        photoStorageDir = File(activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!.path)
        mLoadingDialog = ProgressDialog(context)
        mDownloadDialog = ProgressDialog(context)
    }

    private fun initUI() {
        //Creating a ProgressDialog and configuring its behavioural settings as a loading screen

        mLoadingDialog?.let { progressDialog ->
            progressDialog.setMessage("Please wait")
            progressDialog.setCanceledOnTouchOutside(false)
            progressDialog.setCancelable(false)
        }

        //Creating a ProgressDialog and configuring its behavioural settings as a download progress screen

        mDownloadDialog?.let { progressDialog ->
            progressDialog.setTitle("Downloading file")
            progressDialog.setIcon(android.R.drawable.ic_dialog_info)
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            progressDialog.setCanceledOnTouchOutside(false)
            progressDialog.setCancelable(true)

            //If the ProgressDialog is cancelled, the MediaManager will stop the downloading process
            progressDialog.setOnCancelListener {
                mMediaManager?.exitMediaDownloading()
            }
        }
    }

    //Function used to display the loading ProgressDialog
    private fun showProgressDialog() {
        activity.runOnUiThread { mLoadingDialog?.show() }
    }

    //Function used to dismiss the loading ProgressDialog
    private fun hideProgressDialog() {
        activity.runOnUiThread {
            mLoadingDialog?.let { progressDialog ->
                if (progressDialog.isShowing) {
                    progressDialog.dismiss()
                }
            }
        }
    }

    //Function used to display the download ProgressDialog
    private fun showDownloadProgressDialog() {
        activity.runOnUiThread {
            mDownloadDialog?.let { progressDialog ->
                progressDialog.incrementProgressBy(-progressDialog.progress)
                progressDialog.show()
            }
        }
    }

    //Function used to dismiss the download ProgressDialog
    private fun hideDownloadProgressDialog() {
        activity.runOnUiThread {
            mDownloadDialog?.let { progressDialog ->
                if (progressDialog.isShowing) {
                    progressDialog.dismiss()
                }
            }
        }
    }

    //Function that displays toast messages to the user
    private fun showToast(msg: String?) {
        activity.runOnUiThread { Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show() }
    }


    //Function used to initialize the MediaManager
    private fun initMediaManager() {
        //If there is no DJI product connected to the mobile device...
        if (DJIDemoApplication.getProductInstance() == null) {
            //clear the mediaFileList
            mediaFileList.clear()

            DJILog.e(MainActivity.TAG, "Product disconnected")
            return

            //If there is a DJI product connected to the mobile device...
        } else {
            //get an instance of the DJI product's camera
            DJIDemoApplication.getCameraInstance()?.let { camera ->
                //If the camera supports downloading media from it...
                if (camera.isMediaDownloadModeSupported) {
                    mMediaManager = camera.mediaManager //get the camera's MediaManager
                    mMediaManager?.let { mediaManager ->

                        val updateFileListStateListener =
                            //when the MediaManager's FileListState changes, save the state to currentFileListState
                            MediaManager.FileListStateListener { state ->
                                currentFileListState = state
                            }

                        /**
                         * NOTE: To know when a change in the MediaManager's file list state occurs, the MediaManager needs a
                         *     ... FileListStateListener. We have created a FileListStateListener (further down in the code)
                         *     ... named updateFileListStateListener, and gave this listener to the MediaManager.
                         */
                        mediaManager.addUpdateFileListStateListener(updateFileListStateListener)

                        //Setting the camera mode to media download mode and then receiving an error callback
                        camera.setMode(
                            SettingsDefinitions.CameraMode.MEDIA_DOWNLOAD
                        ) { error ->
                            //If the error is null, the operation was successful
                            if (error == null) {
                                DJILog.e(MainActivity.TAG, "Set cameraMode success")
                                showProgressDialog() //show the loading screen ProgressDialog
                                getFileList() //update the mediaFileList using the DJI product' SD card
                                //If the error is not null, alert user
                            } else {
                                showToast("Set cameraMode failed")
                            }
                        }

                        //Setting the scheduler to be the MediaManager's scheduler
                        scheduler = mediaManager.scheduler
                    }
                } else {
                    //If the camera doesn't support downloading media from it, alert the user
                    showToast("Media Download Mode not Supported")
                }
            }
        }
        return
    }

    private fun getFileList() {
        DJIDemoApplication.getCameraInstance()
            ?.let { camera -> //Get an instance of the connected DJI product's camera
                mMediaManager = camera.mediaManager //Get the camera's MediaManager
                mMediaManager?.let { mediaManager ->
                    //If the MediaManager's file list state is syncing or deleting, the MediaManager is busy
                    if (currentFileListState == MediaManager.FileListState.SYNCING || currentFileListState == MediaManager.FileListState.DELETING) {
                        DJILog.e(MainActivity.TAG, "Media Manager is busy.")
                    } else {
                        showToast(currentFileListState.toString()) //for debugging

                        //refreshing the MediaManager's file list using the connected DJI product's SD card
                        mediaManager.refreshFileListOfStorageLocation(
                            SettingsDefinitions.StorageLocation.SDCARD //file storage location
                        ) { djiError -> //checking the callback error

                            //If the error is null, dismiss the loading screen ProgressDialog
                            if (null == djiError) {
                                hideProgressDialog()

                                //Reset data if the file list state is not incomplete
                                if (currentFileListState != MediaManager.FileListState.INCOMPLETE) {
                                    mediaFileList.clear()

                                }
                                //updating the recycler view's mediaFileList using the now refreshed MediaManager's file list
                                mediaManager.sdCardFileListSnapshot?.let { listOfMedia ->
                                    mediaFileList = listOfMedia
                                }

                                //sort the files in the mediaFileList by descending order based on the time each media file was created.
                                //Older files are now at the top of the mediaFileList, and newer ones are at the bottom.
                                mediaFileList.sortByDescending { it.timeCreated }

                                //Resume the scheduler. This will allow it to start executing any tasks in its download queue.
                                scheduler?.let { schedulerSafe ->
                                    schedulerSafe.resume { error ->
                                        //if the callback error is null, the operation was successful.
                                        if (error == null) {
                                            //TODO
                                        }
                                    }
                                }
                                //If there was an error with refreshing the MediaManager's file list, dismiss the loading progressDialog and alert the user.
                            } else {
                                hideProgressDialog()
                                showToast("Get Media File List Failed:" + djiError.description)
                            }
                        }
                    }
                }

            }
    }


}