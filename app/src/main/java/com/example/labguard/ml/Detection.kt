package com.example.labguard.ml

data class Detection(
    val label: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class LabDetectionSummary(
    val goggles: Boolean = false,
    val gloves: Boolean = false,
    val labCoat: Boolean = false,
    val cap: Boolean = false
) {
    val compliantCount: Int
        get() = listOf(
            goggles,
            gloves,
            labCoat,
            cap
        ).count { it }
}