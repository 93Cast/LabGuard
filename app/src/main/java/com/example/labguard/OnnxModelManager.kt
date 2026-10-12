
package com.example.labguard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Color
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.labguard.ml.Detection
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

class OnnxModelManager(context: Context) {

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private val inputName = "images"
    private val outputName = "output0"

    private val inputSize = 640
    private val candidateCount = 8400
    private val expectedOutputSize = 9 * candidateCount

    private val classNames = listOf(
        "Coverall",
        "Face_Shield",
        "Gloves",
        "Goggles",
        "Mask"
    )

    // Umbral inicial para reducir falsos positivos.
    private val confidenceThreshold = 0.35f

    // Supresión de cajas duplicadas.
    private val nmsThreshold = 0.45f

    // Máximo de detecciones finales por clase.
    private val maxDetectionsPerClass = 20

    init {
        val modelBytes = context.applicationContext.assets
            .open("epoch45.onnx")
            .use { it.readBytes() }

        session = environment.createSession(
            modelBytes,
            OrtSession.SessionOptions()
        )

        Log.i("LabGuardONNX", "Modelo cargado correctamente")
        printModelInfo()
    }

    fun printModelInfo() {
        Log.i("LabGuardONNX", "=== INFORMACIÓN DEL MODELO ===")

        session.inputInfo.forEach { (name, info) ->
            Log.i("LabGuardONNX", "Entrada: $name -> $info")
        }

        session.outputInfo.forEach { (name, info) ->
            Log.i("LabGuardONNX", "Salida: $name -> $info")
        }
    }

    fun testInference() {
        val inputData = FloatArray(
            3 * inputSize * inputSize
        )

        try {
            runInference(inputData) { output ->
                Log.i(
                    "LabGuardONNX",
                    "Inferencia de prueba ejecutada: ${output.info}"
                )
            }
        } catch (e: Exception) {
            Log.e(
                "LabGuardONNX",
                "Falló la inferencia de prueba",
                e
            )
        }
    }

    private data class PreparedImage(
        val tensorData: FloatArray,
        val scale: Float,
        val padLeft: Int,
        val padTop: Int,
        val resizedWidth: Int,
        val resizedHeight: Int
    )

    private data class RawCandidate(
        val classIndex: Int,
        val candidateIndex: Int,
        val score: Float,
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float
    )

    private fun prepareBitmap(bitmap: Bitmap): PreparedImage {
        require(bitmap.width > 0 && bitmap.height > 0) {
            "La imagen está vacía"
        }

        // Redimensionar manteniendo la proporción.
        val scale = min(
            inputSize.toFloat() / bitmap.width,
            inputSize.toFloat() / bitmap.height
        )

        val resizedWidth = (bitmap.width * scale)
            .toInt()
            .coerceIn(1, inputSize)

        val resizedHeight = (bitmap.height * scale)
            .toInt()
            .coerceIn(1, inputSize)

        val resized = Bitmap.createScaledBitmap(
            bitmap,
            resizedWidth,
            resizedHeight,
            true
        )

        val prepared = Bitmap.createBitmap(
            inputSize,
            inputSize,
            Bitmap.Config.ARGB_8888
        )

        val padLeft = (inputSize - resizedWidth) / 2
        val padTop = (inputSize - resizedHeight) / 2

        try {
            val canvas = Canvas(prepared)

            canvas.drawColor(Color.rgb(114, 114, 114))

            canvas.drawBitmap(
                resized,
                null,
                Rect(
                    padLeft,
                    padTop,
                    padLeft + resizedWidth,
                    padTop + resizedHeight
                ),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )

            val pixels = IntArray(inputSize * inputSize)

            prepared.getPixels(
                pixels,
                0,
                inputSize,
                0,
                0,
                inputSize,
                inputSize
            )

            val planeSize = inputSize * inputSize
            val inputData = FloatArray(3 * planeSize)

            // NCHW, RGB, valores normalizados entre 0 y 1.
            for (i in pixels.indices) {
                val pixel = pixels[i]

                inputData[i] =
                    Color.red(pixel) / 255f

                inputData[planeSize + i] =
                    Color.green(pixel) / 255f

                inputData[2 * planeSize + i] =
                    Color.blue(pixel) / 255f
            }

            return PreparedImage(
                tensorData = inputData,
                scale = scale,
                padLeft = padLeft,
                padTop = padTop,
                resizedWidth = resizedWidth,
                resizedHeight = resizedHeight
            )
        } finally {
            if (resized !== bitmap && !resized.isRecycled) {
                resized.recycle()
            }

            prepared.recycle()
        }
    }

