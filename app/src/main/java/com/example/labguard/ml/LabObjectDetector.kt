package com.example.labguard.ml

import android.graphics.Bitmap

interface LabObjectDetector {
    suspend fun detect(bitmap: Bitmap): List<Detection>
}