package com.codejump.iglookup

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class HistoryManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("ig_lookup_history", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        const val KEY_HISTORY = "search_history"
        const val KEY_SCAN_RESULT = "last_scan_result"
        const val KEY_SCAN_TIMESTAMP = "last_scan_timestamp"
        const val MAX_HISTORY = 50  // Increased for better caching
    }

    fun getHistory(): List<InstagramProfile> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        val type = object : TypeToken<List<InstagramProfile>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get a profile from history by username (for caching)
     */
    fun getFromHistory(username: String): InstagramProfile? {
        return getHistory().find { it.username.equals(username, ignoreCase = true) }
    }

    fun addToHistory(profile: InstagramProfile) {
        val history = getHistory().toMutableList()
        
        // Remove if already exists
        history.removeAll { it.username.equals(profile.username, ignoreCase = true) }
        
        // Add to beginning
        history.add(0, profile.copy(timestamp = System.currentTimeMillis()))
        
        // Keep only last MAX_HISTORY items
        val trimmed = history.take(MAX_HISTORY)
        
        // Save
        prefs.edit().putString(KEY_HISTORY, gson.toJson(trimmed)).apply()
    }

    fun removeFromHistory(username: String) {
        val history = getHistory().toMutableList()
        history.removeAll { it.username.equals(username, ignoreCase = true) }
        prefs.edit().putString(KEY_HISTORY, gson.toJson(history)).apply()
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }
    
    // ==================== SCAN RESULTS STORAGE ====================
    
    /**
     * Data class for storing scan results with metadata
     */
    data class StoredScanResult(
        val totalScanned: Int,
        val highRiskCount: Int,
        val suspiciousCount: Int,
        val normalCount: Int,
        val highRiskFollowers: List<StoredFollowerAnalysis>,
        val suspiciousFollowers: List<StoredFollowerAnalysis>,
        val timestamp: Long
    )
    
    data class StoredFollowerAnalysis(
        val username: String,
        val fullName: String?,
        val pk: String?,
        val riskScore: Int,
        val riskLevel: String,
        val flags: List<String>,
        val hasProfilePic: Boolean,
        val isPrivate: Boolean,
        val isVerified: Boolean,
        val estimatedAge: String?
    )
    
    /**
     * Save scan results
     */
    fun saveScanResult(result: FollowersScanner.ScanResult) {
        val storedResult = StoredScanResult(
            totalScanned = result.totalScanned,
            highRiskCount = result.highRiskCount,
            suspiciousCount = result.suspiciousCount,
            normalCount = result.normalCount,
            highRiskFollowers = result.highRiskFollowers.map { it.toStored() },
            suspiciousFollowers = result.suspiciousFollowers.map { it.toStored() },
            timestamp = System.currentTimeMillis()
        )
        
        prefs.edit()
            .putString(KEY_SCAN_RESULT, gson.toJson(storedResult))
            .putLong(KEY_SCAN_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }
    
    /**
     * Get cached scan result if still valid (less than 24 hours old)
     */
    fun getCachedScanResult(maxAgeHours: Int = 24): FollowersScanner.ScanResult? {
        val json = prefs.getString(KEY_SCAN_RESULT, null) ?: return null
        val timestamp = prefs.getLong(KEY_SCAN_TIMESTAMP, 0)
        
        // Check if expired
        val ageMs = System.currentTimeMillis() - timestamp
        val maxAgeMs = maxAgeHours * 60 * 60 * 1000L
        if (ageMs > maxAgeMs) {
            return null
        }
        
        return try {
            val stored = gson.fromJson(json, StoredScanResult::class.java)
            FollowersScanner.ScanResult(
                totalScanned = stored.totalScanned,
                highRiskCount = stored.highRiskCount,
                suspiciousCount = stored.suspiciousCount,
                normalCount = stored.normalCount,
                highRiskFollowers = stored.highRiskFollowers.map { it.toAnalysis() },
                suspiciousFollowers = stored.suspiciousFollowers.map { it.toAnalysis() }
            )
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Get scan result age in minutes
     */
    fun getScanResultAgeMinutes(): Long {
        val timestamp = prefs.getLong(KEY_SCAN_TIMESTAMP, 0)
        if (timestamp == 0L) return -1
        return (System.currentTimeMillis() - timestamp) / 60000
    }
    
    /**
     * Clear cached scan result
     */
    fun clearScanResult() {
        prefs.edit()
            .remove(KEY_SCAN_RESULT)
            .remove(KEY_SCAN_TIMESTAMP)
            .apply()
    }
    
    /**
     * Remove a specific follower from cached scan result (after unfollow)
     */
    fun removeFollowerFromCache(username: String): Boolean {
        val json = prefs.getString(KEY_SCAN_RESULT, null) ?: return false
        
        return try {
            val stored = gson.fromJson(json, StoredScanResult::class.java)
            
            // Check if user exists in any list
            val inHighRisk = stored.highRiskFollowers.any { it.username.equals(username, ignoreCase = true) }
            val inSuspicious = stored.suspiciousFollowers.any { it.username.equals(username, ignoreCase = true) }
            
            if (!inHighRisk && !inSuspicious) return false
            
            // Remove from lists
            val newHighRisk = stored.highRiskFollowers.filterNot { it.username.equals(username, ignoreCase = true) }
            val newSuspicious = stored.suspiciousFollowers.filterNot { it.username.equals(username, ignoreCase = true) }
            
            // Update counts
            val updatedStored = stored.copy(
                totalScanned = stored.totalScanned - 1,
                highRiskCount = newHighRisk.size,
                suspiciousCount = newSuspicious.size,
                highRiskFollowers = newHighRisk,
                suspiciousFollowers = newSuspicious
            )
            
            // Save back
            prefs.edit()
                .putString(KEY_SCAN_RESULT, gson.toJson(updatedStored))
                .apply()
            
            true
        } catch (e: Exception) {
            false
        }
    }
    
    // Extension functions for conversion
    private fun FollowersScanner.FollowerAnalysis.toStored() = StoredFollowerAnalysis(
        username = username,
        fullName = fullName,
        pk = pk,
        riskScore = riskScore,
        riskLevel = riskLevel,
        flags = flags,
        hasProfilePic = hasProfilePic,
        isPrivate = isPrivate,
        isVerified = isVerified,
        estimatedAge = estimatedAge
    )
    
    private fun StoredFollowerAnalysis.toAnalysis() = FollowersScanner.FollowerAnalysis(
        username = username,
        fullName = fullName,
        pk = pk,
        riskScore = riskScore,
        riskLevel = riskLevel,
        flags = flags,
        hasProfilePic = hasProfilePic,
        isPrivate = isPrivate,
        isVerified = isVerified,
        estimatedAge = estimatedAge
    )
}
