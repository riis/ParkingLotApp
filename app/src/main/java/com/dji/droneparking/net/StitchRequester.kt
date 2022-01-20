package com.dji.droneparking.net

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Message
import android.util.Log
import androidx.annotation.Nullable
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.Exception
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.io.*


class StitchRequester(context: Context) {

    //class variables
    private val activity: AppCompatActivity = context as AppCompatActivity
    private var mHandler: Handler? = null

    //completion callbacks
    enum class StitchResponseCodes(val value: Int) {
        START_BATCH_SUCCESS(100), START_BATCH_FAILURE(101),
        STITCH_IMAGE_ADD_SUCCESS(102), STITCH_IMAGE_ADD_FAILURE(103),
        STITCH_BATCH_SUCCESS(104), STITCH_BATCH_FAILURE(105),
        STITCH_POLL_NOT_COMPLETE(106), STITCH_POLL_COMPLETE(107),
        STITCH_RESULT_SUCCESS(108), STITCH_RESULT_FAILURE(109),
    }

    //setting the handler to one provided by PhotoStitcherActivity.kt
    fun setHandler(h: Handler) {
        this.mHandler = h
    }

    //Obtaining a batch id for a new set of images
    fun requestStitchId() {
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                //creating a URL object for the link needed to obtain a batch id
                val url: URL? = getURLforPath(NetworkInformation.START_STITCH_URl)

                //trying to establish a connection to the url and then getting a message from it
                var connection: HttpsURLConnection? = null

                try {
                    if (url != null) {
                        connection = url.openConnection() as HttpsURLConnection
                    }
                    if (connection != null) {
                        connection.requestMethod = "GET"
                    }
                    //reading responses from the connection to get the batch id
                    val r = BufferedReader(InputStreamReader(connection?.inputStream))
                    val batchId: String = r.readLine()

                    connection?.connect() //Opens a communications link to the resource referenced by this URL

                    //sending the Handler a message to start the stitching process with the batchId
                    sendMessage(StitchResponseCodes.START_BATCH_SUCCESS, batchId)

                    //catching any errors and sending them to Handler
                } catch (e: Exception) {
                    sendMessage(StitchResponseCodes.START_BATCH_FAILURE, e.message)

                    //once the thread is complete, close the connection
                } finally {
                    connection?.disconnect()
                }
            }
        }
    }


    /* Add a list of images to the batch by sending it over the network */
    fun addImage(file: File, batchId: String) {
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val boundary = "Boundary-${System.currentTimeMillis()}"
                val url: URL? =
                    getURLforPath(NetworkInformation.ADD_IMAGE_URL + batchId)
                var connection: HttpsURLConnection? = null

                try {
                    if (url != null) {
                        connection = url.openConnection() as HttpsURLConnection
                        Log.d("Requester", connection.toString())
                    }
                    if (connection != null) {

                        connection.addRequestProperty(
                            "Content-Type",
                            "multipart/form-data; boundary=$boundary"
                        )
                        connection.addRequestProperty(
                            "Connection", "close"
                        )
                        connection.requestMethod = "POST"
                        connection.doInput = true
                        connection.doOutput = true

                        val output = connection.outputStream
                        val writer = BufferedWriter(OutputStreamWriter(output))

                        writer.write("\n--$boundary\n")
                        writer.write("Content-Disposition: form-data;"
                                + "name=\"myFile\";"
                                + "filename=\"" + file.name + "\""
                                + "\nContent-Type: image/png\n\n")
                        writer.flush()

                        val inputStreamToFile = FileInputStream(file)
                        var bytesRead: Int
                        val dataBuffer = ByteArray(1024)
                        while (inputStreamToFile.read(dataBuffer).also { bytesRead = it } != -1) {
                            output.write(dataBuffer, 0, bytesRead)
                        }
                        output.flush()

                        // End of the multipart request
                        writer.write("\n--$boundary--\n")
                        writer.flush()

                        // Close the streams
                        output.close()
                        writer.close()
                    }


                    if (connection != null) {
                        if (connection.responseCode == 200) {
                            val r = BufferedReader(InputStreamReader(connection?.inputStream))
                            val uploadedImageName: String = r.readLine()
                            sendMessage(StitchResponseCodes.STITCH_IMAGE_ADD_SUCCESS, uploadedImageName)
                        } else {
                            sendMessage(
                                StitchResponseCodes.STITCH_IMAGE_ADD_FAILURE,
                                connection.responseCode
                            )
                        }
                    }
                    else{
                        sendMessage(StitchResponseCodes.STITCH_IMAGE_ADD_FAILURE, "null connection")
                    }
                    //catching any errors and sending them to Handler
                } catch (e: Exception) {
                    sendMessage(StitchResponseCodes.START_BATCH_FAILURE, e.message + "; server response: " + connection?.responseCode)

                    //once the thread is complete, close the connection
                } finally {
                    connection?.disconnect()
                }
            }
        }
    }

    fun stitchBatch(batchId: String) {
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val url: URL? = getURLforPath(NetworkInformation.STITCH_BATCH_URL + batchId)

                //trying to establish a connection to the url and then getting a message from it
                var connection: HttpsURLConnection? = null
                try {
                    if (url != null) {
                        connection = url.openConnection() as HttpsURLConnection
                    }
                    if (connection != null) {
                        connection.requestMethod = "GET"
                    }

                    connection?.connect() //Opens a communications link to the resource referenced by this URL

                    if (connection != null) {
                        if (connection.responseCode == 200) {
                            sendMessage(StitchResponseCodes.STITCH_BATCH_SUCCESS, null)
                        } else {
                            sendMessage(StitchResponseCodes.STITCH_BATCH_FAILURE, connection.responseCode)
                        }
                    }
                    //catching any errors and sending them to Handler
                } catch (e: Exception) {
                    sendMessage(StitchResponseCodes.STITCH_BATCH_FAILURE, e.message)

                    //once the thread is complete, close the connection
                } finally {
                    connection?.disconnect()
                }
            }
        }
    }

    fun pollBatch(batchId: String) {
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val url: URL? = getURLforPath(NetworkInformation.POLL_BATCH_URL + batchId)

                //trying to establish a connection to the url and then getting a message from it
                var connection: HttpsURLConnection? = null

                try {
                    if (url != null) {
                        connection = url.openConnection() as HttpsURLConnection
                    }
                    if (connection != null) {
                        connection.requestMethod = "GET"
                    }

                    connection?.connect() //Opens a communications link to the resource referenced by this URL

                    if (connection != null) {
                        if (connection.responseCode == 107) {
                            sendMessage(StitchResponseCodes.STITCH_POLL_COMPLETE, null)
                        } else {
                            sendMessage(
                                StitchResponseCodes.STITCH_POLL_NOT_COMPLETE,
                                connection.responseCode
                            )
                        }
                    }
                    //catching any errors and sending them to Handler
                } catch (e: Exception) {
                    sendMessage(StitchResponseCodes.STITCH_POLL_NOT_COMPLETE, e.message)

                    //once the thread is complete, close the connection
                } finally {
                    connection?.disconnect()
                }
            }
        }
    }

    fun retrieveResult(batchId: String, photoDir: File) {
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val url: URL? = getURLforPath(NetworkInformation.RETRIEVE_RESULT_URL + batchId)

                //trying to establish a connection to the url and then getting a message from it
                var connection: HttpsURLConnection? = null

                try {
                    if (url != null) {
                        connection = url.openConnection() as HttpsURLConnection
                    }
                    if (connection != null) {
                        connection.requestMethod = "GET"
                    }

                    connection?.connect() //Opens a communications link to the resource referenced by this URL

                    if (connection != null) {
                        if (connection.responseCode == 200) {
                            val inputStream = BufferedInputStream(connection?.inputStream)
                            val bitmap = BitmapFactory.decodeStream(inputStream)
                            /*
                            val bytes = inputStream.readBytes()
                            File(photoDir.path + "/stitch results/stitch.png").also {
                                    file -> file.parentFile?.mkdirs()
                            }.writeBytes(bytes)

                             */

                            sendMessage(StitchResponseCodes.STITCH_RESULT_SUCCESS, bitmap)
                        } else {
                            sendMessage(
                                StitchResponseCodes.STITCH_RESULT_FAILURE,
                                connection.responseCode
                            )
                        }
                    }
                    //catching any errors and sending them to Handler
                } catch (e: Exception) {
                    sendMessage(StitchResponseCodes.STITCH_RESULT_FAILURE, e.message)

                    //once the thread is complete, close the connection
                } finally {
                    connection?.disconnect()
                }
            }
        }
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

    private fun sendMessage(msg: StitchResponseCodes, @Nullable companion: Any?) {
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

}