package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "impact_records")
data class ImpactRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: String,
    val latitude: Double,
    val longitude: Double,
    val impactG: Float,
    val isRealtime: Boolean = false
) {
    val severity: String
        get() = when {
            impactG >= 4.0f -> "Severe"
            impactG >= 2.5f -> "Moderate"
            else -> "Mild"
        }
}
