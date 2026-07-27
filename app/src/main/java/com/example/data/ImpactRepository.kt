package com.example.data

import kotlinx.coroutines.flow.Flow

class ImpactRepository(private val impactDao: ImpactDao) {
    val allImpacts: Flow<List<ImpactRecord>> = impactDao.getAllImpacts()

    suspend fun insert(record: ImpactRecord): Long {
        return impactDao.insertImpact(record)
    }

    suspend fun insertBatch(records: List<ImpactRecord>) {
        impactDao.insertImpacts(records)
    }

    suspend fun delete(record: ImpactRecord) {
        impactDao.deleteImpact(record)
    }

    suspend fun clearAll() {
        impactDao.clearAll()
    }
}
