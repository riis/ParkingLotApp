package com.dji.droneparking.net

//This class contains all of the links as strings needed to make 5 different photo-stitching API calls
class NetworkInformation {

    companion object {
        private const val STITCHING_SERVER_IP = "http://ec2-50-19-78-20.compute-1.amazonaws.com:8000"
        const val START_STITCH_URl: String = "$STITCHING_SERVER_IP/start_stitch_batch"
        const val ADD_IMAGE_URL: String = "$STITCHING_SERVER_IP/add_stitch_image/"
        const val STITCH_BATCH_URL: String = "$STITCHING_SERVER_IP/stitch_batch/"
        const val POLL_BATCH_URL: String = "$STITCHING_SERVER_IP/poll_batch/"
        const val RETRIEVE_RESULT_URL: String = "$STITCHING_SERVER_IP/retrieve_result/"
    }
}

