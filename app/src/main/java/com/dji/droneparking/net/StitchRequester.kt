package com.dji.droneparking.net

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.os.Handler
import android.os.Message
import android.util.Log
import androidx.annotation.Nullable
import com.bumptech.glide.Glide
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.lang.Exception
import java.net.URL

import javax.net.ssl.HttpsURLConnection


// This class makes HTTP requests to the stitching server while giving back progress updates
class StitchRequester {

    //class variables
    private var mHandler: Handler? = null
    private var batchId: String? = null
    private val xtag = "cattle"

    //completion callbacks
    enum class StitchMessage(val value: Int) {
        STITCH_START_SUCCESS(100), STITCH_START_FAILURE(101), STITCH_IMAGE_ADD_SUCCESS(102), STITCH_IMAGE_ADD_FAILURE(
            103
        ),
        STITCH_LOCK_SUCCESS(104), STITCH_LOCK_FAILURE(105), STITCH_POLL_NOT_COMPLETE(106), STITCH_POLL_COMPLETE(
            107
        ),
        STITCH_RESULT_SUCCESS(108), STITCH_RESULT_FAILURE(109);

    }

    //setting the handler to one provided by PhotoStitcherActivity.kt
    fun setHandler(h: Handler) {
        this.mHandler = h

    }

    //Obtaining a batch id for a new set of images
    fun requestStitchId() {
        Thread {
            //creating a URL object for the link needed to obtain a batch id
            val url: URL? = getURLforPath(NetworkInformation.PATH_STITCH_START)

            //trying to establish a connection to the url and then posting a message to it
            var connection: HttpsURLConnection? = null
            try {
                if (url != null) {
                    connection = url.openConnection() as HttpsURLConnection
                }
                if (connection != null) {
                    connection.requestMethod = "POST"
                }
                //reading responses from the connection to get the batch id
                val r = BufferedReader(InputStreamReader(connection?.inputStream))
                val batchId: String = r.readLine()
                this.batchId = batchId

                connection?.connect() //Opens a communications link to the resource referenced by this URL

                //sending the Handler a message to start the stitching process with the batchId
                sendMessage(StitchMessage.STITCH_START_SUCCESS, batchId)

            //catching any errors and sending them to Handler
            } catch (e: Exception) {
                sendMessage(StitchMessage.STITCH_LOCK_FAILURE, e.localizedMessage)
                e.printStackTrace()

            //once the thread is complete, close the connection
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    /* Add a list of images to the batch by sending it over the network */
    fun addImage(image: Bitmap) {
        Thread {

            val url: URL? = getURLforPath(NetworkInformation.PATH_STITCH_ADD_IMAGE)
            var connection: HttpsURLConnection? = null
            try {
                if (url != null) {
                    connection = url.openConnection() as HttpsURLConnection
                }
                if (connection != null) {
                    connection.requestMethod = "POST"
                }
                connection?.setRequestProperty("Batch-Id", batchId)
                connection?.setRequestProperty("X-tag", xtag)
                connection?.setRequestProperty("Content-Type", "image/png")
                // Add the image data to the request.
                connection?.doInput = true
                connection?.doOutput = true
                // Send the request.
                connection?.connect()
                val output: OutputStream? = connection?.outputStream
                image.compress(Bitmap.CompressFormat.PNG, 100, output)
                output?.close()

                if (connection != null) {
                    if (connection.responseCode == 202) {
                        sendMessage(StitchMessage.STITCH_IMAGE_ADD_SUCCESS, null)
                    } else {
                        sendMessage(
                            StitchMessage.STITCH_IMAGE_ADD_FAILURE,
                            "Response code: " + connection.responseCode
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "addImagesrunException: $e")
                sendMessage(StitchMessage.STITCH_IMAGE_ADD_FAILURE, null)
                e.printStackTrace()
            } finally {
                connection?.disconnect()
            }

            }.start()

    }

    fun lockBatch() {
        Thread {
            val url: URL? = getURLforPath(NetworkInformation.PATH_STITCH_LOCK)
            var connection: HttpsURLConnection? = null
            try {
                if (url != null) {
                    connection = url.openConnection() as HttpsURLConnection
                }
                connection?.requestMethod = "POST"
                connection?.setRequestProperty("Batch-Id", batchId)
                connection?.setRequestProperty("X-tag", xtag)
                connection?.connect()
                if (connection != null) {
                    if (connection.responseCode == 200) {
                        sendMessage(StitchMessage.STITCH_LOCK_SUCCESS, null)
                    } else {
                        sendMessage(StitchMessage.STITCH_LOCK_FAILURE, null)
                    }
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
                sendMessage(StitchMessage.STITCH_LOCK_FAILURE, null)
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    fun pollBatch() {
        Thread {
            val url: URL? = getURLforPath(NetworkInformation.PATH_STITCH_POLL)
            var connection: HttpsURLConnection? = null
            try {
                if (url != null) {
                    connection = url.openConnection() as HttpsURLConnection
                }
                connection?.requestMethod = "POST"
                connection?.setRequestProperty("Batch-Id", batchId)
                connection?.setRequestProperty("X-tag", xtag)
                connection?.connect()
                if (connection != null) {
                    if (connection.responseCode == HttpsURLConnection.HTTP_OK) {
                        sendMessage(StitchMessage.STITCH_POLL_COMPLETE, null)
                    } else {
                        sendMessage(StitchMessage.STITCH_POLL_NOT_COMPLETE, null)
                    }
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
                sendMessage(StitchMessage.STITCH_POLL_NOT_COMPLETE, null)
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    fun retrieveResult() {
        Thread {
            val url: URL? = getURLforPath(NetworkInformation.PATH_STITCH_RETRIEVE)
            var connection: HttpsURLConnection? = null
            try {
                if (url != null) {
                    connection = url.openConnection() as HttpsURLConnection
                }
                connection?.requestMethod = "GET"
                connection?.setRequestProperty("Batch-Id", batchId)
                connection?.setRequestProperty("x-tag", xtag)
                connection?.doInput = true
                val inputStream =
                    BufferedInputStream(connection?.inputStream)
                //contains resulting stitch
                val b = BitmapFactory.decodeStream(inputStream)
                connection?.connect()
                if (connection != null) {
                    if (connection.responseCode == HttpsURLConnection.HTTP_BAD_REQUEST) {
                        sendMessage(StitchMessage.STITCH_RESULT_FAILURE, null)
                    } else {
                        sendMessage(StitchMessage.STITCH_RESULT_SUCCESS, b)
                    }
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
                sendMessage(StitchMessage.STITCH_RESULT_FAILURE, null)
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    @Nullable
    private fun getURLforPath(path: String): URL? {
        try {
            return URL(path)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun sendMessage(msg: StitchMessage, @Nullable companion: Any?) {
        val value = msg.value
        val m: Message? = mHandler?.obtainMessage()
        if (m != null) {
            m.what = value
        }
        if (companion != null) if (m != null) {
            m.obj = companion
        }
        m?.sendToTarget()
    }

    companion object {
        private const val TAG = "StitchRequester"
    }
}