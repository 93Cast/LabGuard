package com.example.labguard.data

import kotlinx.coroutines.flow.Flow

class LabRepository(
    private val dao: LabRecordDao
) {
    val records: Flow<List<LabRecord>> =
        dao.getAllRecords()

    suspend fun save(record: LabRecord) {
        dao.insert(record)
    }

    suspend fun delete(record: LabRecord) {
        dao.delete(record)
    }
}