    private fun runInference(
        inputData: FloatArray,
        consumeOutput: (OnnxTensor) -> Unit
    ) {
        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(inputData),
            longArrayOf(
                1L,
                3L,
                inputSize.toLong(),
                inputSize.toLong()
            )
        ).use { inputTensor ->

            session.run(
                mapOf(inputName to inputTensor)
            ).use { result ->

                val output = result
                    .get(outputName)
                    .orElseThrow() as OnnxTensor

                consumeOutput(output)
            }
        }
    }

    /**
     * Diagnóstico de las puntuaciones de las cinco clases.
     * No realiza una segunda detección ni dibuja cajas.
     */
    fun diagnoseBitmap(bitmap: Bitmap) {
        try {
            val prepared = prepareBitmap(bitmap)

            runInference(prepared.tensorData) { output ->
                val buffer = output.floatBuffer
                buffer.rewind()

                if (buffer.remaining() < expectedOutputSize) {
                    Log.e(
                        "LabGuardONNX",
                        "Salida demasiado pequeña: ${buffer.remaining()}"
                    )
                    return@runInference
                }

                val data = FloatArray(expectedOutputSize)
                buffer.get(data)

                Log.i(
                    "LabGuardONNX",
                    "Imagen original: ${bitmap.width}x${bitmap.height}"
                )

                Log.i(
                    "LabGuardONNX",
                    "Letterbox: escala=${prepared.scale}, " +
                            "relleno=${prepared.padLeft},${prepared.padTop}, " +
                            "contenido=${prepared.resizedWidth}x${prepared.resizedHeight}"
                )

                for (classIndex in classNames.indices) {
                    val channel = 4 + classIndex
                    var maxScore = Float.NEGATIVE_INFINITY
                    var above05 = 0
                    var above025 = 0
                    var above050 = 0

                    for (i in 0 until candidateCount) {
                        val score = data[channel * candidateCount + i]

                        if (!score.isFinite()) continue

                        maxScore = max(maxScore, score)

                        if (score >= 0.05f) above05++
                        if (score >= 0.25f) above025++
                        if (score >= 0.50f) above050++
                    }

                    Log.i(
                        "LabGuardONNX",
                        "${classNames[classIndex]}: " +
                                "máximo=$maxScore, " +
                                "candidatos>=0.05=$above05, " +
                                ">=0.25=$above025, " +
                                ">=0.50=$above050"
                    )
                }

                val top = (0 until candidateCount)
                    .mapNotNull { i ->
                        var bestClass = -1
                        var bestScore = Float.NEGATIVE_INFINITY

                        for (classIndex in classNames.indices) {
                            val score = data[
                                (4 + classIndex) * candidateCount + i
                            ]

                            if (score.isFinite() && score > bestScore) {
                                bestScore = score
                                bestClass = classIndex
                            }
                        }

                        if (bestClass < 0) {
                            null
                        } else {
                            RawCandidate(
                                classIndex = bestClass,
                                candidateIndex = i,
                                score = bestScore,
                                centerX = data[i],
                                centerY = data[candidateCount + i],
                                width = data[2 * candidateCount + i],
                                height = data[3 * candidateCount + i]
                            )
                        }
                    }
                    .sortedByDescending { it.score }
                    .take(10)

                Log.i("LabGuardONNX", "=== TOP 10 CANDIDATOS ===")

                top.forEachIndexed { position, candidate ->
                    Log.i(
                        "LabGuardONNX",
                        "#${position + 1}: " +
                                "${classNames[candidate.classIndex]}, " +
                                "score=${candidate.score}, " +
                                "índice=${candidate.candidateIndex}, " +
                                "xywh=(${candidate.centerX}," +
                                "${candidate.centerY}," +
                                "${candidate.width}," +
                                "${candidate.height})"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("LabGuardONNX", "Error en diagnóstico", e)
        }
    }

    /**
     * Detecta elementos y transforma las cajas al sistema
     * de coordenadas del bitmap original.
     */
    fun detectBitmap(bitmap: Bitmap): List<Detection> {
        val prepared = prepareBitmap(bitmap)
        val candidates = mutableListOf<Detection>()

        runInference(prepared.tensorData) { output ->
            val buffer = output.floatBuffer
            buffer.rewind()

            require(buffer.remaining() >= expectedOutputSize) {
                "Forma de salida inesperada: ${buffer.remaining()} valores; " +
                        "se esperaban al menos $expectedOutputSize"
            }

            val data = FloatArray(expectedOutputSize)
            buffer.get(data)

            // Límites de la zona que contiene la imagen,
            // excluyendo las bandas de relleno del letterbox.
            val contentLeft = prepared.padLeft.toFloat()
            val contentTop = prepared.padTop.toFloat()

            val contentRight = (
                    prepared.padLeft + prepared.resizedWidth
                    ).toFloat()

            val contentBottom = (
                    prepared.padTop + prepared.resizedHeight
                    ).toFloat()

            for (i in 0 until candidateCount) {
                var bestClass = -1
                var bestScore = Float.NEGATIVE_INFINITY

                // Salida asumida: [1, 9, 8400].
                // Canales 0-3: xywh; canales 4-8: puntuaciones.
                for (classIndex in classNames.indices) {
                    val score = data[
                        (4 + classIndex) * candidateCount + i
                    ]

                    if (score.isFinite() && score > bestScore) {
                        bestScore = score
                        bestClass = classIndex
                    }
                }

                if (
                    bestClass < 0 ||
                    bestScore < confidenceThreshold
                ) {
                    continue
                }

                val centerX = data[i]
                val centerY = data[candidateCount + i]
                val width = data[2 * candidateCount + i]
                val height = data[3 * candidateCount + i]

                if (
                    !centerX.isFinite() ||
                    !centerY.isFinite() ||
                    !width.isFinite() ||
                    !height.isFinite() ||
                    width <= 0f ||
                    height <= 0f
                ) {
                    continue
                }

                // Coordenadas de la caja dentro de la imagen 640x640.
                val rawLeft = centerX - width / 2f
                val rawTop = centerY - height / 2f
                val rawRight = centerX + width / 2f
                val rawBottom = centerY + height / 2f

                // Recortar primero al área real de imagen.
                // Así no se interpreta el relleno como contenido.
                val modelLeft = max(rawLeft, contentLeft)
                    .coerceAtMost(contentRight)

                val modelTop = max(rawTop, contentTop)
                    .coerceAtMost(contentBottom)

                val modelRight = min(rawRight, contentRight)
                    .coerceAtLeast(contentLeft)

                val modelBottom = min(rawBottom, contentBottom)
                    .coerceAtLeast(contentTop)

                if (
                    modelRight <= modelLeft ||
                    modelBottom <= modelTop
                ) {
                    continue
                }

                // Deshacer el letterbox y volver a la imagen original.
                val left = (
                        (modelLeft - prepared.padLeft) / prepared.scale
                        ).coerceIn(0f, bitmap.width.toFloat())

                val top = (
                        (modelTop - prepared.padTop) / prepared.scale
                        ).coerceIn(0f, bitmap.height.toFloat())

                val right = (
                        (modelRight - prepared.padLeft) / prepared.scale
                        ).coerceIn(0f, bitmap.width.toFloat())

                val bottom = (
                        (modelBottom - prepared.padTop) / prepared.scale
                        ).coerceIn(0f, bitmap.height.toFloat())

                if (
                    !left.isFinite() ||
                    !top.isFinite() ||
                    !right.isFinite() ||
                    !bottom.isFinite() ||
                    right <= left ||
                    bottom <= top
                ) {
                    continue
                }

                candidates.add(
                    Detection(
                        label = classNames[bestClass],
                        confidence = bestScore,
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom
                    )
                )
            }
        }

        // NMS independiente por clase para evitar que una caja
        // de otra clase elimine una detección válida.
        val selected = mutableListOf<Detection>()

        for (label in classNames) {
            val pending = candidates
                .filter { it.label == label }
                .sortedByDescending { it.confidence }
                .toMutableList()

            var keptForClass = 0

            while (
                pending.isNotEmpty() &&
                keptForClass < maxDetectionsPerClass
            ) {
                val best = pending.removeAt(0)
                selected.add(best)
                keptForClass++

                pending.removeAll { other ->
                    intersectionOverUnion(best, other) > nmsThreshold
                }
            }
        }

        val finalDetections = selected
            .sortedByDescending { it.confidence }

        Log.i(
            "LabGuardONNX",
            "Imagen ${bitmap.width}x${bitmap.height}: " +
                    "${finalDetections.size} detecciones finales"
        )

        finalDetections.forEach { detection ->
            Log.i(
                "LabGuardONNX",
                "${detection.label}: " +
                        "${(detection.confidence * 100).toInt()}%, " +
                        "caja=(${detection.left.toInt()}," +
                        "${detection.top.toInt()}," +
                        "${detection.right.toInt()}," +
                        "${detection.bottom.toInt()})"
            )
        }

        return finalDetections
    }

    private fun intersectionOverUnion(
        a: Detection,
        b: Detection
    ): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)

        val intersection =
            max(0f, right - left) *
                    max(0f, bottom - top)

        val areaA =
            max(0f, a.right - a.left) *
                    max(0f, a.bottom - a.top)

        val areaB =
            max(0f, b.right - b.left) *
                    max(0f, b.bottom - b.top)

        val union = areaA + areaB - intersection

        return if (union > 0f) {
            intersection / union
        } else {
            0f
        }
    }

    fun close() {
        session.close()
        Log.i("LabGuardONNX", "Sesión del modelo cerrada")
    }
}
