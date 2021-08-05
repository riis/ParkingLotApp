package com.dji.droneparking


import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dji.droneparking.util.*
import com.dji.droneparking.util.Tools.showToast
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.maps.*
import com.mapbox.mapboxsdk.plugins.annotation.*
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.riis.cattlecounter.util.distanceToSegment
import dji.common.mission.waypoint.WaypointMission
import dji.common.model.LocationCoordinate2D
import dji.sdk.products.Aircraft
import dji.ux.widget.TakeOffWidget
import java.util.*

class FlightPlanActivity : AppCompatActivity(), OnMapReadyCallback,
    MapboxMap.OnMapClickListener, View.OnClickListener {


    private var aircraft: Aircraft? = null
    private lateinit var cancelFlightBtn: Button
    private lateinit var overlayView: LinearLayout
    private lateinit var layoutConfirmPlan: LinearLayout
    private lateinit var layoutCancelPlan: LinearLayout
    private lateinit var startFlightBtn: Button
    private lateinit var locateBtn: Button
    private lateinit var takeoffWidget: TakeOffWidget
    private lateinit var mContext: Context

    private var mbMap: MapboxMap? = null
    private lateinit var mapFragment: SupportMapFragment
    private var styleReady = false

    private var polygonCoordinates: MutableList<LatLng> = ArrayList()

    private val symbols: MutableList<Symbol> = ArrayList<Symbol>()
    private val flightPathSymbols: MutableList<Symbol> = ArrayList<Symbol>()
    private var droneSymbol: Symbol? = null
    private lateinit var symbolManager: SymbolManager
    private val symbolDragListener = object : OnSymbolDragListener {
        override fun onAnnotationDrag(symbol: Symbol) {}

        override fun onAnnotationDragFinished(symbol: Symbol) {
            configureFlightPlan(symbol)
        }

        override fun onAnnotationDragStarted(symbol: Symbol) {

            symbols.remove(symbol)
            polygonCoordinates.remove(symbol.latLng)

        }
    }

    private lateinit var fillManager: FillManager
    private var fill: Fill? = null

    private lateinit var lineManager: LineManager
    private var line: Line? = null

    private var flightPlanLine: Line? = null
    private var flightPlan: List<LatLng> = ArrayList()
    private val flightPlan2D: MutableList<LocationCoordinate2D> = ArrayList()

    private var manager: WaypointMissionManager? = null
    private var operator = MavicMiniMissionOperator(this)
    private lateinit var droneLocation: LocationCoordinate2D
    private var located = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_flight_plan_mapbox)

        initUI()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mContext = applicationContext
        Mapbox.getInstance(mContext, getString(R.string.mapbox_api_key))

        mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.onCreate(savedInstanceState)
        mapFragment.getMapAsync(this)

        //TODO implement the DJI Image Downloader Class

        val dialog = AlertDialog.Builder(this)
            .setMessage(R.string.ensure_clear_sd)
            .setTitle(R.string.title_clear_sd)
            .setNegativeButton(R.string.no, null)
            .setPositiveButton(R.string.yes) { _, _ -> /*downloader.cleanDrone()*/ }
            // TODO: yes will crash app since downloader not initialized yet
            .create()

        dialog.show()

        operator.droneLocationLiveData.observe(this, { location ->
            if (styleReady) {
                droneLocation = location
                if (!located) cameraUpdate()
                updateDroneLocation()
                located = true
            }
        })
    }

    override fun onMapReady(mapboxMap: MapboxMap) {

        this.mbMap = mapboxMap

        mapboxMap.setStyle(Style.SATELLITE_STREETS) { style: Style ->

            styleReady = true
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

        val symbol = symbolManager.create(
            SymbolOptions()
                .withLatLng(LatLng(point))
                .withIconImage("ic_waypoint_marker_unvisited")
                .withIconHaloWidth(2.0f)
                .withDraggable(true)
        )


        //      Add new point to the list
        configureFlightPlan(symbol)

        return true
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.locate_button -> {
                updateDroneLocation()
                cameraUpdate()
            }
            R.id.get_started_button -> {
                overlayView.animate()
                    .alpha(0.0f)
                    .translationXBy(1000f)
                    .setDuration(500)
                    .setListener(null)
                    .start()

                locateBtn.visibility = View.VISIBLE
            }
            R.id.start_flight_button -> {
                when (aircraft) {
                    null -> {
                        Toast.makeText(mContext, "Please connect to a drone", Toast.LENGTH_SHORT)
                            .show()
                    }
                    else -> {

                        val djiMission: WaypointMission =
                            FlightPlanner.createFlightMissionFromCoordinates(flightPlan2D)

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
            R.id.cancel_flight_button -> {
                manager?.stopFlight() ?: return
                clearMapViews()
                layoutCancelPlan.visibility = View.GONE
            }
            R.id.cancel_flight_plan_button -> {
                layoutConfirmPlan.visibility = View.GONE
                clearMapViews()
            }
        }
    }

    private fun initUI() {

        startFlightBtn = findViewById(R.id.start_flight_button)
        cancelFlightBtn = findViewById(R.id.cancel_flight_button)
        locateBtn = findViewById(R.id.locate_button)
        val cancelFlightPlanBtn: Button = findViewById(R.id.cancel_flight_plan_button)
        val getStartedBtn: Button = findViewById(R.id.get_started_button)

        takeoffWidget = findViewById(R.id.takeoff_widget_flight_plan)
        layoutConfirmPlan = findViewById(R.id.ll_confirm_flight_plan)
        layoutCancelPlan = findViewById(R.id.ll_cancel_flight_plan)

        overlayView = findViewById(R.id.map_help_overlay)

        aircraft =
            DJIDemoApplication.getProductInstance() as Aircraft //TODO change this to implement SDKManager or something better

        locateBtn.setOnClickListener(this)
        getStartedBtn.setOnClickListener(this)
        startFlightBtn.setOnClickListener(this)
        cancelFlightPlanBtn.setOnClickListener(this)
        cancelFlightBtn.setOnClickListener(this)


    }

    private fun cameraUpdate() {
        if (droneLocation.latitude.isNaN() || droneLocation.longitude.isNaN()) {
            return
        }
        val pos = LatLng(droneLocation.latitude, droneLocation.longitude)
        val zoomLevel = 18.0
        val cameraUpdate = CameraUpdateFactory.newLatLngZoom(pos, zoomLevel)
        runOnUiThread {
            mbMap?.moveCamera(cameraUpdate)
        }
    }

    private fun configureFlightPlan(symbol: Symbol) {

        symbols.add(symbol)

        val point = symbol.latLng
        var minDistanceIndex = -1
        var nextIndex: Int

        startFlightBtn.visibility = View.GONE

        if (polygonCoordinates.size < 3) { // Only Draw Area of Flight Plan
            polygonCoordinates.add(point)
            updatePoly()
        } else { // Draw Area and Configure Flight Plan
            var minDistance = Double.MAX_VALUE

            for (i in polygonCoordinates.indices) {
                // place new coordinate in list such that the distance between it and the line segment
                // created by its neighbouring coordinates are minimized

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
    }

    private fun drawFlightPlan() {

        symbolManager.removeDragListener(symbolDragListener) // prevents infinite loop


        if (polygonCoordinates.size < 3) return

        startFlightBtn.visibility = View.VISIBLE

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

            flightPlan =
                FlightPlanner.createFlightPlan(newPoints, 95.0f, polygonCoordinates) // get plan

            flightPlanLine?.let { line -> // delete on plan on map and reset arrays
                lineManager.delete(line)
                symbolManager.delete(flightPathSymbols)
                flightPlan2D.clear()
                flightPathSymbols.clear()
            }

            for (coordinate in flightPlan) { // populate new plan and place markers on map
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

    private fun updateDroneLocation() {
        runOnUiThread {

            if (droneLocation.latitude.isNaN() || droneLocation.longitude.isNaN()) {
                return@runOnUiThread
            }

            if (MainActivity.checkGpsCoordination(
                    droneLocation.latitude,
                    droneLocation.longitude
                )
            ) {

                val pos = LatLng(droneLocation.latitude, droneLocation.longitude)

                droneSymbol?.let { drone ->
                    symbolManager.delete(drone)
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
            takeoffWidget.visibility = View.VISIBLE
            cancelFlightBtn.visibility = View.INVISIBLE
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

}