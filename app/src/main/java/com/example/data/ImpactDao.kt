package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ImpactDao {
    @Query("SELECT * FROM impact_records ORDER BY id DESC")
    fun getAllImpacts(): Flow<List<ImpactRecord>>

    @Query("SELECT * FROM impact_records")
    suspend fun getAllImpactsList(): List<ImpactRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImpact(record: ImpactRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImpacts(records: List<ImpactRecord>)

    @Delete
    suspend fun deleteImpact(record: ImpactRecord)

    @Query("DELETE FROM impact_records")
    suspend fun clearAll()
}
