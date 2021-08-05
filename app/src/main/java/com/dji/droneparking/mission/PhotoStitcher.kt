package com.dji.droneparking.mission

import android.app.ProgressDialog
import android.content.Context
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dji.droneparking.MainActivity
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.log.DJILog
import dji.sdk.media.DownloadListener
import dji.sdk.media.FetchMediaTaskScheduler
import dji.sdk.media.MediaFile
import dji.sdk.media.MediaManager
import java.io.File
import java.util.*

class PhotoStitcher(context: Context) {

    //class variables
    private val activity: AppCompatActivity
    private var mediaFileList: MutableList<MediaFile> = mutableListOf() //empty list of MediaFile objects
    private var mMediaManager: MediaManager? = null //uninitialized media manager
    private var currentFileListState = MediaManager.FileListState.UNKNOWN //variable for the current state of the MediaManager's file list
    private var scheduler: FetchMediaTaskScheduler? = null //used to queue and download small content types of media
    private var currentProgress = -1 //integer variable for the current download progress
    private lateinit var photoStorageDir: File
    private var mLoadingDialog: ProgressDialog? = null
    private var mDownloadDialog: ProgressDialog? = null
    private lateinit var dateString: String
    private val mContext = context

    init {
        initUI()
        initMediaManager()
        createFileDir()
        activity = context as AppCompatActivity
        mLoadingDialog = ProgressDialog(context)
        mDownloadDialog = ProgressDialog(context)
    }

   private fun createFileDir(){
       val calendar = Calendar.getInstance()
       val year = calendar.get(Calendar.YEAR).toString()
       val day = calendar.get(Calendar.DAY_OF_MONTH).toString()
       val month = calendar.get(Calendar.MONTH)+1


       dateString = if (month<10){
           "$year-0$month-$day"
       } else{
           "$year-$month-$day"
       }

       photoStorageDir = File(mContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.path.toString())
       if(!photoStorageDir.exists()) photoStorageDir.mkdirs()
       Log.d("BANANAPIE", photoStorageDir.toString())


   }

    private fun initUI(){
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

            Log.d("BANANAPIE", "Product disconnected")
            return

            //If there is a DJI product connected to the mobile device...
        } else {
            Log.d("BANANAPIE", "Product connnected")
            //get an instance of the DJI product's camera
            DJIDemoApplication.getCameraInstance()?.let { camera ->
                //If the camera supports downloading media from it...
                if (camera.isMediaDownloadModeSupported) {
                    Log.d("BANANAPIE", "Download is supported")
                    mMediaManager = camera.mediaManager //get the camera's MediaManager
                    mMediaManager?.let { mediaManager ->
                        val updateFileListStateListener =
                            //when the MediaManager's FileListState changes, save the state to currentFileListState
                            MediaManager.FileListStateListener { state -> currentFileListState = state }

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
                                Log.d("BANANAPIE", "Set camera mode is a success")
                                showProgressDialog() //show the loading screen ProgressDialog
                                getFileList() //update the mediaFileList using the DJI product' SD card
                                //If the error is not null, alert user
                            } else {
                                Log.d("BANANAPIE", "Set camera mode is a failure")
                            }
                        }
                    }
                } else {
                    //If the camera doesn't support downloading media from it, alert the user
                    Log.d("BANANAPIE","Media Download Mode not Supported")
                }
            }
        }
        return
    }

    private fun getFileList() {
        DJIDemoApplication.getCameraInstance()?.let { camera -> //Get an instance of the connected DJI product's camera
            mMediaManager = camera.mediaManager //Get the camera's MediaManager
            mMediaManager?.let { mediaManager ->
                //If the MediaManager's file list state is syncing or deleting, the MediaManager is busy
                if (currentFileListState == MediaManager.FileListState.SYNCING || currentFileListState == MediaManager.FileListState.DELETING) {
                    Log.d("BANANAPIE", "Media Manager is busy.")
                } else {
                    Log.d("BANANAPIE", currentFileListState.toString()) //for debugging

                    //refreshing the MediaManager's file list using the connected DJI product's SD card
                    mediaManager.refreshFileListOfStorageLocation(
                        SettingsDefinitions.StorageLocation.SDCARD //file storage location
                    ) { djiError -> //checking the callback error

                        //If the error is null, dismiss the loading screen ProgressDialog
                        if (djiError == null) {
                            Log.d("BANANAPIE", "we are in the get file list")
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

                            activity.runOnUiThread {
                                for (i in mediaFileList.indices) {
                                    Log.d("BANANAPIE", "$i 'th iteration")
                                    downloadFileByIndex(i)
                                }
                            }


                            //If there was an error with refreshing the MediaManager's file list, dismiss the loading progressDialog and alert the user.
                        } else {
                            hideProgressDialog()
                            Log.d("BANANAPIE","Get Media File List Failed:" + djiError.description)
                        }
                    }
                }
            }

        }
    }

    private val downloadFileListener = object: DownloadListener<String> {
        //if the download fails, dismiss the download progressDialog, alert the user,
        //...and reset currentProgress.
        override fun onFailure(error: DJIError) {
            hideDownloadProgressDialog()
            Log.d("BANANAPIE", "DOWNLOAD FILE FAILED")
            showToast("Download File Failed" + error.description)
            currentProgress = -1
        }

        override fun onProgress(total: Long, current: Long) {}

        //called every 1 second to show the download rate
        override fun onRateUpdate(
            total: Long, //the total size
            current: Long, //the current download size
            persize: Long //the download size between two calls
        ) {
            //getting the current download progress as an integer between 1-100
            val tmpProgress = (1.0 * current / total * 100).toInt()
            Log.d("BANANAPIE","Downloading $tmpProgress")

            if (tmpProgress != currentProgress) {
                mDownloadDialog?.let {
                    it.progress = tmpProgress //set tmpProgress as the progress of the download progressDialog
                    currentProgress = tmpProgress //save tmpProgress to currentProgress
                }
            }
        }

        //When the download starts, reset currentProgress and show the download ProgressDialog
        override fun onStart() {
            currentProgress = -1
            Log.d("BANANAPIE","Start Download...")
            showDownloadProgressDialog()
        }
        //When the download successfully finishes, dismiss the download ProgressDialog, alert the user,
        //...and reset currentProgress.
        override fun onSuccess(filePath: String) {
            hideDownloadProgressDialog()
            Log.d("BANANAPIE","Download File Success:$filePath")
            currentProgress = -1
        }
    }

    //Function used to download full resolution photos/videos from the DJI product's SD card
    private fun downloadFileByIndex(index: Int) {

        //If the media file's type is panorama or shallow_focus, don't download it
        if (mediaFileList[index].mediaType == MediaFile.MediaType.PANORAMA
            || mediaFileList[index].mediaType == MediaFile.MediaType.SHALLOW_FOCUS
        ) {
            return
        }

        //If the media file's type is JPEG or JSON, download it to photoStorageDir
        if (mediaFileList[index].mediaType == MediaFile.MediaType.JPEG
            || mediaFileList[index].mediaType == MediaFile.MediaType.JSON) {
            mediaFileList[index].fetchFileData(photoStorageDir, null, downloadFileListener)
        }
    }



}