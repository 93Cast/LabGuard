
package com.example.labguard.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint as AndroidPaint
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.ExifInterface
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.labguard.OnnxModelManager
import com.example.labguard.data.LabRecord
import com.example.labguard.data.LabRepository
import com.example.labguard.ml.Detection
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerificationScreen(
    employeeName: String,
    employeeCard: String,
    repository: LabRepository,
    modelManager: OnnxModelManager?,
    onFinish: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var isAnalyzing by remember { mutableStateOf(false) }
    var analyzedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var detections by remember {
        mutableStateOf<List<Detection>>(emptyList())
    }

    var liveFrameWidth by remember { mutableIntStateOf(640) }
    var liveFrameHeight by remember { mutableIntStateOf(480) }

    var statusMessage by remember {
        mutableStateOf("Apunta la cámara a los elementos de seguridad.")
    }

    val inferenceBusy = remember { AtomicBoolean(false) }
    val analysisPaused = remember { AtomicBoolean(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            statusMessage = "Debes conceder permiso a la cámara."
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(
                ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            )
            .build()
    }

    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(640, 480))
            .setBackpressureStrategy(
                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            )
            .build()
    }

    val cameraProviderFuture = remember {
        ProcessCameraProvider.getInstance(context)
    }

    val analysisExecutor = remember {
        Executors.newSingleThreadExecutor()
    }

    DisposableEffect(
        hasCameraPermission,
        lifecycleOwner,
        cameraProviderFuture,
        imageAnalysis,
        imageCapture
    ) {
        if (hasCameraPermission) {
            val executor = ContextCompat.getMainExecutor(context)

            val listener = Runnable {
                try {
                    val provider = cameraProviderFuture.get()

                    val targetRotation = previewView.display?.rotation
                        ?: android.view.Surface.ROTATION_0

                    imageCapture.targetRotation = targetRotation
                    imageAnalysis.targetRotation = targetRotation

                    val preview = Preview.Builder()
                        .setTargetResolution(android.util.Size(640, 480))
                        .setTargetRotation(targetRotation)
                        .build()
                        .also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        analyzeLiveFrame(
                            imageProxy = imageProxy,
                            manager = modelManager,
                            inferenceBusy = inferenceBusy,
                            analysisPaused = analysisPaused,
                            onResult = { results, width, height ->
                                liveFrameWidth = width
                                liveFrameHeight = height
                                detections = results
                            }
                        )
                    }

                    provider.unbindAll()

                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                        imageAnalysis
                    )

                    Log.i(
                        "LabGuardCamera",
                        "Cámara y análisis en vivo iniciados"
                    )
                } catch (e: Exception) {
                    Log.e(
                        "LabGuardCamera",
                        "Error al iniciar cámara",
                        e
                    )
                    statusMessage = "No se pudo iniciar la cámara."
                }
            }

            cameraProviderFuture.addListener(listener, executor)

            onDispose {
                imageAnalysis.clearAnalyzer()

                if (cameraProviderFuture.isDone) {
                    try {
                        cameraProviderFuture.get().unbindAll()
                    } catch (e: Exception) {
                        Log.e(
                            "LabGuardCamera",
                            "Error al liberar cámara",
                            e
                        )
                    }
                }
            }
        } else {
            onDispose { }
        }
    }

    DisposableEffect(analysisExecutor) {
        onDispose {
            analysisExecutor.shutdown()
        }
    }

    fun captureAndAnalyze() {
        val manager = modelManager

        if (manager == null) {
            statusMessage = "El modelo ONNX no está disponible."
            return
        }

        if (isAnalyzing) return

        isAnalyzing = true
        analysisPaused.set(true)
        statusMessage = "Capturando imagen..."

        val photoFile = File(
            context.cacheDir,
            "labguard_${System.currentTimeMillis()}.jpg"
        )

        val options = ImageCapture.OutputFileOptions.Builder(
            photoFile
        ).build()

        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {

                override fun onImageSaved(
                    outputFileResults: ImageCapture.OutputFileResults
                ) {
                    scope.launch {
                        var bitmapForCleanup: Bitmap? = null

                        try {
                            val bitmap = withContext(Dispatchers.IO) {
                                decodeBitmapWithExif(photoFile)
                                    ?: error(
                                        "No se pudo leer la fotografía."
                                    )
                            }

                            bitmapForCleanup = bitmap

                            Log.i(
                                "LabGuardCapture",
                                "Foto capturada y corregida: " +
                                        "${bitmap.width}x${bitmap.height}"
                            )

                            statusMessage =
                                "Analizando y guardando registro..."

                            // Diagnóstico temporal del modelo.
                            // Se conserva para investigar las puntuaciones.
                            val results = withContext(Dispatchers.Default) {
                                manager.diagnoseBitmap(bitmap)
                                manager.detectBitmap(bitmap)
                            }

                            Log.i(
                                "LabGuardCapture",
                                "Detecciones en foto capturada: ${results.size}"
                            )

                            results.forEach { detection ->
                                Log.i(
                                    "LabGuardCapture",
                                    "${detection.label}: " +
                                            "${(detection.confidence * 100).toInt()}% " +
                                            "caja=[${detection.left}, " +
                                            "${detection.top}, " +
                                            "${detection.right}, " +
                                            "${detection.bottom}]"
                                )
                            }

                            val labels = results
                                .map {
                                    it.label.trim()
                                        .lowercase(java.util.Locale.ROOT)
                                        .replace("_", "")
                                        .replace(" ", "")
                                }
                                .toSet()

                            // Las cinco clases reales del modelo.
                            val coverall = "coverall" in labels
                            val faceShield = "faceshield" in labels
                            val gloves = "gloves" in labels
                            val goggles = "goggles" in labels
                            val mask = "mask" in labels

                            // La gorra no es una clase del modelo.
                            val cap = false

                            val compliantCount = listOf(
                                coverall,
                                faceShield,
                                gloves,
                                goggles,
                                mask
                            ).count { it }

                            val record = LabRecord(
                                employeeName = employeeName,
                                employeeCard = employeeCard,
                                goggles = goggles,
                                gloves = gloves,
                                labCoat = coverall,
                                cap = cap,
                                faceShield = faceShield,
                                mask = mask,
                                compliantCount = compliantCount,
                                totalElements = 5
                            )

                            Log.i(
                                "LabGuardSave",
                                "Etiquetas detectadas en la foto: $labels"
                            )

                            Log.i(
                                "LabGuardSave",
                                "Goggles=$goggles, " +
                                        "Gloves=$gloves, " +
                                        "Coverall=$coverall, " +
                                        "Face_Shield=$faceShield, " +
                                        "Mask=$mask"
                            )

                            withContext(Dispatchers.IO) {
                                repository.save(record)
                            }

                            // Mostrar la foto después de guardar.
                            analyzedBitmap?.recycle()
                            analyzedBitmap = bitmap
                            bitmapForCleanup = null
                            detections = results

                            statusMessage =
                                "Registro guardado correctamente. " +
                                        "${results.size} detección(es)."

                            Log.i(
                                "LabGuardDB",
                                "Registro guardado para $employeeCard"
                            )
                        } catch (e: Exception) {
                            Log.e(
                                "LabGuardONNX",
                                "Error al analizar/guardar",
                                e
                            )

                            statusMessage =
                                "Error al analizar o guardar: " +
                                        (e.localizedMessage
                                            ?: "Error desconocido")

                            bitmapForCleanup?.recycle()
                            analysisPaused.set(false)
                        } finally {
                            photoFile.delete()
                            isAnalyzing = false
                        }
                    }
                }

                override fun onError(
                    exception: ImageCaptureException
                ) {
                    Log.e(
                        "LabGuardCamera",
                        "Error al capturar foto",
                        exception
                    )

                    statusMessage = "Falló la captura de la imagen."
                    photoFile.delete()
                    analysisPaused.set(false)
                    isAnalyzing = false
                }
            }
        )
    }

    fun takeAnotherPhoto() {
        analyzedBitmap?.recycle()
        analyzedBitmap = null
        detections = emptyList()
        statusMessage = "Apunta la cámara a los elementos de seguridad."
        analysisPaused.set(false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verificación de seguridad") },
                navigationIcon = {
                    TextButton(
                        onClick = {
                            analyzedBitmap?.recycle()
                            analyzedBitmap = null
                            onBack()
                        }
                    ) {
                        Text("Volver")
                    }
                }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                employeeName,
                style = MaterialTheme.typography.titleLarge
            )

            Text("Carné: $employeeCard")

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        Color(0xFF102A43),
                        RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                val bitmap = analyzedBitmap

                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Fotografía analizada",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    DetectionOverlay(
                        detections = detections,
                        sourceWidth = bitmap.width,
                        sourceHeight = bitmap.height,
                        fillCenter = false,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (hasCameraPermission) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize()
                    )

                    DetectionOverlay(
                        detections = detections,
                        sourceWidth = liveFrameWidth,
                        sourceHeight = liveFrameHeight,
                        fillCenter = false,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Button(
                        onClick = {
                            permissionLauncher.launch(
                                Manifest.permission.CAMERA
                            )
                        }
                    ) {
                        Text("Conceder permiso")
                    }
                }

                if (isAnalyzing) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(12.dp)
                    ) {
                        Text(
                            "Procesando imagen...",
                            color = Color.White,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            Text(
                "Objetos detectados: ${detections.size}",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                detections.joinToString(" · ") {
                    "${it.label} ${(it.confidence * 100).toInt()}%"
                }.ifBlank { "Buscando objetos..." },
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                statusMessage,
                style = MaterialTheme.typography.bodySmall
            )

            if (analyzedBitmap == null) {
                Button(
                    onClick = { captureAndAnalyze() },
                    enabled = hasCameraPermission &&
                            modelManager != null &&
                            !isAnalyzing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Text(
                        if (isAnalyzing) "Analizando..."
                        else "Capturar y registrar"
                    )
                }
            } else {
                Button(
                    onClick = { takeAnotherPhoto() },
                    enabled = !isAnalyzing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text("Tomar otra foto")
                }

                OutlinedButton(
                    onClick = {
                        analyzedBitmap?.recycle()
                        analyzedBitmap = null
                        onFinish()
                    },
                    enabled = !isAnalyzing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text("Volver al inicio")
                }
            }
        }
    }
}

