package com.dji.droneparking.util

import android.content.Context
import android.graphics.Bitmap
import android.os.*
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.RecyclerView
import com.dji.droneparking.R
import com.dji.droneparking.core.PhotoStitcherActivity
import com.dji.droneparking.dialog.DownloadDialog
import com.dji.droneparking.dialog.LoadingDialog
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.sdk.media.*
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Array.get
import java.util.*

class PhotoDownloader(mContext: Context) {

    //class variables
    private var mediaFileList: MutableList<MediaFile> = mutableListOf() //empty list of MediaFile objects
    private var mMediaManager: MediaManager? = null //uninitialized media manager
    private var currentFileListState = MediaManager.FileListState.UNKNOWN //variable for the current state of the MediaManager's file list
    private var scheduler: FetchMediaTaskScheduler? = null //used to queue and download small content types of media
    private var currentProgress = -1 //integer variable for the current download progress
    private var mLoadingDialog: LoadingDialog = LoadingDialog("Obtaining Images from SD Card")
    private lateinit var mDownloadDialog: DownloadDialog
    private var currentDownloadIndex = -1
    private var thumbnailDownloadCount = 0
    private val activity: AppCompatActivity = mContext as AppCompatActivity
    private var mListAdapter: FileListAdapter
    private lateinit var stitchButtonVisibilityListener: StitchButtonVisibilityListener


    init{
        createFileDir()
        initMediaManager()
        mListAdapter = FileListAdapter()
    }

    fun interface StitchButtonVisibilityListener {
        fun setStitchButtonVisibility(boolean: Boolean)
    }

    fun setStitchButtonVisibilityListener(listener: StitchButtonVisibilityListener) {
        this.stitchButtonVisibilityListener = listener
    }

