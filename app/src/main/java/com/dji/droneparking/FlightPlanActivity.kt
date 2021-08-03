package com.dji.droneparking


import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.dji.droneparking.util.*
import com.dji.droneparking.util.Tools.showToast
import com.mapbox.android.core.permissions.PermissionsListener
import com.mapbox.android.core.permissions.PermissionsManager
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.location.LocationComponent
import com.mapbox.mapboxsdk.location.LocationComponentActivationOptions
import com.mapbox.mapboxsdk.location.modes.CameraMode
import com.mapbox.mapboxsdk.location.modes.RenderMode
import com.mapbox.mapboxsdk.maps.*
import com.mapbox.mapboxsdk.plugins.annotation.*
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.riis.cattlecounter.util.distanceToSegment
import dji.common.mission.waypoint.WaypointMission
import dji.common.model.LocationCoordinate2D
import dji.sdk.products.Aircraft
import dji.ux.widget.TakeOffWidget
import java.util.*

class FlightPlanActivity : AppCompatActivity(), PermissionsListener, OnMapReadyCallback,
    MapboxMap.OnMapClickListener {


//    private lateinit var downloader: DJIImageDownloader

    private var aircraft: Aircraft? = null
    private lateinit var stitchBtn: Button
    private lateinit var btnCancelFlight: Button
    private lateinit var overlayView: LinearLayout
    private lateinit var layoutConfirmPlan: LinearLayout
    private lateinit var layoutCancelPlan: LinearLayout
    private lateinit var confirmFlightButton: Button
    private lateinit var takeoffWidget: TakeOffWidget
    private lateinit var mContext: Context

    private lateinit var permissionsManager: PermissionsManager

    private var mbMap: MapboxMap? = null
    private lateinit var mapFragment: SupportMapFragment

    private val symbols: MutableList<Symbol> = ArrayList<Symbol>()
    private val flightPathSymbols: MutableList<Symbol> = ArrayList<Symbol>()
    private lateinit var symbol: Symbol
    private var droneSymbol: Symbol? = null
    private lateinit var symbolManager: SymbolManager

    private val symbolDragListener = object : OnSymbolDragListener {
        override fun onAnnotationDrag(symbol: Symbol) {}

        override fun onAnnotationDragFinished(symbol: Symbol) {

            symbols.add(symbol)
            val point = symbol.latLng

            var minDistanceIndex = -1
            var nextIndex: Int

            confirmFlightButton.visibility = View.GONE

            if (polygonCoordinates.size < 3) {
                polygonCoordinates.add(point)
                updatePoly()
            } else {
                var minDistance = Double.MAX_VALUE

                for (i in polygonCoordinates.indices) {

                    nextIndex = if (i == polygonCoordinates.size - 1) {
                        0
                    } else {
                        i + 1
                    }

                    val distance: Double =
                        distanceToSegment(
                            polygonCoordinates[i],
                            polygonCoordinates[nextIndex],
                            point
                        )

                    if (distance < minDistance) {
                        minDistance = distance
                        minDistanceIndex = i + 1
                    }
                }

                polygonCoordinates.add(minDistanceIndex, point)
                updatePoly()

                try {
                    drawFlightPlan()

                } catch (ignored: Exception) {
                }
            }

            layoutConfirmPlan.visibility = View.VISIBLE
        }

        override fun onAnnotationDragStarted(symbol: Symbol) {

            symbols.remove(symbol)
            polygonCoordinates.remove(symbol.latLng)
            flightPlan2D.clear()

        }
    }

    private var polygonCoordinates: MutableList<LatLng> = ArrayList()

    private lateinit var fillManager: FillManager
    private var fill: Fill? = null

    private lateinit var lineManager: LineManager
    private var line: Line? = null

    private var flightPlanLine: Line? = null
    private var flightPlan: List<LatLng> = ArrayList()
    private val flightPlan2D: MutableList<LocationCoordinate2D> = ArrayList()

    private var manager: WaypointMissionManager? = null
    private var droneLocationLat: Double = 0.0
    private var droneLocationLng: Double = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_flight_plan_mapbox)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mContext = applicationContext
        Mapbox.getInstance(mContext, getString(R.string.mapbox_api_key))

        confirmFlightButton = findViewById(R.id.start_flight_button)
        takeoffWidget = findViewById(R.id.takeoff_widget_flight_plan)


        mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.onCreate(savedInstanceState)
        mapFragment.getMapAsync(this)

        //TODO implement the DJI Image Downloader Class

