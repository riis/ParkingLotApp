package com.dji.droneparking.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.*
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dji.droneparking.R
import com.dji.droneparking.dialog.RecyclerViewDialog
import java.io.File
import com.dji.droneparking.net.StitchRequester
import com.dji.droneparking.util.PhotoDownloader
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.FileOutputStream
import kotlin.collections.HashMap


class PhotoStitcherActivity() : AppCompatActivity(), View.OnClickListener{

    //class variables
    private var thumbnailFileList: MutableList<File> = mutableListOf() //stores the thumbnails used in photo gallery
    private lateinit var mListAdapter: FileListAdapter
    private lateinit var mRecyclerViewDialog: RecyclerViewDialog
    private lateinit var stitchedImageBitmap: Bitmap
    private var stitchRequester: StitchRequester? = null
    private var stitchUploadCounter = 0
    private var photoStorageDirLength = 0
    private val logTag: String = "STITCH_ACTIVITY"
    private lateinit var batchID: String

    companion object {
        lateinit var photosRecyclerView: RecyclerView //recycler view for photo gallery
        lateinit var photoStorageDir: File //storage directory containing photos of selected flight mission
        //NOTE: photoStorageDir is set in either PhotoDownloader.kt or by populateRecyclerView()
    }

    //layouts and widgets
    private lateinit var photoStitchProgressBar: ProgressBar
    private lateinit var downloadButtonsLayout: LinearLayout
    private lateinit var downloadSDCardButton: Button
    lateinit var downloadOtherMissionsButton: Button
    private lateinit var stitchImagesButton: Button
    private lateinit var downloadStitchButton: Button
    private lateinit var photoStitchProgressTextView: TextView
    private lateinit var stitchedImageView: ImageView

    //Creates the activity
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //activity layout view is created from activity_photo_stitcher.xml
        setContentView(R.layout.activity_photo_stitcher)
        initUI()