/**
 * Decodifica la fotografía capturada y corrige su orientación EXIF.
 * Utiliza android.media.ExifInterface para evitar imports duplicados.
 */
private fun decodeBitmapWithExif(photoFile: File): Bitmap? {
    var original: Bitmap? = null

    return try {
        original = BitmapFactory.decodeFile(photoFile.absolutePath)
            ?: return null

        val exif = ExifInterface(photoFile.absolutePath)

        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )

        Log.i(
            "LabGuardCapture",
            "Foto original: ${original.width}x${original.height}"
        )

        Log.i(
            "LabGuardCapture",
            "Orientación EXIF: $orientation"
        )

        val matrix = Matrix()

        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 ->
                matrix.postRotate(90f)

            ExifInterface.ORIENTATION_ROTATE_180 ->
                matrix.postRotate(180f)

            ExifInterface.ORIENTATION_ROTATE_270 ->
                matrix.postRotate(270f)

            ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                matrix.setScale(-1f, 1f)

            ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                matrix.setScale(1f, -1f)

            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
        }

        val corrected = if (
            orientation == ExifInterface.ORIENTATION_UNDEFINED ||
            orientation == ExifInterface.ORIENTATION_NORMAL
        ) {
            original
        } else {
            Bitmap.createBitmap(
                original,
                0,
                0,
                original.width,
                original.height,
                matrix,
                true
            )
        }

        if (corrected !== original) {
            original.recycle()
        }

        Log.i(
            "LabGuardCapture",
            "Foto corregida: ${corrected.width}x${corrected.height}"
        )

        corrected
    } catch (e: Exception) {
        original?.recycle()

        Log.e(
            "LabGuardCapture",
            "Error al leer o corregir la orientación EXIF",
            e
        )

        null
    }
}

