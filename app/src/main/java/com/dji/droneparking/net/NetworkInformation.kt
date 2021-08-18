package com.dji.droneparking.net

//This class contains all of the links as strings needed to make 5 different photo-stitching API calls
class NetworkInformation {

    companion object {
        private const val STITCHING_SERVER_IP = "https://opencv.riis.com"
        const val PATH_STITCH_START: String = "$STITCHING_SERVER_IP/start_stitch_batch"
        const val PATH_STITCH_ADD_IMAGE: String = "$STITCHING_SERVER_IP/add_stitch_image"
        const val PATH_STITCH_LOCK: String = "$STITCHING_SERVER_IP/lock_batch"
        const val PATH_STITCH_POLL: String = "$STITCHING_SERVER_IP/poll_batch"
        const val PATH_STITCH_RETRIEVE: String = "$STITCHING_SERVER_IP/retrieve_result"
    }

}