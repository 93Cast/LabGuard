
package com.example.labguard

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import com.example.labguard.data.LabDatabase
import com.example.labguard.data.LabRepository
import com.example.labguard.ui.LabGuardApp
import com.example.labguard.ui.LabGuardTheme

class MainActivity : ComponentActivity() {

    private var onnxModelManager: OnnxModelManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e("LabGuardONNX", "MAIN ACTIVITY INICIADA")
        // Inicializar el modelo una sola vez al iniciar la actividad.
        try {
            onnxModelManager = OnnxModelManager(applicationContext)
            onnxModelManager?.printModelInfo()
            onnxModelManager?.testInference()

            Log.i(
                "LabGuardONNX",
                "Modelo epoch45.onnx inicializado correctamente"
            )
        } catch (exception: Exception) {
            onnxModelManager = null

            Log.e(
                "LabGuardONNX",
                "Error al cargar epoch45.onnx",
                exception
            )
        }

        val database = LabDatabase.getDatabase(this)
        val repository = LabRepository(database.labRecordDao())

        setContent {
            LabGuardTheme {
                Surface(color = Color(0xFFF5F8FA)) {
                    LabGuardApp(
                        repository = repository,
                        modelManager = onnxModelManager
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        onnxModelManager?.close()
        onnxModelManager = null
        super.onDestroy()
    }
}
