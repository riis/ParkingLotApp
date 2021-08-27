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
import java.io.FileOutputStream
import kotlin.collections.HashMap


class PhotoStitcherActivity() : AppCompatActivity(), View.OnClickListener{

    //class variables
    private var thumbnailFileList: MutableList<File> = mutableListOf() //empty list of MediaFile objects
    private lateinit var photoStitchProgressBar: ProgressBar
    private lateinit var downloadButtonsLayout: LinearLayout
    private lateinit var downloadSDCardButton: Button
    lateinit var downloadOtherMissionsButton: Button
    private lateinit var stitchImagesButton: Button
    private lateinit var downloadStitchButton: Button
    private lateinit var mListAdapter: FileListAdapter
    private lateinit var mRecyclerViewDialog: RecyclerViewDialog
    private lateinit var stitchedImage: Bitmap


    companion object {
        lateinit var photosRecyclerView: RecyclerView
        lateinit var photoStorageDir: File

    }

    //photoStitching var
    private var stitchRequester: StitchRequester? = null
    private lateinit var stitchedImageView: ImageView

    private lateinit var photoStitchProgressTextView: TextView
    private var imageUploadCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_photo_stitcher)
        initUI()

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


    private fun initUI(){

        downloadButtonsLayout = findViewById(R.id.download_buttons_layout)
        downloadSDCardButton = findViewById(R.id.download_sd_card_button)
        downloadOtherMissionsButton = findViewById(R.id.download_other_missions_button)
        downloadStitchButton = findViewById(R.id.download_stitch_button)

        stitchedImageView = findViewById(R.id.stitched_image_view)
        stitchImagesButton = findViewById(R.id.stitch_images_button)
        photoStitchProgressTextView = findViewById(R.id.photo_stitch_progress_text_view)

        downloadSDCardButton.setOnClickListener(this)
        downloadOtherMissionsButton.setOnClickListener(this)
        stitchImagesButton.setOnClickListener(this)
        downloadStitchButton.setOnClickListener(this)

        photoStitchProgressBar = findViewById(R.id.photo_stitch_progress_bar)

        photosRecyclerView = findViewById(R.id.photos_recycler_view)
        photosRecyclerView.layoutManager =
            GridLayoutManager(this, 2) //second parameter specifies number of columns in grid

    }


    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.stitch_images_button -> {
                startPhotoStitch()
                stitchImagesButton.visibility = View.GONE
            }
            R.id.download_sd_card_button -> {
                PhotoDownloader(this).setStitchButtonVisibilityListener() { stitchButtonVisibility ->
                    if (stitchButtonVisibility) {
                        stitchImagesButton.visibility = View.VISIBLE
                    }
                }
                downloadButtonsLayout.visibility = View.GONE
            }

            R.id.download_other_missions_button -> {

                downloadButtonsLayout.visibility = View.GONE

                val parentDir = File(this.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.path.toString())

                mRecyclerViewDialog = RecyclerViewDialog(parentDir.listFiles())
                mRecyclerViewDialog.show(this.supportFragmentManager, "tagCanBeWhatever")
            }
            R.id.download_stitch_button ->{
                Log.d("BANANAPIE", "download button")
                val file = File(this.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.path.toString()+"/${photoStorageDir.nameWithoutExtension}/stitch.png")
                Log.d("BANANAPIE", file.toString())
                file.parentFile?.mkdirs() // Will create parent directories if not exists
                file.createNewFile()
                val outputStream = FileOutputStream(file, false)

                stitchedImage.compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    outputStream
                )
                outputStream.flush()
                outputStream.close()
                Log.d("BANANAPIE", "stitch downloaded")
                showToast("stitch downloaded")
            }
            }
        }




    //Function that displays toast messages to the user
    private fun showToast(msg: String?) {
        this.runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }




    private fun getDownloadedImagesList(): MutableList<Bitmap>{
        val downloadedImageList = mutableListOf<Bitmap>()
        Log.d("BANANAPIE", photoStorageDir.toString())

        val files = photoStorageDir.listFiles()
        for (file in files){

            if (file.name.endsWith(".jpg") || file.name.endsWith(".png") || file.name.endsWith(".JPG")){
                if (file.nameWithoutExtension != "stitch"){
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 800, 450, true) //800, 450

                    Log.d("BANANAPIE", "adding image ${file.nameWithoutExtension}")
                    downloadedImageList.add(resizedBitmap)
                }

            }
        }
        return downloadedImageList

    }

    private fun startPhotoStitch(){

        stitchRequester = StitchRequester(this)
        val stitchMessageHandler: Handler = getStitchHandler(stitchRequester!!)
        stitchRequester!!.setHandler(stitchMessageHandler)
        stitchRequester!!.requestStitchId()
    }

    private fun getStitchHandler(requester: StitchRequester): Handler {

        val displayables: HashMap<Int, String> = HashMap()
        displayables[StitchRequester.StitchMessage.STITCH_START_SUCCESS.value] = "Connecting to server..."
        displayables[StitchRequester.StitchMessage.STITCH_START_FAILURE.value] = "Couldn't start stitch."
        displayables[StitchRequester.StitchMessage.STITCH_IMAGE_ADD_SUCCESS.value] = "uploading image "
        displayables[StitchRequester.StitchMessage.STITCH_IMAGE_ADD_FAILURE.value] = "Could not upload "
        displayables[StitchRequester.StitchMessage.STITCH_LOCK_SUCCESS.value] = "Stitch locked."
        displayables[StitchRequester.StitchMessage.STITCH_LOCK_FAILURE.value] = "Stitch couldn't be locked."
        displayables[StitchRequester.StitchMessage.STITCH_POLL_COMPLETE.value] = "Stitch completed. Downloading..."
        displayables[StitchRequester.StitchMessage.STITCH_POLL_NOT_COMPLETE.value] = "Waiting for stitch..."
        displayables[StitchRequester.StitchMessage.STITCH_RESULT_SUCCESS.value] = "Stitch complete."
        displayables[StitchRequester.StitchMessage.STITCH_RESULT_FAILURE.value] = "Could not download stitch!"

        return object : Handler(Looper.getMainLooper()) {

            override fun handleMessage(msg: Message) {
                val what: Int = msg.what
                runOnUiThread {
                    photoStitchProgressTextView.visibility = View.VISIBLE
                    photoStitchProgressBar.visibility = View.VISIBLE
                }
                Log.d("STITCH", what.toString())

                when (what) {

                    StitchRequester.StitchMessage.STITCH_IMAGE_ADD_SUCCESS.value-> {
                        runOnUiThread{
                            photoStitchProgressTextView.text = displayables[what] + "${imageUploadCount+1}/${getDownloadedImagesList().size}"
                        }
                    }
                    StitchRequester.StitchMessage.STITCH_IMAGE_ADD_FAILURE.value -> {
                        runOnUiThread {
                            photoStitchProgressTextView.text = displayables[what] + "${imageUploadCount+1}/${getDownloadedImagesList().size}, retrying..."
                        }
                    }
                    else -> {
                        runOnUiThread {
                            photoStitchProgressTextView.text = displayables[what]
                        }
                    }
                }
                Log.d("STITCH", "handleMessage: " + photoStitchProgressTextView.text)

                when (what){
                    StitchRequester.StitchMessage.STITCH_START_SUCCESS.value -> {
                        Log.d("STITCH", "Requesting upload start")
                        runOnUiThread {
                            photoStitchProgressBar.visibility = View.VISIBLE
                        }

                        for (image in getDownloadedImagesList()){ //mediaFileList
                            requester.addImage(image) //image.thumbnail
                        }
                    }
                    StitchRequester.StitchMessage.STITCH_IMAGE_ADD_FAILURE.value -> {
                        Log.d("STITCH", "failed to upload image to server, retrying...")
                        if (imageUploadCount != 0){
                            requester.addImage(getDownloadedImagesList()[imageUploadCount-1])
                        }
                        else{
                            requester.addImage(getDownloadedImagesList()[0])
                        }
                    }
                    StitchRequester.StitchMessage.STITCH_IMAGE_ADD_SUCCESS.value -> {
                        Log.d("STITCH", "successfully uploaded image to server, moving on...")
                        imageUploadCount++

                        if(imageUploadCount == getDownloadedImagesList().size) {
                            val str =  "Locking stitch..."
                            photoStitchProgressTextView.text = str
                            requester.lockBatch()
                        }
                    }
                    StitchRequester.StitchMessage.STITCH_LOCK_SUCCESS.value -> {
                        Log.d("STITCH", "successfully locked batch")
                        requester.pollBatch()
                    }
                    StitchRequester.StitchMessage.STITCH_POLL_NOT_COMPLETE.value -> {
                        this.postDelayed({
                            Log.d("STITCH", "stitch poll not complete, retrying...")
                            requester.pollBatch()
                        }, 3000)
                    }
                    StitchRequester.StitchMessage.STITCH_POLL_COMPLETE.value -> {
                        Log.d("STITCH", "stitch poll complete")
                        requester.retrieveResult()
                    }
                    StitchRequester.StitchMessage.STITCH_RESULT_SUCCESS.value -> {
                        Log.d("STITCH", "successfully retrieved stitch results")
                        photoStitchProgressBar.visibility = View.GONE
                        stitchedImage = msg.obj as Bitmap
                        stitchedImageView.visibility = View.VISIBLE
                        stitchedImageView.setImageBitmap(stitchedImage)
                        downloadStitchButton.visibility = View.VISIBLE
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