        /*
        If the user selected the option to download images directly from the drone's SD card (prior to navigating to this activity),
        then only show the user the option to stitch those images. Otherwise show the user all downloading options.
        */
        val directlyDownloadFromSD: Boolean = intent.getBooleanExtra("directlyDownloadFromSD", false)
        if (directlyDownloadFromSD){
            PhotoDownloader(this).setStitchButtonVisibilityListener(){ stitchButtonVisibility ->
                if (stitchButtonVisibility){
                    stitchImagesButton.visibility = View.VISIBLE
                }
            }
        }
        else{
            downloadButtonsLayout.visibility = View.VISIBLE
        }
    }

    //Initializes all layouts, widgets and their listeners
    private fun initUI(){
        downloadButtonsLayout = findViewById(R.id.download_buttons_layout)
        downloadSDCardButton = findViewById(R.id.download_sd_card_button)
        downloadOtherMissionsButton = findViewById(R.id.download_other_missions_button)
        downloadStitchButton = findViewById(R.id.download_stitch_button)
        stitchedImageView = findViewById(R.id.stitched_image_view)
        stitchImagesButton = findViewById(R.id.stitch_images_button)
        photoStitchProgressTextView = findViewById(R.id.photo_stitch_progress_text_view)
        photoStitchProgressBar = findViewById(R.id.photo_stitch_progress_bar)
        photosRecyclerView = findViewById(R.id.photos_recycler_view)

        downloadSDCardButton.setOnClickListener(this)
        downloadOtherMissionsButton.setOnClickListener(this)
        stitchImagesButton.setOnClickListener(this)
        downloadStitchButton.setOnClickListener(this)

        photosRecyclerView.layoutManager =
            GridLayoutManager(this, 2)
    }

    //Handles what happens when any of the buttons are clicked
    override fun onClick(v: View?) {
        when (v?.id) {
            //If user clicks on thee 'STITCH IMAGES' button, then the images currently stored in photoStorageDir will be stitched
            R.id.stitch_images_button -> {
                startPhotoStitch()
                stitchImagesButton.visibility = View.GONE
            }
            //If user clicks on the 'SD CARD' Button, then the images from the drone's SD card will be downloaded
            R.id.download_sd_card_button -> {
                downloadButtonsLayout.visibility = View.GONE //hide download options

                PhotoDownloader(this).setStitchButtonVisibilityListener() { stitchButtonVisibility ->
                    //if the SD cards have successfully been uploaded, show the option to stitch the images
                    if (stitchButtonVisibility) {
                        stitchImagesButton.visibility = View.VISIBLE
                    }
                }
            }
            /*
            If user clicks on the 'OTHER MISSIONS' Button, then a dialog will appear containing a list of photo storage directories
            in the app. Each directory stores the downloaded images of a previous drone flight. If the user clicks on one of these
            directories, the thumbnails of these images will be displayed in the photo gallery.
            */
            R.id.download_other_missions_button -> {
                downloadButtonsLayout.visibility = View.GONE //hide download options

                //Accessing the 'Pictures' subdirectory from within the app's external storage directory
                val parentDir = File(this.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.path.toString())

                //Listing all of the photo directories in the 'Pictures' directory and displaying them in a Dialog
                mRecyclerViewDialog = RecyclerViewDialog(parentDir.listFiles())
                mRecyclerViewDialog.show(this.supportFragmentManager, "tagCanBeWhatever")
            }
            //If user clicks on the 'DOWNLOAD STITCH' Button (provided a stitch result is available), then stitch image is downloaded
            R.id.download_stitch_button ->{
                val file = File(this.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.path.toString()+"/${photoStorageDir.nameWithoutExtension}/stitch.png")
                file.parentFile?.mkdirs()
                file.createNewFile()
                val outputStream = FileOutputStream(file, false)

                stitchedImageBitmap.compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    outputStream
                )
                outputStream.flush()
                outputStream.close()
                showToast("stitch downloaded")
            }
            }
        }

    //Displays brief pop-up messages on the screen
    private fun showToast(msg: String?) {
        this.runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun resizeDownloadedImages(){
        val files = photoStorageDir.listFiles()
        if (files != null){
            for (file in files){
                if (file.name.endsWith(".jpg") || file.name.endsWith(".png") || file.name.endsWith(".JPG")){
                    if (file.nameWithoutExtension != "stitch"){ //ignore any images containing previous stitch results
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 800, 450, true)

                        val outputStream = FileOutputStream(file, false)
                        resizedBitmap.compress(
                            Bitmap.CompressFormat.PNG,
                            100,
                            outputStream
                        )
                        outputStream.flush()
                        outputStream.close()
                    }
                }
            }
        }
    }

    /*
    //Returns a list of processed Bitmaps representing the images currently stored in the photoStorageDir
    private fun getDownloadedImagesList(): MutableList<Bitmap>{
        val downloadedImageList = mutableListOf<Bitmap>()

        val files = photoStorageDir.listFiles()
        for (file in files){
            if (file.name.endsWith(".jpg") || file.name.endsWith(".png") || file.name.endsWith(".JPG")){
                if (file.nameWithoutExtension != "stitch"){ //ignore any images containing previous stitch results
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 800, 450, true)
                    downloadedImageList.add(resizedBitmap)
                }
            }
        }
        return downloadedImageList
    }
     */

    //Initializes the StitchRequester and requests a new batch id from the stitching server
    private fun startPhotoStitch(){
        stitchUploadCounter = 0

        val pics = photoStorageDir.listFiles()
        photoStorageDirLength = pics.size
        if(pics != null) {
            for (file in pics) {
                //we don't want to include the 'stitch results' folder when uploading images to server
                if(file.nameWithoutExtension == "stitch"){
                    photoStorageDirLength--
                }
                else if(file.nameWithoutExtension == "Thumbnails"){
                    photoStorageDirLength--
                }
            }
        }

        resizeDownloadedImages()

        stitchRequester = StitchRequester(this) //used to make http requests to the stitching server and receive responses

        //Creating and setting a Handler to handle messages from the StitchRequester
        val stitchMessageHandler: Handler = getStitchHandler(stitchRequester!!)
        stitchRequester!!.setHandler(stitchMessageHandler)

        //Show the indicators used to display stitching status
        runOnUiThread {
            photoStitchProgressTextView.visibility = View.VISIBLE
            photoStitchProgressBar.visibility = View.VISIBLE
            photoStitchProgressTextView.text = "Requesting new batch id from server..."
        }
        Log.d(logTag, "Requesting new batch id from server...")
        stitchRequester!!.requestStitchId()
    }

    //Creates the message Handler for StitchRequester
    private fun getStitchHandler(requester: StitchRequester): Handler {

        return object : Handler(Looper.getMainLooper()) {

            override fun handleMessage(msg: Message) { //handling received StitchRequester messages
                when (msg.what) { //msg.what is the response code from StitchRequester

                    StitchRequester.StitchResponseCodes.START_BATCH_SUCCESS.value -> {
                        batchID = msg.obj.toString()
                        runOnUiThread {
                            photoStitchProgressTextView.text = "Request successful. New batch id: $batchID"
                            photoStitchProgressTextView.text = "Attempting to upload images to server..."
                        }
                        Log.d(logTag, "Request successful. New batch id: $batchID")
                        Log.d(logTag, "Attempting to upload images to server...")

                        val pics = photoStorageDir.listFiles()
                        if (pics != null) {
                            if(pics[stitchUploadCounter].nameWithoutExtension != "stitch" && pics[stitchUploadCounter].nameWithoutExtension != "Thumbnails"){
                                requester.addImage(pics[stitchUploadCounter], batchID)
                            }
                            else{
                                requester.addImage(pics[stitchUploadCounter+1], batchID)
                            }
                        }
                    }
                    StitchRequester.StitchResponseCodes.START_BATCH_FAILURE.value -> {
                        runOnUiThread {
                            photoStitchProgressTextView.text = "Request Failed. Error: ${msg.obj}"
                        }
                        Log.d(logTag, "Request Failed. Error: ${msg.obj}")
                    }

                    StitchRequester.StitchResponseCodes.STITCH_IMAGE_ADD_SUCCESS.value -> {
                        stitchUploadCounter++
                        runOnUiThread {
                            photoStitchProgressTextView.text = "Image '${msg.obj}' uploaded successful. [$stitchUploadCounter/$photoStorageDirLength]"
                        }
                        Log.d(logTag, "Image '${msg.obj}' uploaded successful. [$stitchUploadCounter/$photoStorageDirLength]")


                        if (stitchUploadCounter == photoStorageDirLength){
                            runOnUiThread {
                                photoStitchProgressTextView.text = "Attempting to start stitching batch $batchID ..."
                            }
                            Log.d(logTag, "Attempting to start stitching batch $batchID ...")
                            requester.stitchBatch(batchID)
                        }else{
                            val pics = photoStorageDir.listFiles()
                            if (pics != null) {
                                requester.addImage(pics[stitchUploadCounter], batchID)
                            }
                        }
                    }
                    StitchRequester.StitchResponseCodes.STITCH_IMAGE_ADD_FAILURE.value -> {
                        runOnUiThread {
                            photoStitchProgressTextView.text = "Image upload Failed. Error: ${msg.obj}"
                            photoStitchProgressTextView.text = "Retrying..."
                        }
                        Log.d(logTag, "Image upload Failed. Error: ${msg.obj}")
                        Log.d(logTag, "Retrying...")
                        val pics = photoStorageDir.listFiles()
                        if (pics != null) {
                            requester.addImage(pics[stitchUploadCounter], batchID)
                        }
                    }
                    StitchRequester.StitchResponseCodes.STITCH_BATCH_SUCCESS.value -> {
                        runOnUiThread {
                            photoStitchProgressTextView.text = "Request to start stitching batch $batchID is successful"
                            photoStitchProgressTextView.text = "Waiting for server to complete stitch..."
                        }
                        Log.d(logTag, "Request to start stitching batch $batchID is successful")
                        Log.d(logTag, "Waiting for server to complete stitch...")
                        requester.pollBatch(batchID)
                    }
                    StitchRequester.StitchResponseCodes.STITCH_BATCH_FAILURE.value -> {
                        if(msg.obj == 504){
                            runOnUiThread {
                                photoStitchProgressTextView.text = "504 Gateway Timeout Error"
                                photoStitchProgressTextView.text = "Waiting for server to complete stitch..."
                            }
                            Log.d(logTag, "504 Gateway Timeout Error")
                            Log.d(logTag, "Waiting for server to complete stitch...")
                            requester.pollBatch(batchID)
                            runBlocking {
                                delay(3000)
                            }
                        }
                        else{
                            runOnUiThread {
                                photoStitchProgressTextView.text = "Request to start stitching batch $batchID failed. Server response: ${msg.obj}"
                                photoStitchProgressTextView.text = "Attempting again to start stitching batch $batchID ..."
                            }
                            Log.d(logTag, "Request to start stitching batch $batchID failed. Server response: ${msg.obj}")
                            Log.d(logTag, "Attempting again to start stitching batch $batchID ...")
                            requester.stitchBatch(batchID)
                        }
                    }
                    StitchRequester.StitchResponseCodes.STITCH_POLL_COMPLETE.value -> {
                        runOnUiThread {
                            photoStitchProgressTextView.text = "Server has completed stitch successfully."
                            photoStitchProgressTextView.text = "Attempting to retrieve result from server..."
                        }
                        Log.d(logTag, "Server has completed stitch successfully.")
                        Log.d(logTag, "Attempting to retrieve result from server...")
                        requester.retrieveResult(batchID, photoStorageDir)
                    }
                    StitchRequester.StitchResponseCodes.STITCH_POLL_NOT_COMPLETE.value -> {
                        runOnUiThread {
                            Log.d(logTag, "Server has not completed stitching. Server response: ${msg.obj}")
                            Log.d(logTag, "Waiting for server to complete stitch...")
                        }
                        Log.d(logTag, "Server has not completed stitching. Server response: ${msg.obj}")
                        Log.d(logTag, "Waiting for server to complete stitch...")
                        requester.pollBatch(batchID)
                        runBlocking {
                            delay(3000)
                        }
                    }
                    StitchRequester.StitchResponseCodes.STITCH_RESULT_SUCCESS.value -> {
                        Log.d(logTag, "Retrieval of stitch result is successful")
                        runOnUiThread {
                            photoStitchProgressTextView.text = "Retrieval of stitch result is successful"
                        }
                        photoStitchProgressBar.visibility = View.GONE
                        stitchedImageBitmap = msg.obj as Bitmap
                        stitchedImageView.visibility = View.VISIBLE
                        stitchedImageView.setImageBitmap(stitchedImageBitmap)
                        downloadStitchButton.visibility = View.VISIBLE

                    }
                    StitchRequester.StitchResponseCodes.STITCH_RESULT_FAILURE.value -> {
                        Log.d(logTag, "Retrieval of stitch result is successful")
                        runOnUiThread {
                            photoStitchProgressTextView.text = "Failed to retrieve stitch result. Server response: ${msg.obj}"
                        }
                        Log.d(logTag, "Failed to retrieve stitch result. Server response: ${msg.obj}")
                    }
                }
            }
        }
    }

    //Creating a ViewHolder to store the item views displayed in the RecyclerView
    class ItemHolder(itemView: View) :
        RecyclerView.ViewHolder(itemView) {

        //referencing child views from the item view's layout using their resource ids
        var thumbnailImage: ImageView = itemView.findViewById(R.id.thumbnail) as ImageView
    }

    //Creating an adapter for the RecyclerView
    private inner class FileListAdapter : RecyclerView.Adapter<ItemHolder>() {

        //returns the number of items in the adapter's data set list
        override fun getItemCount(): Int {
            return thumbnailFileList.size
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
            val thumbnail: File = thumbnailFileList[index]
            val thumbnailImg = BitmapFactory.decodeFile(thumbnail.path)
            mItemHolder.thumbnailImage.setImageBitmap(Bitmap.createBitmap(thumbnailImg))

            //mItemHolder.thumbnailImage.setOnClickListener(ImgOnClickListener) //making the thumbnail_img ImageView clickable
            mItemHolder.thumbnailImage.tag = thumbnail //setting the MediaFile object as the thumbnail_img ImageView's tag
            mItemHolder.itemView.tag = index //setting the current mediaFileList index as the itemView's tag

        }
    }
    fun populateRecyclerView(file: File){
        mListAdapter = FileListAdapter()
        val thumbnailDir = File(file.path.toString()+"/Thumbnails")
        val files = thumbnailDir.listFiles()
        //thumbnailFileList.clear()
        for (file in files) {
            if (file.name.endsWith(".jpg") || file.name.endsWith(".png")) {
                thumbnailFileList.add(file)
                mListAdapter.notifyDataSetChanged()
                photosRecyclerView.adapter = mListAdapter
            }
        }
        photoStorageDir = file
        stitchImagesButton.visibility = View.VISIBLE
        mRecyclerViewDialog.dismiss()
    }

}

