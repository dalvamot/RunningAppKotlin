package com.vt.runningappkotlin

import android.Manifest
import android.content.IntentSender.SendIntentException
import android.content.pm.PackageManager
import android.content.res.Resources.NotFoundException
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapFragment
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.*
import java.util.*

/***Location variables and constants */
    //location settings request code
    const val REQUEST_CHECK_SETTINGS = 2

    //constant that represent permission request code
    const val MY_PERMISSION_REQUEST_ACCESS_FINE_LOCATION = 1

    private var locationRequest: LocationRequest? = null
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    private var settingsClient: SettingsClient? = null
    private var settingsRequestBuilder: LocationSettingsRequest.Builder? = null

    private var lastKnownLocation: Location? = null

    /***Permissions and settings */
    var hasPermission = false
    var locationEnabled = false
    var permissionRequest = false
    var settingsChangeRequested = false

    /***Google maps variables and constants */
    var mapFragment: MapFragment? = null
    var googleMap: GoogleMap? = null

    var marker: Marker? = null
    var markerBitmap: Bitmap? = null

    val LINE_OPTIONS = PolylineOptions().color(Color.parseColor("#E94335")).width(13f)
    var routes = ArrayList<Polyline>()
    var routesPoints: ArrayList<LatLng>? = null

    var isRunning = false

    /***UI elements */
    lateinit var startPauseButton: Button

    lateinit var distanceTextView: TextView
    lateinit var durationTextView: TextView
    lateinit var serviceInfoTextView: TextView

    lateinit var targetButton: MenuItem
    lateinit var clearButton: MenuItem

    /***Distance and duration */
    private var timer: Timer? = null
    private  var duration = 0
    private  var distance = 0

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        distanceTextView = findViewById(R.id.distanceTextView)
        durationTextView = findViewById(R.id.durationTextView)
        serviceInfoTextView = findViewById(R.id.serviceInfoTextView)

        startPauseButton = findViewById(R.id.startPauseButton)

        //add home icon in action bar

        //add home icon in action bar
        supportActionBar!!.setDisplayShowHomeEnabled(true)
        supportActionBar!!.setIcon(R.mipmap.ic_launcher)

        //add title of action bar

        //add title of action bar
        supportActionBar!!.title = "Running Tracker"

        //get map fragment reference

        //get map fragment reference
        mapFragment = fragmentManager.findFragmentById(R.id.map) as MapFragment

        //initialize Google maps

        //initialize Google maps
        mapFragment!!.getMapAsync(this)

        //prepare marker Bitmap

        //prepare marker Bitmap
        val bitmapdraw = resources.getDrawable(R.drawable.location_marker) as BitmapDrawable
        val b = bitmapdraw.bitmap

        val width = 120
        val height = 120
        markerBitmap = Bitmap.createScaledBitmap(b, width, height, false)

        //create the locationRequest object

        //create the locationRequest object
        locationRequest = LocationRequest.create()
            .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
            .setSmallestDisplacement(10f) //10 meters
            .setInterval((5 * 1000).toLong()) //5 seconds
            .setFastestInterval((1 * 1000).toLong()) //1 second

        //create FusedLocationClient object
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        //create SettingsClient object
        settingsClient = LocationServices.getSettingsClient(this)
        settingsRequestBuilder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest!!)

        //callback for getting location updates

        //callback for getting location updates
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    if (!cameraCalibrated) {
                        initialMapCameraCalibration(location)
                    } else {
                        if (location.accuracy > 30) return
                        drawMarker(location)
                        if (isRunning) {
                            drawRoute(location)
                            updateDistance(location)
                        }
                    }
                    lastKnownLocation = location
                }
            }

            override fun onLocationAvailability(locationAvailability: LocationAvailability) {
                super.onLocationAvailability(locationAvailability)
                if (locationAvailability.isLocationAvailable) {
                    setUiEnabled(true, null)
                } else {
                    setUiEnabled(
                        false,
                        "This app needs Location data. Please enable location in Settings."
                    )
                }
            }
        }

        if (savedInstanceState != null) {
            isRunning = savedInstanceState.getBoolean("IS_RUNNING")
            cameraCalibrated = savedInstanceState.getBoolean("CAMERA_CALIBRATED")
            duration = savedInstanceState.getInt("DURATION")
            distance = savedInstanceState.getInt("DISTANCE")
            lastKnownLocation = savedInstanceState.getParcelable("LAST_KNOW_LOCATION")
            lastRoutePoints = savedInstanceState.getParcelableArrayList<LatLng>("LAST_ROUTE_POINTS")
            durationTextView.text = getDurationFromSeconds(duration).toString()
            distanceTextView.text = distance.toString()
            routesPoints = savedInstanceState.getParcelableArrayList("ROUTES_POINTS")
        }

    }

    /*** Google maps logic ***/

    override fun onMapReady(p0: GoogleMap) {
        googleMap = googleMap

        try {
            val success = googleMap!!.setMapStyle(
                MapStyleOptions.loadRawResourceStyle(
                    this,
                    R.raw.gmap_style
                )
            )
            if (!success) {
                Log.e("gmap", "Style parsing failed.")
            }
        } catch (e: NotFoundException) {
            Log.e("gmap", "Can't find style. Error: ", e)
        }

        //if activity is recreated, draw all polygones

        //if activity is recreated, draw all polygones
        if (routesPoints != null) {
            var linePoints: MutableList<LatLng?> = ArrayList()
            for (i in routesPoints!!.indices) {
                if (routesPoints!![i] != null) {
                    linePoints.add(routesPoints!![i])
                } else {
                    val polyline = googleMap!!.addPolyline(LINE_OPTIONS)
                    polyline.points = linePoints
                    routes.add(polyline)
                    linePoints = ArrayList()
                }
            }
        }

        if (isRunning) {
            startTimer()
        }
    }

    fun checkGooglePlayServices(): Boolean {
        val result = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)
        return result == ConnectionResult.SUCCESS
    }

    /***Location logic */
    @Throws(SecurityException::class)
    private fun getLastKnowLocation() {
        fusedLocationClient!!.lastLocation.addOnSuccessListener(
            this
        ) { location ->
            //handle location object
            if (location != null) {
                lastKnownLocation = location
                initialMapCameraCalibration(location)
                drawMarker(location)
            } else {
                //there is no last know location; wait
            }
        }
    }

    private fun startLocationUpdate() {
        if (!checkGooglePlayServices()) {
            setUiEnabled(false, "Please, update Google Play Services.")
            return
        }

        //check if permission is already granted
        if (ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            hasPermission = true
            setUiEnabled(true, null)
            settingsClient!!.checkLocationSettings(settingsRequestBuilder!!.build())
                .addOnSuccessListener(
                    this
                ) {
                    locationEnabled = true
                    setUiEnabled(true, null)
                    getLastKnowLocation()
                }.addOnFailureListener(this) { e ->
                    if (e is ResolvableApiException) {
                        locationEnabled = false
                        setUiEnabled(false, "Please, enable location.")

                        //location settings are not satisfied, but this can be fixed by showing the user a dialog
                        try {
                            if (!settingsChangeRequested) {

                                //show the dialog by calling startResolutionForResult(), and check the result in onActivityResult()
                                e.startResolutionForResult(
                                    this@MainActivity,
                                    REQUEST_CHECK_SETTINGS
                                )
                                settingsChangeRequested = true
                            } else {
                                serviceInfoTextView!!.text =
                                    "This app needs Location data. Please enable location in Settings."
                            }
                        } catch (sendIntentException: SendIntentException) {
                            //ignore the error.
                        }
                    }
                }
            fusedLocationClient!!.requestLocationUpdates(
                locationRequest!!,
                locationCallback!!,
                null
            )
            serviceInfoTextView!!.text = ""
        } else {
            hasPermission = false
            setUiEnabled(false, "App doesn't have permission for using location data.")
            if (!permissionRequest) {

                //if permission is not granted, ask user for permission
                ActivityCompat.requestPermissions(
                    this@MainActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    MY_PERMISSION_REQUEST_ACCESS_FINE_LOCATION
                )
            }
            permissionRequest = true
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient!!.removeLocationUpdates(locationCallback!!)
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }

    override fun onResume() {
        super.onResume()
        startLocationUpdate()
    }


    /***Data presentation logic start */
    var uiEnabled = false

    private fun setUiEnabled(enabled: Boolean, message: String?) {
        uiEnabled = enabled
        startPauseButton!!.isEnabled = enabled
        if (clearButton != null) {
            clearButton!!.isEnabled = enabled
        }
        if (targetButton != null) {
            targetButton!!.isEnabled = enabled
        }
        if (message != null) {
            serviceInfoTextView!!.visibility = View.VISIBLE
            serviceInfoTextView!!.text = message
        } else {
            serviceInfoTextView!!.visibility = View.GONE
            serviceInfoTextView!!.text = ""
        }
    }

    private var cameraCalibrated = false

    private fun initialMapCameraCalibration(location: Location) {
        val demoLocation = LatLng(location.latitude, location.longitude)
        val cameraPosition = CameraPosition.Builder()
            .target(demoLocation) //center of the map
            .zoom(17f)
            .build()
        googleMap!!.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), 1000, null)
        cameraCalibrated = true
    }

    private fun reposition() {
        if (lastKnownLocation == null) return
        val location = LatLng(lastKnownLocation!!.latitude, lastKnownLocation!!.longitude)
        googleMap!!.animateCamera(CameraUpdateFactory.newLatLngZoom(location, 17f))
    }

    private fun drawMarker(location: Location) {
        if (marker == null) {
            marker = googleMap!!.addMarker(
                MarkerOptions()
                    .position(LatLng(location.latitude, location.longitude))
                    .title("You are here")
                    .alpha(0.8f)
                    .icon(BitmapDescriptorFactory.fromBitmap(markerBitmap!!))
            )
        } else {
            marker!!.position = LatLng(location.latitude, location.longitude)
        }
    }

    var lastRoutePoints: ArrayList<LatLng>? = null

    private fun drawRoute(location: Location) {
        val newLocation = LatLng(location.latitude, location.longitude)
        if (lastRoutePoints == null) {
            lastRoutePoints = ArrayList()
            lastRoutePoints!!.add(newLocation)
            routes.add(googleMap!!.addPolyline(LINE_OPTIONS))
            routes[routes.size - 1].points = lastRoutePoints!!
        } else {
            lastRoutePoints!!.add(newLocation)
            routes[routes.size - 1].points = lastRoutePoints!!
        }
    }

    private fun clearAllRoutes() {
        for (i in routes.indices) {
            routes[i].remove()
        }
        routes = ArrayList()
        lastRoutePoints = null
        if (isRunning) {
            drawRoute(lastKnownLocation!!)
        }
    }

    private fun updateDistance(location: Location) {
        val results = FloatArray(1)
        Location.distanceBetween(
            lastKnownLocation!!.latitude, lastKnownLocation!!.longitude,
            location.latitude, location.longitude,
            results
        )
        distance += results[0].toInt()
        distanceTextView!!.text = distance.toString()
    }

    fun clearDistance() {
        distance = 0
        distanceTextView!!.text = distance.toString()
    }

    private fun startTimer() {
        timer = Timer()
        timer!!.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread {
                    duration++
                    durationTextView!!.text = getDurationFromSeconds(duration)
                }
            }
        }, 0, 1000)
    }

    private fun getDurationFromSeconds(seconds: Int): String? {
        val min = seconds / 60
        val sec = seconds - min * 60
        val minutesString = min.toString()
        var secondsStrings = sec.toString()
        secondsStrings = if (secondsStrings.length == 1) "0$secondsStrings" else secondsStrings
        return "$minutesString:$secondsStrings"
    }

    private fun pauseTimer() {
        timer?.cancel()
    }

    private fun resetTimer() {
        duration = 0
        durationTextView!!.text = "0:00"
    }

    /***Action bar menu logic start */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.action_bar_menu, menu)
        targetButton = menu.findItem(R.id.targetButton)
        clearButton = menu.findItem(R.id.clearButton)
        targetButton.setEnabled(uiEnabled)
        clearButton.setEnabled(uiEnabled)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.clearButton -> {

                //user taps on CLEAR button
                clearAllRoutes()
                clearDistance()
                resetTimer()
                true
            }
            R.id.targetButton -> {

                //user taps REPOSITION button
                reposition()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /***Event handler and callback methods start  */
    fun startPauseButtonClick(view: View?) {
        isRunning = !isRunning
        if (!isRunning) {
            lastRoutePoints = null
            pauseTimer()
        } else {
            drawRoute(lastKnownLocation!!)
            startTimer()
        }
    }

    /***State handling logic */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("IS_RUNNING", isRunning)
        outState.putBoolean("CAMERA_CALIBRATED", cameraCalibrated)
        outState.putInt("DURATION", duration)
        outState.putInt("DISTANCE", distance)
        outState.putParcelable(
            "MARKER",
            if (googleMap != null) googleMap!!.cameraPosition else null
        )
        outState.putParcelableArrayList("LAST_ROUTE_POINTS", lastRoutePoints)
        outState.putParcelable("LAST_KNOW_LOCATION", lastKnownLocation)
        val routesPoints = ArrayList<LatLng?>()
        for (i in routes.indices) {
            routesPoints.addAll(routes[i].points)
            routesPoints.add(null)
        }
        outState.putParcelableArrayList("ROUTES_POINTS", routesPoints)
    }


}