private fun analyzeLiveFrame(
    imageProxy: ImageProxy,
    manager: OnnxModelManager?,
    inferenceBusy: AtomicBoolean,
    analysisPaused: AtomicBoolean,
    onResult: (List<Detection>, Int, Int) -> Unit
) {
    if (
        manager == null ||
        analysisPaused.get() ||
        !inferenceBusy.compareAndSet(false, true)
    ) {
        imageProxy.close()
        return
    }

    var bitmap: Bitmap? = null

    try {
        bitmap = imageProxyToBitmap(imageProxy)
    } catch (e: Exception) {
        Log.e(
            "LabGuardCamera",
            "No se pudo convertir el fotograma",
            e
        )
    } finally {
        imageProxy.close()
    }

    if (bitmap == null) {
        inferenceBusy.set(false)
        return
    }

    val frameBitmap = bitmap

    LiveInferenceExecutor.execute {
        try {
            if (analysisPaused.get()) return@execute

            val results = kotlinx.coroutines.runBlocking {
                manager.detectBitmap(frameBitmap)
            }

            if (!analysisPaused.get()) {
                onResult(
                    results,
                    frameBitmap.width,
                    frameBitmap.height
                )
            }
        } catch (e: Exception) {
            Log.e(
                "LabGuardONNX",
                "Error en detección en vivo",
                e
            )
        } finally {
            frameBitmap.recycle()
            inferenceBusy.set(false)
        }
    }
}

