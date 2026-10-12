
package com.example.labguard.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lab_records")
data class LabRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val employeeName: String,
    val employeeCard: String,

    val goggles: Boolean,
    val gloves: Boolean,
    val labCoat: Boolean,
    val cap: Boolean,

    @ColumnInfo(defaultValue = "0")
    val faceShield: Boolean = false,

    @ColumnInfo(defaultValue = "0")
    val mask: Boolean = false,

    val compliantCount: Int,
    val totalElements: Int = 5,

    val timestamp: Long = System.currentTimeMillis()
)
