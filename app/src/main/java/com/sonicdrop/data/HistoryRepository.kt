package com.sonicdrop.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class TransferRecord(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val timestamp: Long,
    val isSender: Boolean,
    val durationSeconds: Float,
    val isSuccess: Boolean,
    val filePath: String? = null
)

class HistoryRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sonicdrop_history", Context.MODE_PRIVATE)
    private val _records = MutableStateFlow<List<TransferRecord>>(emptyList())
    val records: StateFlow<List<TransferRecord>> = _records.asStateFlow()

    init {
        loadRecords()
    }

    private fun loadRecords() {
        val jsonStr = prefs.getString("records_json", null) ?: return
        try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<TransferRecord>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    TransferRecord(
                        id = obj.getString("id"),
                        fileName = obj.getString("fileName"),
                        fileSize = obj.getLong("fileSize"),
                        timestamp = obj.getLong("timestamp"),
                        isSender = obj.getBoolean("isSender"),
                        durationSeconds = obj.getDouble("durationSeconds").toFloat(),
                        isSuccess = obj.getBoolean("isSuccess"),
                        filePath = if (obj.has("filePath") && !obj.isNull("filePath")) obj.getString("filePath") else null
                    )
                )
            }
            _records.value = list.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            _records.value = emptyList()
        }
    }

    fun addRecord(record: TransferRecord) {
        val updated = listOf(record) + _records.value
        _records.value = updated
        saveRecords(updated)
    }

    fun clearHistory() {
        _records.value = emptyList()
        prefs.edit().remove("records_json").apply()
    }

    private fun saveRecords(list: List<TransferRecord>) {
        val arr = JSONArray()
        list.take(50).forEach { r ->
            val obj = JSONObject()
            obj.put("id", r.id)
            obj.put("fileName", r.fileName)
            obj.put("fileSize", r.fileSize)
            obj.put("timestamp", r.timestamp)
            obj.put("isSender", r.isSender)
            obj.put("durationSeconds", r.durationSeconds.toDouble())
            obj.put("isSuccess", r.isSuccess)
            obj.put("filePath", r.filePath)
            arr.put(obj)
        }
        prefs.edit().putString("records_json", arr.toString()).apply()
    }
}