private object LiveInferenceExecutor {
    private val executor = Executors.newSingleThreadExecutor()

    fun execute(task: () -> Unit) {
        executor.execute(task)
    }
}

/**
 * Convierte un fotograma YUV_420_888 de CameraX a Bitmap RGB
 * y aplica la rotación indicada por CameraX.
 */
private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
    val width = image.width
    val height = image.height
    val planes = image.planes

    if (planes.size < 3) return null

    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer

    val yBase = yBuffer.position()
    val uBase = uBuffer.position()
    val vBase = vBuffer.position()

    val nv21 = ByteArray(width * height + width * height / 2)

    var outputIndex = 0

    for (row in 0 until height) {
        for (col in 0 until width) {
            val index = yBase +
                    row * yPlane.rowStride +
                    col * yPlane.pixelStride

            nv21[outputIndex++] = yBuffer.get(index)
        }
    }

    val chromaWidth = width / 2
    val chromaHeight = height / 2
    val chromaStart = width * height

    for (row in 0 until chromaHeight) {
        for (col in 0 until chromaWidth) {
            val vIndex = vBase +
                    row * vPlane.rowStride +
                    col * vPlane.pixelStride

            val uIndex = uBase +
                    row * uPlane.rowStride +
                    col * uPlane.pixelStride

            nv21[chromaStart + row * width + col * 2] =
                vBuffer.get(vIndex)

            nv21[chromaStart + row * width + col * 2 + 1] =
                uBuffer.get(uIndex)
        }
    }

    val output = ByteArrayOutputStream()

    val success = YuvImage(
        nv21,
        ImageFormat.NV21,
        width,
        height,
        null
    ).compressToJpeg(
        Rect(0, 0, width, height),
        80,
        output
    )

    if (!success) return null

    val decoded = BitmapFactory.decodeByteArray(
        output.toByteArray(),
        0,
        output.size()
    ) ?: return null

    val rotation = image.imageInfo.rotationDegrees

    if (rotation == 0) return decoded

    val matrix = Matrix().apply {
        postRotate(rotation.toFloat())
    }

    val rotated = Bitmap.createBitmap(
        decoded,
        0,
        0,
        decoded.width,
        decoded.height,
        matrix,
        true
    )

    if (rotated !== decoded) {
        decoded.recycle()
    }

    return rotated
}

@Composable
private fun DetectionOverlay(
    detections: List<Detection>,
    sourceWidth: Int,
    sourceHeight: Int,
    fillCenter: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return@Canvas
        }

        val scale = if (fillCenter) {
            maxOf(
                size.width / sourceWidth.toFloat(),
                size.height / sourceHeight.toFloat()
            )
        } else {
            minOf(
                size.width / sourceWidth.toFloat(),
                size.height / sourceHeight.toFloat()
            )
        }

        val offsetX = (size.width - sourceWidth * scale) / 2f
        val offsetY = (size.height - sourceHeight * scale) / 2f

        detections.forEach { detection ->
            val left = offsetX + detection.left * scale
            val top = offsetY + detection.top * scale
            val right = offsetX + detection.right * scale
            val bottom = offsetY + detection.bottom * scale

            val boxColor = when (detection.label) {
                "Coverall" -> Color(0xFF4CAF50)
                "Face_Shield" -> Color(0xFFFFC107)
                "Gloves" -> Color(0xFF03A9F4)
                "Goggles" -> Color(0xFFE91E63)
                "Mask" -> Color(0xFF9C27B0)
                else -> Color.White
            }

            drawRect(
                color = boxColor,
                topLeft = Offset(left, top),
                size = Size(
                    right - left,
                    bottom - top
                ),
                style = Stroke(width = 3.dp.toPx())
            )

            drawContext.canvas.nativeCanvas.drawText(
                "${detection.label} " +
                        "${(detection.confidence * 100).toInt()}%",
                left.coerceAtLeast(0f),
                (top - 8f).coerceAtLeast(24f),
                AndroidPaint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 14.dp.toPx()
                    isFakeBoldText = true

                    setShadowLayer(
                        4f,
                        1f,
                        1f,
                        android.graphics.Color.BLACK
                    )
                }
            )
        }
    }
}
