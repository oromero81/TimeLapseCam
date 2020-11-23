package cat.oscarromero.timelapsecam

import android.Manifest
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val cameraProviderFuture by lazy {
        ProcessCameraProvider.getInstance(this)
    }
    private val imagePreviewUseCase: Preview by lazy {
        Preview.Builder().apply {
            setTargetAspectRatio(AspectRatio.RATIO_16_9)
            setTargetRotation(cameraPreviewView.display.rotation)
        }.build()
    }
    private val imageCaptureUseCase by lazy {
        ImageCapture.Builder().apply {
            setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            setTargetResolution(Size(1920 * 2, 1080 * 2))
        }.build()
    }
    private val outputDirectory: File by lazy {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                .toString() + File.separator + "timelapse"
        )
        if (!dir.exists()) {
            dir.mkdir()
        }
        dir
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val handlerPhoto = Handler(Looper.myLooper()!!)
    private val runnablePhoto: Runnable by lazy {
        Runnable {
            takePicture()
            handlerPhoto.postDelayed(runnablePhoto, timeEditText.text.toString().toInt() * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        takePhotoButton.setOnClickListener { takePicture() }
        startTimeLapseButton.setOnClickListener { handlerPhoto.post(runnablePhoto) }
        timeEditText.setText("$DEFAULT_PHOTO_INTERVAL")
    }

    override fun onResume() {
        super.onResume()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
        } else {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }

        if (allPermissionsGranted()) {
            cameraPreviewView.post { startCamera() }
        } else {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onPause() {
        super.onPause()
        handlerPhoto.removeCallbacks(runnablePhoto)
    }

    override fun onDestroy() {
        super.onDestroy()

        executor.shutdown()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                cameraPreviewView.post { startCamera() }
            } else {
                finish()
            }
        }
    }

    private fun startCamera() {
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        val cameraProvider = cameraProviderFuture.get()

        cameraProvider.unbindAll()
        val camera = cameraProvider.bindToLifecycle(
            this,
            cameraSelector,
            imagePreviewUseCase,
            imageCaptureUseCase
        )

        cameraPreviewView.setOnTouchListener { view, event ->
            view.performClick()

            return@setOnTouchListener when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val factory: MeteringPointFactory = SurfaceOrientedMeteringPointFactory(
                        cameraPreviewView.width.toFloat(), cameraPreviewView.height.toFloat()
                    )
                    val autoFocusPoint = factory.createPoint(event.x, event.y)
                    try {
                        camera.cameraControl.startFocusAndMetering(
                            FocusMeteringAction.Builder(autoFocusPoint).apply {
                                //focus only when the user tap the preview
                                disableAutoCancel()
                            }.build()
                        )
                    } catch (e: CameraInfoUnavailableException) {
                        Log.d("ERROR", "cannot access camera", e)
                    }
                    true
                }
                else -> false // Unhandled event.
            }
        }

        cameraProviderFuture.addListener(
            Runnable {
                imagePreviewUseCase.setSurfaceProvider(cameraPreviewView.surfaceProvider)
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun takePicture() {
        val file = File(
            outputDirectory,
            "Timelapse_" + SimpleDateFormat(
                FILENAME_FORMAT,
                Locale.US
            ).format(System.currentTimeMillis()) + ".jpg"
        )
        val outputFileOptions = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCaptureUseCase.takePicture(
            outputFileOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val msg = "Photo capture succeeded: ${file.absolutePath}"
                    cameraPreviewView.post {
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    val msg = "Photo capture failed: ${exception.message}"
                    cameraPreviewView.post {
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
            })
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        private const val FILENAME_FORMAT = "yyyy.MM.dd_HH:mm:ss"
        private const val DEFAULT_PHOTO_INTERVAL = 10
    }
}
