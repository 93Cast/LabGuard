package com.example.labguard.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LabRecordDao {

    @Query("""
        SELECT * FROM lab_records
        ORDER BY timestamp DESC
    """)
    fun getAllRecords(): Flow<List<LabRecord>>

    @Insert
    suspend fun insert(record: LabRecord)

    @Delete
    suspend fun delete(record: LabRecord)
}