    private fun createFileDir(){
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR).toString()
        val day = calendar.get(Calendar.DAY_OF_MONTH).toString()
        val month = calendar.get(Calendar.MONTH)+1
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)

        val dateString = if (month<10){
            "$year-0$month-$day $hour:$minute:$second"
        } else{
            "$year-$month-$day $hour:$minute:$second"
        }

        PhotoStitcherActivity.photoStorageDir = File(activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.path.toString(), dateString)
        if(!PhotoStitcherActivity.photoStorageDir.exists()) PhotoStitcherActivity.photoStorageDir.mkdirs()
        Log.d("BANANAPIE", PhotoStitcherActivity.photoStorageDir.toString())

    }

    //Function that displays toast messages to the user
    private fun showToast(msg: String?) {
        activity.runOnUiThread { Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show() }
    }

    //Function used to initialize the MediaManager
    private fun initMediaManager() {
        //If there is no DJI product connected to the mobile device...
        if (DJIDemoApplication.getProductInstance() == null) {
            Log.d("BANANAPIE", "Product disconnected")
            return

            //If there is a DJI product connected to the mobile device...
        } else {
            Log.d("BANANAPIE", "Product connected")
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

                                mLoadingDialog.show(activity.supportFragmentManager, "testing") //show the loading screen ProgressDialog
                                getFileList() //update the mediaFileList using the DJI product' SD card
                                //If the error is not null, alert user

                            } else {
                                Log.d("BANANAPIE", "Set camera mode is a failure ${error.description}")
                            }
                        }
                        scheduler = mediaManager.scheduler
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
                            Log.d("BANANAPIE", "obtained media data from SD card (PhotoStitcherActivity)")
                            mLoadingDialog.dismiss()

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
                                downloadFileByIndex(currentDownloadIndex)
                            }


                            //If there was an error with refreshing the MediaManager's file list, dismiss the loading progressDialog and alert the user.
                        } else {
                            Log.d("BANANAPIE","could not obtain media data from SD card (PhotoStitcher)" + djiError.description)
                        }
                    }
                }
            }

        }
    }

    /**
     * NOTE:Because full resolution photos/videos take too long to load, we want the recycler view to only display
     *   ... thumbnails of each media file in the mediaFileList.
     */

    //Function used to get the thumbnail images of all the media files in the mediaFileList
    private fun getThumbnails() {
        Log.d("BANANAPIE", "downloading thumbnails")
        //if the mediaFileList is empty, alert the user
        if (mediaFileList.size <= 0) {
            Log.d("BANANAPIE","No File info for downloading thumbnails")
            return
        }
        //if the mediaFileList is not empty, call getThumbnailByIndex() on each media file
        for (i in mediaFileList.indices) {
            getThumbnailByIndex(i)
        }
    }

    //creating a Callback which is called whenever media content is downloaded using FetchMediaTask()
    private val taskCallback =
        FetchMediaTask.Callback { _, option, error ->
            //if the callback error is null, the download operation was successful
            if (null == error) {
                //if a preview image or thumbnail was downloaded, notify the recycler view that its data set has changed.
                if (option == FetchMediaTaskContent.PREVIEW) {
                    activity.runOnUiThread {
                        mListAdapter.notifyDataSetChanged()
                        PhotoStitcherActivity.photosRecyclerView.adapter = mListAdapter
                    }
                }
                if (option == FetchMediaTaskContent.THUMBNAIL) {
                    activity.runOnUiThread {
                        mListAdapter.notifyDataSetChanged()
                        PhotoStitcherActivity.photosRecyclerView.adapter = mListAdapter
                    }
                    thumbnailDownloadCount++
                    Log.d("BANANAPIE", thumbnailDownloadCount.toString())
                    if (thumbnailDownloadCount == mediaFileList.size){
                        Log.d("BANANAPIE", "if statement")
                        activity.runOnUiThread {

                            for (image in mediaFileList){
                                val imageName = image.fileName.replace(".jpg", "")
                                    .replace(".png", "")
                                    .replace(".JPG", "")
                                    .replace(".PNG", "")
                                val file = File(PhotoStitcherActivity.photoStorageDir.path.toString()+"/Thumbnails/$imageName.png")
                                file.parentFile?.mkdirs() // Will create parent directories if not exists
                                file.createNewFile()
                                val outputStream = FileOutputStream(file, false)

                                image.thumbnail.compress(
                                    Bitmap.CompressFormat.PNG,
                                    100,
                                    outputStream
                                )
                                outputStream.flush()
                                outputStream.close()
                            }

                            stitchButtonVisibilityListener.setStitchButtonVisibility(true)
                        }

                    }
                }
            } else {
                Log.d("BANANAPIE", "Fetch Media Task Failed" + error.description)
            }
        }

    private fun getThumbnailByIndex(index: Int) {
        //creating a task to fetch the thumbnail of a media file in the mediaFileList.
        //This function also calls taskCallback to check for and respond to errors.
        val task =
            FetchMediaTask(mediaFileList[index], FetchMediaTaskContent.THUMBNAIL, taskCallback)

        //Using the scheduler to move each task to the back of its download queue.
        //The task will be executed after all other tasks are completed.
        scheduler?.moveTaskToEnd(task)
    }

    //Creating a ViewHolder to store the item views displayed in the RecyclerView
    private class ItemHolder(itemView: View) :
        RecyclerView.ViewHolder(itemView) {

        //referencing child views from the item view's layout using their resource ids
        var thumbnailImage: ImageView = itemView.findViewById(R.id.thumbnail) as ImageView
    }

    //Creating an adapter for the RecyclerView
    private inner class FileListAdapter : RecyclerView.Adapter<ItemHolder>() {

        //returns the number of items in the adapter's data set list
        override fun getItemCount(): Int {
            return mediaFileList.size
        }
        //inflates an item view and creates a ViewHolder to wrap it
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemHolder {
            val view = LayoutInflater.from(parent.context)
                //item view layout defined in media_info_item.xml
                .inflate(R.layout.list_item_photo_stitcher, parent, false)
            return ItemHolder(view)
        }

        //Binds a ViewHolder in the recyclerView to a MediaFile object in the mediaFileList.
        //The UI of the ViewHolder is changed to display the MediaFile's data.
        override fun onBindViewHolder(mItemHolder: ItemHolder, index: Int) {
            val mediaFile: MediaFile = mediaFileList[index]

            mItemHolder.thumbnailImage.setImageBitmap(mediaFile.thumbnail)

            //mItemHolder.thumbnailImage.setOnClickListener(ImgOnClickListener) //making the thumbnail_img ImageView clickable
            mItemHolder.thumbnailImage.tag = mediaFile //setting the MediaFile object as the thumbnail_img ImageView's tag
            mItemHolder.itemView.tag = index //setting the current mediaFileList index as the itemView's tag

        }
    }

    private val downloadFileListener = object: DownloadListener<String> {
        //if the download fails, dismiss the download progressDialog, alert the user,
        //...and reset currentProgress.
        override fun onFailure(error: DJIError) {
            Log.d("BANANAPIE", "DOWNLOAD FILE FAILED")
            showToast("Download File Failed" + error.description)
            currentProgress = -1

            mDownloadDialog.dismiss()




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
            mDownloadDialog.setProgress(tmpProgress)
            Log.d("BANANAPIE","Downloading $tmpProgress")
        }

        //When the download starts, reset currentProgress and show the download ProgressDialog
        override fun onStart() {
            currentProgress = -1
            Log.d("BANANAPIE","Start Download...")
            Log.d("BANANAPIE", "Downloading ${currentDownloadIndex + 1}/${mediaFileList.size} images ...")

            mDownloadDialog = DownloadDialog("Downloading ${currentDownloadIndex + 1}/${mediaFileList.size} images ...")


            mDownloadDialog.show(activity.supportFragmentManager, "asdf")




        }
        //When the download successfully finishes, dismiss the download ProgressDialog, alert the user,
        //...and reset currentProgress.
        override fun onSuccess(filePath: String) {
            Log.d("BANANAPIE","Download File Success:$filePath")
            currentProgress = -1


            mDownloadDialog.dismiss()


            downloadFileByIndex(currentDownloadIndex)



        }
    }

    //Function used to download full resolution photos/videos from the DJI product's SD card
    private fun downloadFileByIndex(index: Int) {

        if (index < mediaFileList.size-1){

            //If the media file's type is panorama or shallow_focus, don't download it
            if (mediaFileList[index+1].mediaType == MediaFile.MediaType.PANORAMA
                || mediaFileList[index+1].mediaType == MediaFile.MediaType.SHALLOW_FOCUS
            ) {
                return
            }

            //If the media file's type is JPEG or JSON, download it to photoStorageDir
            if (mediaFileList[index+1].mediaType == MediaFile.MediaType.JPEG
                || mediaFileList[index+1].mediaType == MediaFile.MediaType.JSON) {

                currentDownloadIndex += 1
                mediaFileList[currentDownloadIndex].fetchFileData(PhotoStitcherActivity.photoStorageDir, null, downloadFileListener)
            }

        }

        else{
            //Resume the scheduler. This will allow it to start executing any tasks in its download queue.
            scheduler?.let { schedulerSafe ->
                schedulerSafe.resume { error ->
                    //if the callback error is null, the operation was successful.
                    if (error == null) {
                        getThumbnails()
                    }
                }
            }
            return
        }

    }


}