//        downloader = DJIImageDownloader(
//            this,
//            (application as MApplication).getSdkManager(), null
//        )

        val builder = AlertDialog.Builder(this)

        val dialog: AlertDialog = builder
            .setMessage(R.string.ensure_clear_sd)
            .setTitle(R.string.title_clear_sd)
            .setNegativeButton(R.string.no, null)
            .setPositiveButton(R.string.yes) { _, _ -> /*downloader.cleanDrone()*/ } // TODO: yes will crash app since downloader not initialized yet
            .create()

        dialog.show()


        initStitchBtn()
        initConfirmLayout()
        initCloseOverlayButton()

    }

    override fun onMapReady(mapboxMap: MapboxMap) {

        this.mbMap = mapboxMap

        mapboxMap.setStyle(Style.SATELLITE_STREETS) { style: Style ->
            enableLocationComponent(style)

            var bm: Bitmap? =
                Tools.bitmapFromVectorDrawable(mContext, R.drawable.ic_waypoint_marker_unvisited)
            bm?.let { mapboxMap.style?.addImage("ic_waypoint_marker_unvisited", it) }

            bm = Tools.bitmapFromVectorDrawable(mContext, R.drawable.ic_drone)
            bm?.let { mapboxMap.style?.addImage("ic_drone", it) }

            fillManager = FillManager(mapFragment.view as MapView, mapboxMap, style)
            lineManager = LineManager(mapFragment.view as MapView, mapboxMap, style)

            symbolManager = SymbolManager(mapFragment.view as MapView, mapboxMap, style)
            symbolManager.iconAllowOverlap = true
            symbolManager.addDragListener(symbolDragListener)

        }

        val position = CameraPosition.Builder()
            .zoom(18.0)
            .build()

        mapboxMap.animateCamera(CameraUpdateFactory.newCameraPosition(position))

        mbMap?.addOnMapClickListener(this)
    }

    override fun onMapClick(point: LatLng): Boolean {

        symbol = symbolManager.create(
            SymbolOptions()
                .withLatLng(LatLng(point))
                .withIconImage("ic_waypoint_marker_unvisited")
                .withIconHaloWidth(2.0f)
                .withDraggable(true)
        )
        symbols.add(symbol)


//      Add new point to the list

        var minDistanceIndex = -1
        var nextIndex: Int

        confirmFlightButton.visibility = View.GONE

        if (polygonCoordinates.size < 3) {
            polygonCoordinates.add(point)
            updatePoly()
        } else {
            var minDistance = Double.MAX_VALUE

            for (i in polygonCoordinates.indices) {

                nextIndex = if (i == polygonCoordinates.size - 1) {
                    0
                } else {
                    i + 1
                }

                val distance: Double =
                    distanceToSegment(polygonCoordinates[i], polygonCoordinates[nextIndex], point)

                if (distance < minDistance) {
                    minDistance = distance
                    minDistanceIndex = i + 1
                }
            }

            polygonCoordinates.add(minDistanceIndex, point)
            updatePoly()

            try {
                drawFlightPlan()
            } catch (ignored: Exception) {
            }
        }

        layoutConfirmPlan.visibility = View.VISIBLE

        return true
    }

    private fun drawFlightPlan() {

        symbolManager.removeDragListener(symbolDragListener)


        if (polygonCoordinates.size < 3) return

        confirmFlightButton.visibility = View.VISIBLE

        var minLat = Int.MAX_VALUE.toDouble()
        var minLon = Int.MAX_VALUE.toDouble()
        var maxLat = Int.MIN_VALUE.toDouble()
        var maxLon = Int.MIN_VALUE.toDouble()

        for (c in polygonCoordinates) {
            if (c.latitude < minLat) minLat = c.latitude
            if (c.latitude > maxLat) maxLat = c.latitude
            if (c.longitude < minLon) minLon = c.longitude
            if (c.longitude > maxLon) maxLon = c.longitude
        }

        val newPoints: MutableList<LatLng> = ArrayList()

        newPoints.add(LatLng(minLat, minLon))
        newPoints.add(LatLng(minLat, maxLon))
        newPoints.add(LatLng(maxLat, maxLon))
        newPoints.add(LatLng(maxLat, minLon))

        try {

            flightPlan = FlightPlanner.createFlightPlan(newPoints, 95.0f, polygonCoordinates)

            flightPlanLine?.let { line ->
                lineManager.delete(line)
                symbolManager.delete(flightPathSymbols)
            }

            for (coordinate in flightPlan) {
                flightPlan2D.add(LocationCoordinate2D(coordinate.latitude, coordinate.longitude))

                val flightPathSymbol: Symbol = symbolManager.create(
                    SymbolOptions()
                        .withLatLng(LatLng(coordinate))
                        .withIconImage("ic_waypoint_marker_unvisited")
                        .withIconSize(0.5f)
                )

                flightPathSymbols.add(flightPathSymbol)
            }


            val lineOptions: LineOptions = LineOptions()
                .withLatLngs(flightPlan)
                .withLineWidth(2.0f)
                .withLineColor(PropertyFactory.fillColor(Color.parseColor("#FFFFFF")).value)

            flightPlanLine = lineManager.create(lineOptions)

        } catch (e: FlightPlanner.NotEnoughPointsException) {

            showToast(
                this@FlightPlanActivity,
                "Could not create flight plan! Try a larger area."
            )

            clearMapViews()
            showOriginalControls()
            e.printStackTrace()
        }

        symbolManager.addDragListener(symbolDragListener)
    }

    private fun enableLocationComponent(loadedMapStyle: Style) {
        // Check if permissions are enabled and if not request
        if (PermissionsManager.areLocationPermissionsGranted(mContext)) {

            // Get an instance of the component
            val locationComponent: LocationComponent? = mbMap?.locationComponent

            // Activate with options
            locationComponent?.activateLocationComponent(
                LocationComponentActivationOptions.builder(mContext, loadedMapStyle).build()
            )

            // Set the component's camera mode
            locationComponent?.cameraMode = CameraMode.TRACKING

            // Set the component's render mode
            locationComponent?.renderMode = RenderMode.COMPASS

            // Enable to make component visible
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                permissionsManager = PermissionsManager(mContext as PermissionsListener?)
                permissionsManager.requestLocationPermissions(mContext as Activity?)
                return

            }

            locationComponent?.isLocationComponentEnabled = true

        } else {
            permissionsManager = PermissionsManager(mContext as PermissionsListener?)
            permissionsManager.requestLocationPermissions(mContext as Activity?)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        permissionsManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onExplanationNeeded(permissionsToExplain: List<String?>?) {
        Toast.makeText(this, "Need your location", Toast.LENGTH_LONG).show()
    }

    override fun onPermissionResult(granted: Boolean) {
        if (granted) {
            mbMap?.getStyle { loadedMapStyle: Style -> enableLocationComponent(loadedMapStyle) }
        } else {
            Toast.makeText(this, "Location permission not granted", Toast.LENGTH_LONG)
                .show()
            finish()
        }
    }

    // Redraw polygons fill after having changed the container values.
    private fun updatePoly() {

        val polygonCoordinatesCopy: MutableList<LatLng> = ArrayList(polygonCoordinates)
        polygonCoordinatesCopy.add(polygonCoordinates[0])

        line?.let { lineManager.delete(it) }

        val lineOpts: LineOptions = LineOptions()
            .withLineColor(PropertyFactory.lineColor("#FFFFFF").value)
            .withLatLngs(polygonCoordinatesCopy)

        line = lineManager.create(lineOpts)

        val fillCoordinates: MutableList<List<LatLng>> = ArrayList()
        fillCoordinates.add(polygonCoordinatesCopy)

        fill?.let { fillManager.delete(it) }

        val fillOpts: FillOptions = FillOptions()
            .withFillColor(PropertyFactory.fillColor(Color.parseColor("#81FFFFFF")).value)
            .withLatLngs(fillCoordinates)

        fill = fillManager.create(fillOpts)
    }

    private fun initConfirmLayout() {

        layoutConfirmPlan = findViewById(R.id.ll_confirm_flight_plan)
        layoutCancelPlan = findViewById(R.id.ll_cancel_flight_plan)
        val btnConfirmFlight: Button = findViewById(R.id.start_flight_button)
        val btnCancelFlightPlan: Button = findViewById(R.id.cancel_flight_plan_button)
        btnCancelFlight = findViewById(R.id.cancel_flight_button)

        aircraft =
            DJIDemoApplication.getProductInstance() as Aircraft //TODO change this to implement SDKManager or something better

        btnConfirmFlight.setOnClickListener {
            when (aircraft) {
                null -> {
                    Toast.makeText(mContext, "Please connect to a drone", Toast.LENGTH_SHORT).show()
                }
                else -> {

                    val djiMission: WaypointMission =
                        FlightPlanner.createFlightMissionFromCoordinates(flightPlan2D)

                    val operator = MavicMiniMissionOperator(this)

                    operator.setLocationListener { location ->
                        droneLocationLat = location.latitude
                        droneLocationLng = location.longitude
                        updateDroneLocation()
                    }

                    manager = WaypointMissionManager(
                        djiMission,
                        operator,
                        findViewById(R.id.label_flight_plan),
                        this@FlightPlanActivity
                    )

                    manager?.startMission()
                    layoutConfirmPlan.visibility = View.GONE
                    layoutCancelPlan.visibility = View.VISIBLE
                }
            }
        }

        btnCancelFlightPlan.setOnClickListener {
            layoutConfirmPlan.visibility = View.GONE
            clearMapViews()
        }

        btnCancelFlight.setOnClickListener {

            manager?.stopFlight() ?: return@setOnClickListener
            clearMapViews()
            layoutCancelPlan.visibility = View.GONE

        }
    }

    private fun updateDroneLocation() {
        runOnUiThread {

            if (droneLocationLat.isNaN() || droneLocationLng.isNaN()) {
                return@runOnUiThread
            }

            if (MainActivity.checkGpsCoordination(droneLocationLat, droneLocationLng)) {

                val pos = LatLng(droneLocationLat, droneLocationLng)

                droneSymbol?.let {
                    symbolManager.delete(it)
                }

                droneSymbol = symbolManager.create(
                    SymbolOptions()
                        .withLatLng(pos)
                        .withIconImage("ic_drone")
                        .withDraggable(false)
                )
            }

        }
    }

    fun animateNextButton() {
        runOnUiThread {
            stitchBtn.animate()
                .alpha(1.0f)
                .translationX(0f)
                .setListener(null)
                .start()
            takeoffWidget.visibility = View.VISIBLE
            btnCancelFlight.visibility = View.INVISIBLE
            clearMapViews()
        }
    }

    private fun showOriginalControls() {
        layoutCancelPlan.visibility = View.GONE
        layoutConfirmPlan.visibility = View.GONE
    }

    private fun clearMapViews() {
        if (symbols.size > 0) {
            symbolManager.delete(symbols)
        }

        if (flightPathSymbols.size > 0) {
            symbolManager.delete(flightPathSymbols)
        }

        if (flightPlanLine != null) {
            lineManager.delete(flightPlanLine)
        }

        fillManager.delete(fill)
        lineManager.delete(line)

        symbols.clear()
        polygonCoordinates.clear()
        flightPlan2D.clear()
    }

    private val isNetworkConnected: Boolean
        get() {
            val cm: ConnectivityManager =
                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            return cm.activeNetworkInfo != null && cm.activeNetworkInfo!!.isConnected
        }

    //TODO IMPLEMENT ImageProgressActivity to implement this function

    private fun initStitchBtn() {
        stitchBtn = findViewById(R.id.btn_stitch)

//        stitchBtn.setOnClickListener { view: View ->
//            if (isNetworkConnected) {
//                val intent = Intent(view.context, ImageProgressActivity::class.java)
//                view.context.startActivity(intent)
//            } else {
//                try {
//                    AlertDialog.Builder(this@FlightPlanActivity)
//                        .setMessage("No internet connection, please try again once connected to internet")
//                        .setPositiveButton("OK", null)
//                        .show()
//                } catch (e: Exception) {
//                    Toast.makeText(this@FlightPlanActivity, e.message, Toast.LENGTH_LONG).show()
//                }
//            }
//        }
//        stitchBtn.translationX = 999f
    }

    private fun initCloseOverlayButton() {
        overlayView = findViewById(R.id.map_help_overlay)
        val btnCloseOverlay: Button = findViewById(R.id.confirm_overlay_btn)

        btnCloseOverlay.setOnClickListener {
            overlayView.animate()
                .alpha(0.0f)
                .translationXBy(1000f)
                .setDuration(500)
                .setListener(null)
                .start()
        }

    }
}