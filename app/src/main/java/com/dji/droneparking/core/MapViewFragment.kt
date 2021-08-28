package com.dji.droneparking.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.viewModels
import com.dji.droneparking.R
import com.dji.droneparking.dialog.LoadingDialog
import com.dji.droneparking.util.*
import com.dji.droneparking.viewmodel.FlightPlanActivityViewModel
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.maps.*
import com.mapbox.mapboxsdk.plugins.annotation.*
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.riis.cattlecounter.util.distanceToSegment
import dji.common.bus.UXSDKEventBus
import dji.common.camera.SettingsDefinitions
import dji.common.mission.waypoint.WaypointMission
import dji.common.model.LocationCoordinate2D
import dji.sdk.camera.VideoFeeder
import dji.sdk.gimbal.Gimbal
import dji.sdk.media.MediaFile
import dji.sdk.media.MediaManager
import dji.sdk.products.Aircraft
import dji.ux.widget.TakeOffWidget
import kotlin.system.measureTimeMillis




class MapViewFragment : Fragment(), OnMapReadyCallback,
    MapboxMap.OnMapClickListener, View.OnClickListener {

    private val vM: FlightPlanActivityViewModel by viewModels()

    private var mapTouch: Boolean = false

    private lateinit var cancelFlightBtn: Button

    //    private lateinit var overlayView: LinearLayout
//    private lateinit var layoutConfirmPlan: LinearLayout
//    private lateinit var layoutCancelPlan: LinearLayout
    private lateinit var altitudeTV: TextView
    private lateinit var startFlightBtn: Button
    private lateinit var locateBtn: Button
    private lateinit var takeoffWidget: TakeOffWidget
    private lateinit var mContext: Context
    private lateinit var cameraBtn: Button
    private lateinit var videoView: FrameLayout
    private lateinit var mapView: FrameLayout

    private lateinit var gimbal: Gimbal


    private lateinit var mLoadingDialog: LoadingDialog

    private var currentDeleteIndex = 0
    private var numOfOldPhotos = 0

    private var mMediaManager: MediaManager? = null //uninitialized media manager
    private var mediaFileList: MutableList<MediaFile> =
        mutableListOf() //empty list of MediaFile objects
//    private lateinit var operator: MavicMiniMissionOperator

    private val symbolDragListener = object : OnSymbolDragListener {
        override fun onAnnotationDrag(symbol: Symbol) {}

        override fun onAnnotationDragFinished(symbol: Symbol) {
            configureFlightPlan(symbol)
        }

        override fun onAnnotationDragStarted(symbol: Symbol) {

            vM.symbols.remove(symbol)
            vM.polygonCoordinates.remove(symbol.latLng)

        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_map_view, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        vM.operator = context?.let { vM.getWaypointMissionOperator(it) }!!

//        val rotation = Rotation.Builder().mode(RotationMode.ABSOLUTE_ANGLE).pitch(-90f).build()
//        try {
//            gimbal = DJISDKManager.getInstance().product.gimbal
//
//            gimbal.rotate(
//                rotation
//            ) { djiError ->
//                if (djiError == null) {
//                    Log.d("BANANAPIE", "rotate gimbal success")
//                    Toast.makeText(applicationContext, "rotate gimbal success", Toast.LENGTH_SHORT)
//                        .show()
//                } else {
//                    Log.d("BANANAPIE", "rotate gimbal error " + djiError.description)
//                    Toast.makeText(applicationContext, djiError.description, Toast.LENGTH_SHORT)
//                        .show()
//                }
//            }
//        } catch (e: Exception) {
//            Log.d("BANANAPIE", "Drone is likely not connected")
//        }


//        setContentView(R.layout.activity_flight_plan_mapbox)

        initUI()

        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mContext = context as Context
        Mapbox.getInstance(mContext, getString(R.string.mapbox_api_key))

        vM.mapFragment =
            childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        vM.mapFragment.onCreate(savedInstanceState)
        vM.mapFragment.getMapAsync(this)


        vM.operator!!.droneLocationLiveData.observe(viewLifecycleOwner, { location ->
            altitudeTV.text = "${"%.1f".format(location.altitude)}\nm"

            if (vM.styleReady) {

                vM.droneLocation = location


                if (!vM.located) {
                    cameraUpdate()
                    vM.located = true
                }

                updateDroneLocation()
            }
        })
        showClearMemoryDialog()
    }

    override fun onMapReady(mapboxMap: MapboxMap) {

        vM.mbMap = mapboxMap

        mapboxMap.setStyle(Style.SATELLITE_STREETS) { style: Style ->

            vM.styleReady = true
            var bm: Bitmap? =
                Tools.bitmapFromVectorDrawable(mContext, R.drawable.ic_waypoint_marker_unvisited)
            bm?.let { mapboxMap.style?.addImage("ic_waypoint_marker_unvisited", it) }

            bm = Tools.bitmapFromVectorDrawable(mContext, R.drawable.ic_drone)
            bm?.let { mapboxMap.style?.addImage("ic_drone", it) }

            vM.fillManager =
                FillManager(vM.mapFragment.view as MapView, mapboxMap, style)
            vM.lineManager =
                LineManager(vM.mapFragment.view as MapView, mapboxMap, style)

            vM.symbolManager =
                SymbolManager(vM.mapFragment.view as MapView, mapboxMap, style)
            vM.symbolManager.iconAllowOverlap = true
            vM.symbolManager.addDragListener(symbolDragListener)


        }

        val position = CameraPosition.Builder()
            .zoom(18.0)
            .build()

        mapboxMap.animateCamera(CameraUpdateFactory.newCameraPosition(position))

        vM.mbMap?.addOnMapClickListener(this)
    }

    override fun onMapClick(point: LatLng): Boolean {

        if (mapTouch) {
            val symbol = vM.symbolManager.create(
                SymbolOptions()
                    .withLatLng(LatLng(point))
                    .withIconImage("ic_waypoint_marker_unvisited")
                    .withIconHaloWidth(2.0f)
                    .withDraggable(true)
            )
            //      Add new point to the list
            configureFlightPlan(symbol)
            return true
        } else {
            return false
        }

    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.locate_button -> {
                updateDroneLocation()
                cameraUpdate()
            }
//            R.id.get_started_button -> {
////                overlayView.animate()
////                    .alpha(0.0f)
////                    .translationXBy(1000f)
////                    .setDuration(500)
////                    .setListener(null)
////                    .start()
//
//                locateBtn.visibility = View.VISIBLE
//                cameraBtn.visibility = View.VISIBLE
//                showClearMemoryDialog()
//            }
            R.id.start_flight_button -> {
                when (vM.aircraft) {
                    null -> {
                        Toast.makeText(mContext, "Please connect to a drone", Toast.LENGTH_SHORT)
                            .show()
                    }
                    else -> {

                        val djiMission: WaypointMission =
                            FlightPlanner.createFlightMissionFromCoordinates(vM.flightPlan2D)

                        vM.manager =
                            context?.let {
                                vM.operator?.let { it1 ->
                                    WaypointMissionManager(
                                        djiMission,
                                        it1,
                                        it,
                                        this
                                    )
                                }
                            }


                        vM.manager?.startMission()
                        mapTouch = false
//                        layoutConfirmPlan.visibility = View.GONE
//                        layoutCancelPlan.visibility = View.VISIBLE
                    }
                }
            }
            R.id.cancel_flight_button -> {
                vM.manager?.stopFlight() ?: return
                clearMapViews()
                mapTouch = true
//                layoutCancelPlan.visibility = View.GONE
            }
            R.id.cancel_flight_plan_button -> {
//                layoutConfirmPlan.visibility = View.GONE
                clearMapViews()
            }
            R.id.camera_button -> {
                videoView.visibility = View.VISIBLE
                mapView.visibility = View.INVISIBLE
            }
        }
    }


    private fun showClearMemoryDialog() {
        val dialog = AlertDialog.Builder(context as Context)
            .setMessage(R.string.ensure_clear_sd)
            .setCancelable(false)
            .setTitle(R.string.title_clear_sd)
            .setNegativeButton(R.string.no) { _, _ -> mapTouch = true }
            .setPositiveButton(R.string.yes) { _, _ ->
                val t = measureTimeMillis {
                    clearSDCard()
                }
                Log.d("BANANAPIE", "SD card cleared in $t ms")
            }
            .create()

        dialog.show()
    }

    private fun clearSDCard() {
        Log.d("BANANAPIE", "attempting to update the media file list from the SD card")
        activity?.let { mLoadingDialog.show(it.supportFragmentManager, "tagCanBeWhatever") }
        DJIDemoApplication.getCameraInstance()
            ?.let { camera ->
                mMediaManager = camera.mediaManager
                mMediaManager?.let { mediaManager ->

                    //refreshing the MediaManager's file list using the connected DJI product's SD card
                    mediaManager.refreshFileListOfStorageLocation(
                        SettingsDefinitions.StorageLocation.SDCARD //file storage location
                    ) { djiError -> //checking the callback error

                        if (djiError == null) {
                            Log.d(
                                "BANANAPIE",
                                "obtained media data from SD card (FlightPlanActivity) - 1"
                            )
                            camera.formatSDCard { DJIError ->
                                if (DJIError == null) {
                                    Log.d(
                                        "BANANAPIE",
                                        "successfully cleared SD card"
                                    )
                                    mLoadingDialog.dismiss()
                                    mapTouch = true
                                } else {
                                    Log.d(
                                        "BANANAPIE",
                                        "SD Card clear error: $DJIError"
                                    )
                                }
                            }
                        } else {
                            Log.d(
                                "BANANAPIE",
                                "could not obtain media data from SD card (FlightPlanActivity)"
                            )
                        }
                    }
                }
            }
    }


    private fun initUI() {

        mLoadingDialog = LoadingDialog("Clearing SD card...")
        mLoadingDialog.isCancelable = false


        altitudeTV = requireView().findViewById(R.id.altitudeTV)
        videoView = activity?.findViewById(R.id.video_view_layout)!!
        mapView = activity?.findViewById(R.id.map_view_layout)!!
        startFlightBtn = requireView().findViewById(R.id.start_flight_button)
        cancelFlightBtn = requireView().findViewById(R.id.cancel_flight_button)
        locateBtn = requireView().findViewById(R.id.locate_button)
        val cancelFlightPlanBtn: Button = requireView().findViewById(R.id.cancel_flight_plan_button)

        takeoffWidget = requireView().findViewById(R.id.takeoff_widget_flight_plan)
//        layoutConfirmPlan = requireView().findViewById(R.id.ll_confirm_flight_plan)
//        layoutCancelPlan = requireView().findViewById(R.id.ll_cancel_flight_plan)


        cameraBtn = requireView().findViewById(R.id.camera_button)


        //TODO change this to implement SDKManager or something better
        vM.aircraft = DJIDemoApplication.getProductInstance() as Aircraft

        locateBtn.setOnClickListener(this)
//        getStartedBtn.setOnClickListener(this)
        startFlightBtn.setOnClickListener(this)
        cancelFlightPlanBtn.setOnClickListener(this)
        cancelFlightBtn.setOnClickListener(this)
        cameraBtn.setOnClickListener(this)

    }

    private fun cameraUpdate() {
        if (vM.droneLocation.latitude.isNaN() || vM.droneLocation.longitude.isNaN()) {
            return
        }
        val pos = LatLng(vM.droneLocation.latitude, vM.droneLocation.longitude)
        val zoomLevel = 18.0
        val cameraUpdate = CameraUpdateFactory.newLatLngZoom(pos, zoomLevel)
        activity?.runOnUiThread {
            vM.mbMap?.moveCamera(cameraUpdate)
        }
    }

    private fun configureFlightPlan(symbol: Symbol) {

        vM.symbols.add(symbol)

        val point = symbol.latLng
        var minDistanceIndex = -1
        var nextIndex: Int

        startFlightBtn.visibility = View.GONE

        if (vM.polygonCoordinates.size < 3) { // Only Draw Area of Flight Plan
            vM.polygonCoordinates.add(point)
            updatePoly()
        } else { // Draw Area and Configure Flight Plan
            var minDistance = Double.MAX_VALUE

            for (i in vM.polygonCoordinates.indices) {
                // place new coordinate in list such that the distance between it and the line segment
                // created by its neighbouring coordinates are minimized

                nextIndex = if (i == vM.polygonCoordinates.size - 1) {
                    0
                } else {
                    i + 1
                }

                val distance: Double =
                    distanceToSegment(
                        vM.polygonCoordinates[i],
                        vM.polygonCoordinates[nextIndex],
                        point
                    )

                if (distance < minDistance) {
                    minDistance = distance
                    minDistanceIndex = i + 1
                }
            }

            vM.polygonCoordinates.add(minDistanceIndex, point)
            updatePoly()

            try {
                drawFlightPlan()
            } catch (ignored: Exception) {
            }
        }

//        layoutConfirmPlan.visibility = View.VISIBLE
    }

    private fun drawFlightPlan() {

        vM.symbolManager.removeDragListener(symbolDragListener) // prevents infinite loop


        if (vM.polygonCoordinates.size < 3) return

        startFlightBtn.visibility = View.VISIBLE

        var minLat = Int.MAX_VALUE.toDouble()
        var minLon = Int.MAX_VALUE.toDouble()
        var maxLat = Int.MIN_VALUE.toDouble()
        var maxLon = Int.MIN_VALUE.toDouble()

        for (c in vM.polygonCoordinates) {
            if (c.latitude < minLat) minLat = c.latitude
            if (c.latitude > maxLat) maxLat = c.latitude
            if (c.longitude < minLon) minLon = c.longitude
            if (c.longitude > maxLon) maxLon = c.longitude
        }

        val newPoints: MutableList<LatLng> = java.util.ArrayList()

        newPoints.add(LatLng(minLat, minLon))
        newPoints.add(LatLng(minLat, maxLon))
        newPoints.add(LatLng(maxLat, maxLon))
        newPoints.add(LatLng(maxLat, minLon))

        try {

            vM.flightPlan =
                FlightPlanner.createFlightPlan(
                    newPoints,
                    35.0f,
                    vM.polygonCoordinates
                ) // get plan

            vM.flightPlanLine?.let { line -> // delete on plan on map and reset arrays
                vM.lineManager.delete(line)
                vM.symbolManager.delete(vM.flightPathSymbols)
                vM.flightPlan2D.clear()
                vM.flightPathSymbols.clear()
            }

            for (coordinate in vM.flightPlan) { // populate new plan and place markers on map
                vM.flightPlan2D.add(
                    LocationCoordinate2D(
                        coordinate.latitude,
                        coordinate.longitude
                    )
                )

                val flightPathSymbol: Symbol = vM.symbolManager.create(
                    SymbolOptions()
                        .withLatLng(LatLng(coordinate))
                        .withIconImage("ic_waypoint_marker_unvisited")
                        .withIconSize(0.5f)
                )

                vM.flightPathSymbols.add(flightPathSymbol)
            }


            val lineOptions: LineOptions = LineOptions()
                .withLatLngs(vM.flightPlan)
                .withLineWidth(2.0f)
                .withLineColor(PropertyFactory.fillColor(Color.parseColor("#FFFFFF")).value)

            vM.flightPlanLine = vM.lineManager.create(lineOptions)

        } catch (e: FlightPlanner.NotEnoughPointsException) {

            Tools.showToast(
                activity as FlightPlanActivity,
                "Could not create flight plan! Try a larger area."
            )

            clearMapViews()
            showOriginalControls()
            e.printStackTrace()
        }

        vM.symbolManager.addDragListener(symbolDragListener)
    }

    private fun checkGpsCoordination(latitude: Double, longitude: Double): Boolean {
        return latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180 && latitude != 0.0 && longitude != 0.0
    }

    // Redraw polygons fill after having changed the container values.
    private fun updatePoly() {

        val polygonCoordinatesCopy: MutableList<LatLng> = ArrayList(vM.polygonCoordinates)
        polygonCoordinatesCopy.add(vM.polygonCoordinates[0])

        vM.line?.let { vM.lineManager.delete(it) }

        val lineOpts: LineOptions = LineOptions()
            .withLineColor(PropertyFactory.lineColor("#FFFFFF").value)
            .withLatLngs(polygonCoordinatesCopy)

        vM.line = vM.lineManager.create(lineOpts)

        val fillCoordinates: MutableList<List<LatLng>> = java.util.ArrayList()
        fillCoordinates.add(polygonCoordinatesCopy)

        vM.fill?.let { vM.fillManager.delete(it) }

        val fillOpts: FillOptions = FillOptions()
            .withFillColor(PropertyFactory.fillColor(Color.parseColor("#81FFFFFF")).value)
            .withLatLngs(fillCoordinates)

        vM.fill = vM.fillManager.create(fillOpts)
    }

    private fun updateDroneLocation() {
        activity?.runOnUiThread {

            if (vM.droneLocation.latitude.isNaN() || vM.droneLocation.longitude.isNaN()) {
                return@runOnUiThread
            }

            if (checkGpsCoordination(
                    vM.droneLocation.latitude,
                    vM.droneLocation.longitude
                )
            ) {

                val pos =
                    LatLng(vM.droneLocation.latitude, vM.droneLocation.longitude)

                vM.droneSymbol?.let { drone ->
                    vM.symbolManager.delete(drone)
                }

                vM.droneSymbol = vM.symbolManager.create(
                    SymbolOptions()
                        .withLatLng(pos)
                        .withIconImage("ic_drone")
                        .withDraggable(false)
                )
            }

        }
    }

    fun animateNextButton() {
        activity?.runOnUiThread {
            takeoffWidget.visibility = View.VISIBLE
            cancelFlightBtn.visibility = View.INVISIBLE
            clearMapViews()
        }
    }

    private fun showOriginalControls() {
//        layoutCancelPlan.visibility = View.GONE
//        layoutConfirmPlan.visibility = View.GONE
    }

    private fun clearMapViews() {
        if (vM.symbols.size > 0) {
            vM.symbolManager.delete(vM.symbols)
        }

        if (vM.flightPathSymbols.size > 0) {
            vM.symbolManager.delete(vM.flightPathSymbols)
        }

        if (vM.flightPlanLine != null) {
            vM.lineManager.delete(vM.flightPlanLine)
        }

        vM.fillManager.delete(vM.fill)
        vM.lineManager.delete(vM.line)

        vM.symbols.clear()
        vM.polygonCoordinates.clear()
        vM.flightPlan2D.clear()
    }
}