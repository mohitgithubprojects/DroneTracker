package com.vizzbee.dronetracker

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import com.vizzbee.dronetracker.R
import com.vizzbee.dronetracker.databinding.ActivityCameraBinding
import com.vizzbee.dronetracker.helper.LocationHelper
import com.vizzbee.dronetracker.model.ARPoint
import java.io.File
import java.util.ArrayList
import java.util.concurrent.ExecutorService

class CameraActivity : AppCompatActivity(), SensorEventListener, LocationListener {
    private var imageCapture: ImageCapture? = null

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var binding: ActivityCameraBinding
    private var arOverlayView: AROverlayView? = null

    private var height: Int = 0
    private var width: Int = 0
    private var projectionMatrix = FloatArray(16)
    private val Z_NEAR = 0.5f
    private val Z_FAR = 10000f
    private var sensorManager: SensorManager? = null

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = this.getSystemService(SENSOR_SERVICE) as SensorManager

        height = binding.viewFinder.height
        width = binding.viewFinder.width

        generateProjectionMatrix()

        arOverlayView = AROverlayView(this)
        startCamera()

        MainActivity.location?.let { updateLatestLocation(it) }

        outputDirectory = getOutputDirectory()

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun takePhoto() {}

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        registerSensors()
        initAROverlayView()
    }

    private fun registerSensors() {
        sensorManager!!.registerListener(
            this,
            sensorManager!!.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
            SensorManager.SENSOR_DELAY_NORMAL
        )
    }

    override fun onSensorChanged(sensorEvent: SensorEvent?) {
        if (sensorEvent!!.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrixFromVector = FloatArray(16)
            val rotationMatrix = FloatArray(16)
            SensorManager.getRotationMatrixFromVector(rotationMatrixFromVector, sensorEvent.values)
            val screenRotation = this.windowManager.defaultDisplay
                .rotation
            when (screenRotation) {
                Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                    rotationMatrixFromVector,
                    SensorManager.AXIS_Y,
                    SensorManager.AXIS_MINUS_X, rotationMatrix
                )
                Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                    rotationMatrixFromVector,
                    SensorManager.AXIS_MINUS_Y,
                    SensorManager.AXIS_X, rotationMatrix
                )
                Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                    rotationMatrixFromVector,
                    SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y,
                    rotationMatrix
                )
                else -> SensorManager.remapCoordinateSystem(
                    rotationMatrixFromVector,
                    SensorManager.AXIS_X, SensorManager.AXIS_Y,
                    rotationMatrix
                )
            }
            val projectionMatrix: FloatArray = getProjectionMatrix()
            val rotatedProjectionMatrix = FloatArray(16)
            Matrix.multiplyMM(rotatedProjectionMatrix, 0, projectionMatrix, 0, rotationMatrix, 0)
            this.arOverlayView!!.updateRotatedProjectionMatrix(rotatedProjectionMatrix)
        }
    }

    fun initAROverlayView() {
        if (arOverlayView!!.parent != null) {
            (arOverlayView!!.parent as ViewGroup).removeView(arOverlayView)
        }
        binding.cameraContainerLayout.addView(arOverlayView)
    }

    private fun updateLatestLocation(location: Location) {
        if (arOverlayView != null && location != null) {
            arOverlayView!!.updateCurrentLocation(location)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            Log.w("DeviceOrientation", "Orientation compass unreliable")
        }
    }

    override fun onLocationChanged(location: Location) {
        updateLatestLocation(location)
    }

    private fun generateProjectionMatrix() {
        var ratio = 0f
        if (width < height) {
            ratio = width.toFloat() / width
        } else {
            ratio = height.toFloat() / width
        }
        val OFFSET = 0
        val LEFT = -ratio
        val RIGHT = ratio
        val BOTTOM = -1f
        val TOP = 1f
        Matrix.frustumM(
            projectionMatrix,
            OFFSET,
            LEFT,
            RIGHT,
            BOTTOM,
            TOP,
            Z_NEAR,
            Z_FAR
        )
    }

    fun getProjectionMatrix(): FloatArray {
        return projectionMatrix
    }
}