package com.example.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SupabaseSyncManager(context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val prefs = context.applicationContext.getSharedPreferences("supabase_prefs", Context.MODE_PRIVATE)

    fun getSupabaseUrl(): String {
        val saved = prefs.getString("supabase_url", "") ?: ""
        if (saved.isNotEmpty()) return saved
        return try {
            val field = com.example.BuildConfig::class.java.getField("SUPABASE_URL")
            field.get(null) as? String ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun getSupabaseAnonKey(): String {
        val saved = prefs.getString("supabase_anon_key", "") ?: ""
        if (saved.isNotEmpty()) return saved
        return try {
            val field = com.example.BuildConfig::class.java.getField("SUPABASE_ANON_KEY")
            field.get(null) as? String ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun saveCredentials(url: String, anonKey: String) {
        prefs.edit()
            .putString("supabase_url", url.trim())
            .putString("supabase_anon_key", anonKey.trim())
            .apply()
    }

    suspend fun uploadRecord(record: ImpactRecord): Result<Unit> = withContext(Dispatchers.IO) {
        val url = getSupabaseUrl().trim().removeSuffix("/")
        val anonKey = getSupabaseAnonKey().trim()

        if (url.isEmpty() || anonKey.isEmpty()) {
            return@withContext Result.failure(Exception("Supabase credentials are not configured. Go to the Cloud Sync tab to set them up."))
        }

        try {
            val endpoint = "$url/rest/v1/bike_impact_records"
            
            val jsonObject = JSONObject().apply {
                put("timestamp", record.timestamp)
                put("latitude", record.latitude)
                put("longitude", record.longitude)
                put("impact_g", record.impactG)
                put("is_realtime", record.isRealtime)
            }

            val requestBody = jsonObject.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(endpoint)
                .post(requestBody)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    val bodyStr = response.body?.string() ?: ""
                    Result.failure(Exception("Supabase REST Error (${response.code}): $bodyStr"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadBatch(records: List<ImpactRecord>): Result<Int> = withContext(Dispatchers.IO) {
        val url = getSupabaseUrl().trim().removeSuffix("/")
        val anonKey = getSupabaseAnonKey().trim()

        if (url.isEmpty() || anonKey.isEmpty()) {
            return@withContext Result.failure(Exception("Supabase credentials are not configured."))
        }

        if (records.isEmpty()) {
            return@withContext Result.success(0)
        }

        try {
            val endpoint = "$url/rest/v1/bike_impact_records"
            
            val jsonArray = JSONArray()
            for (record in records) {
                val jsonObject = JSONObject().apply {
                    put("timestamp", record.timestamp)
                    put("latitude", record.latitude)
                    put("longitude", record.longitude)
                    put("impact_g", record.impactG)
                    put("is_realtime", record.isRealtime)
                }
                jsonArray.put(jsonObject)
            }

            val requestBody = jsonArray.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(endpoint)
                .post(requestBody)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(records.size)
                } else {
                    val bodyStr = response.body?.string() ?: ""
                    Result.failure(Exception("Supabase REST Error (${response.code}): $bodyStr"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchAllRecords(): Result<List<ImpactRecord>> = withContext(Dispatchers.IO) {
        val url = getSupabaseUrl().trim().removeSuffix("/")
        val anonKey = getSupabaseAnonKey().trim()

        if (url.isEmpty() || anonKey.isEmpty()) {
            return@withContext Result.failure(Exception("Supabase credentials are not configured."))
        }

        try {
            val endpoint = "$url/rest/v1/bike_impact_records?select=*"

            val request = Request.Builder()
                .url(endpoint)
                .get()
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: "[]"
                    val jsonArray = JSONArray(bodyStr)
                    val records = mutableListOf<ImpactRecord>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val timestamp = obj.optString("timestamp", "")
                        val latitude = obj.optDouble("latitude", 0.0)
                        val longitude = obj.optDouble("longitude", 0.0)
                        val impactG = if (obj.has("impact_g")) obj.optDouble("impact_g", 0.0).toFloat() else obj.optDouble("impactG", 0.0).toFloat()
                        val isRealtime = if (obj.has("is_realtime")) obj.optBoolean("is_realtime", false) else obj.optBoolean("isRealtime", false)
                        
                        records.add(
                            ImpactRecord(
                                timestamp = timestamp,
                                latitude = latitude,
                                longitude = longitude,
                                impactG = impactG,
                                isRealtime = isRealtime
                            )
                        )
                    }
                    Result.success(records)
                } else {
                    val bodyStr = response.body?.string() ?: ""
                    Result.failure(Exception("Supabase Fetch Error (${response.code}): $bodyStr"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun clearAllRecords(): Result<Unit> = withContext(Dispatchers.IO) {
        val url = getSupabaseUrl().trim().removeSuffix("/")
        val anonKey = getSupabaseAnonKey().trim()

        if (url.isEmpty() || anonKey.isEmpty()) {
            return@withContext Result.failure(Exception("Supabase credentials are not configured."))
        }

        try {
            // Delete with filter matching all rows in Supabase table
            val endpoint = "$url/rest/v1/bike_impact_records?latitude=not.is.null"

            val request = Request.Builder()
                .url(endpoint)
                .delete()
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .addHeader("Prefer", "return=minimal")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 204) {
                    Result.success(Unit)
                } else {
                    val bodyStr = response.body?.string() ?: ""
                    Result.failure(Exception("Supabase Delete Error (${response.code}): $bodyStr"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
