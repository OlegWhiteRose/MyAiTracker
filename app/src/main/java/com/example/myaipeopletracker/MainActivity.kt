package com.example.myaipeopletracker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var viewFinder: PreviewView
    private lateinit var overlayView: FaceOverlayView
    private lateinit var faceRecognizer: FaceRecognizer

    private var lensFacing = CameraSelector.LENS_FACING_FRONT
    private val peopleDatabase = mutableMapOf<String, FloatArray>()
    private val unknownFaceTracker = mutableListOf<TrackedUnknownFace>()
    private var nextPersonId = 1

    // JSON БД: ключ — id (например "1", "2"), значение — вектор лица из 192 чисел.
    private val jsonDbString = "{}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        overlayView = findViewById(R.id.overlayView)
        val btnSwitchCamera = findViewById<Button>(R.id.btnSwitchCamera)

        faceRecognizer = FaceRecognizer(this)
        loadDatabase()

        btnSwitchCamera.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            startCamera()
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun loadDatabase() {
        try {
            val jsonObject = JSONObject(jsonDbString)
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val jsonArray = jsonObject.getJSONArray(id)
                val floatArray = FloatArray(jsonArray.length())
                for (i in 0 until jsonArray.length()) {
                    floatArray[i] = jsonArray.getDouble(i).toFloat()
                }
                peopleDatabase[id] = floatArray
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки БД JSON", e)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, FaceAnalyzer { faces, width, height ->
                        val isFrontMode = lensFacing == CameraSelector.LENS_FACING_FRONT
                        overlayView.setFaces(faces, width, height, isFrontMode)
                    })
                }

            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "AI_Tracker"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private inner class FaceAnalyzer(private val listener: (List<RecognizedFace>, Int, Int) -> Unit) : ImageAnalysis.Analyzer {

        private val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()

        private val detector = FaceDetection.getClient(options)

        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                detector.process(inputImage)
                    .addOnSuccessListener { faces ->
                        val recognizedFaces = mutableListOf<RecognizedFace>()

                        val bitmap = imageProxy.toBitmap()
                        val rotatedBitmap = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees.toFloat())

                        val currentFrameUnknowns = mutableListOf<TrackedUnknownFace>()

                        for (face in faces) {
                            val bounds = face.boundingBox

                            val safeRect = Rect(
                                max(0, bounds.left), max(0, bounds.top),
                                min(rotatedBitmap.width, bounds.right), min(rotatedBitmap.height, bounds.bottom)
                            )

                            if (safeRect.width() > 0 && safeRect.height() > 0) {
                                val croppedFace = Bitmap.createBitmap(
                                    rotatedBitmap, safeRect.left, safeRect.top, safeRect.width(), safeRect.height()
                                )

                                val embedding = faceRecognizer.getFaceEmbedding(croppedFace)

                                Log.d("FACE_VECTOR", embedding.joinToString(prefix = "[", postfix = "]"))

                                val personId = identifyPerson(embedding, currentFrameUnknowns)
                                recognizedFaces.add(RecognizedFace(bounds, personId))
                            }
                        }
                        listener(recognizedFaces, imageProxy.width, imageProxy.height)
                    }
                    .addOnCompleteListener { imageProxy.close() }
            } else {
                imageProxy.close()
            }
        }

        private fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
            if (angle == 0f) return source
            val matrix = Matrix()
            matrix.postRotate(angle)
            return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }

        private fun identifyPerson(
            embedding: FloatArray,
            currentFrameUnknowns: MutableList<TrackedUnknownFace>
        ): String {
            val dbMatch = findClosestDatabaseMatch(embedding)
            if (dbMatch != null) return dbMatch
            return assignPersonId(embedding, currentFrameUnknowns)
        }

        private fun findClosestDatabaseMatch(embedding: FloatArray): String? {
            var minDistance = Float.MAX_VALUE
            var bestMatch: String? = null
            val threshold = 1.1f

            for ((id, dbEmbedding) in peopleDatabase) {
                val distance = euclideanDistance(embedding, dbEmbedding)
                if (distance < minDistance) {
                    minDistance = distance
                    bestMatch = id
                }
            }

            return if (minDistance < threshold) bestMatch else null
        }

        private fun assignPersonId(
            embedding: FloatArray,
            currentFrameUnknowns: MutableList<TrackedUnknownFace>
        ): String {
            val threshold = 1.0f
            val candidates = currentFrameUnknowns + unknownFaceTracker

            var bestTracked: TrackedUnknownFace? = null
            var minDistance = Float.MAX_VALUE

            for (tracked in candidates) {
                val distance = euclideanDistance(embedding, tracked.embedding)
                if (distance < minDistance) {
                    minDistance = distance
                    bestTracked = tracked
                }
            }

            if (bestTracked != null && minDistance < threshold) {
                bestTracked.embedding = embedding
                return bestTracked.id
            }

            val newId = nextPersonId.toString()
            nextPersonId++
            val tracked = TrackedUnknownFace(newId, embedding.copyOf())
            unknownFaceTracker.add(tracked)
            currentFrameUnknowns.add(tracked)
            return newId
        }

        private fun euclideanDistance(a: FloatArray, b: FloatArray): Float {
            var distance = 0f
            val size = min(a.size, b.size)
            for (i in 0 until size) {
                val diff = a[i] - b[i]
                distance += diff * diff
            }
            return sqrt(distance.toDouble()).toFloat()
        }
    }

    private data class TrackedUnknownFace(val id: String, var embedding: FloatArray)
}
