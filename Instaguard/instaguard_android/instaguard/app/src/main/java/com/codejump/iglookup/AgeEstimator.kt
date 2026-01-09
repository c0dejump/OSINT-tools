package com.codejump.iglookup

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Comprehensive age estimation for Instagram accounts
 * Uses multiple methods to determine account creation date
 */
class AgeEstimator {

    companion object {
        private const val TAG = "AgeEstimator"

        // Instagram User IDs are sequential - lower ID = older account
        // These ranges are approximations based on known accounts and research
        private val USER_ID_RANGES = arrayOf(
            longArrayOf(1_000_000L, 2010, 6),        // < 1M = mid 2010
            longArrayOf(5_000_000L, 2010, 12),       // < 5M = end 2010
            longArrayOf(15_000_000L, 2011, 6),       // < 15M = mid 2011
            longArrayOf(30_000_000L, 2011, 12),      // < 30M = end 2011
            longArrayOf(50_000_000L, 2012, 6),       // < 50M = mid 2012
            longArrayOf(100_000_000L, 2012, 12),     // < 100M = end 2012
            longArrayOf(150_000_000L, 2013, 6),      // < 150M = mid 2013
            longArrayOf(200_000_000L, 2013, 12),     // < 200M = end 2013
            longArrayOf(300_000_000L, 2014, 6),      // < 300M = mid 2014
            longArrayOf(400_000_000L, 2014, 12),     // < 400M = end 2014
            longArrayOf(600_000_000L, 2015, 6),      // < 600M = mid 2015
            longArrayOf(800_000_000L, 2015, 12),     // < 800M = end 2015
            longArrayOf(1_200_000_000L, 2016, 6),    // < 1.2B = mid 2016
            longArrayOf(1_600_000_000L, 2016, 12),   // < 1.6B = end 2016
            longArrayOf(2_000_000_000L, 2017, 6),    // < 2B = mid 2017
            longArrayOf(2_500_000_000L, 2017, 12),   // < 2.5B = end 2017
            longArrayOf(3_500_000_000L, 2018, 6),    // < 3.5B = mid 2018
            longArrayOf(5_000_000_000L, 2018, 12),   // < 5B = end 2018
            longArrayOf(7_000_000_000L, 2019, 6),    // < 7B = mid 2019
            longArrayOf(10_000_000_000L, 2019, 12),  // < 10B = end 2019
            longArrayOf(15_000_000_000L, 2020, 6),   // < 15B = mid 2020
            longArrayOf(20_000_000_000L, 2020, 12),  // < 20B = end 2020
            longArrayOf(30_000_000_000L, 2021, 6),   // < 30B = mid 2021
            longArrayOf(40_000_000_000L, 2021, 12),  // < 40B = end 2021
            longArrayOf(50_000_000_000L, 2022, 6),   // < 50B = mid 2022
            longArrayOf(60_000_000_000L, 2022, 12),  // < 60B = end 2022
            longArrayOf(70_000_000_000L, 2023, 6),   // < 70B = mid 2023
            longArrayOf(80_000_000_000L, 2023, 12),  // < 80B = end 2023
            longArrayOf(90_000_000_000L, 2024, 6),   // < 90B = mid 2024
            longArrayOf(100_000_000_000L, 2024, 12), // < 100B = end 2024
        )

        private val MONTH_NAMES = arrayOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Comprehensive age estimation result
     */
    data class AgeEstimation(
        // Individual method results
        var userIdDate: String? = null,
        var userIdAge: String? = null,
        var userIdConfidence: Int = 0,
        
        var firstPostDate: String? = null,
        var firstPostAge: String? = null,
        
        var mediaIdDate: String? = null,
        
        var profilePicDate: String? = null,
        
        var waybackDate: String? = null,
        var waybackAge: String? = null,
        var waybackUrl: String? = null,
        
        // Best estimate
        var bestEstimateDate: String? = null,
        var bestEstimateAge: String? = null,
        var bestEstimateSource: String? = null,
        var confidenceScore: Int = 0,
        
        // All methods summary for display
        var methodsUsed: MutableList<String> = mutableListOf()
    )

    /**
     * Run all age estimation methods and return comprehensive result
     */
    suspend fun estimateAge(
        userId: String?,
        username: String,
        profilePicId: String?,
        mediaEdges: List<MediaEdge>?,
        isPrivate: Boolean
    ): AgeEstimation = withContext(Dispatchers.IO) {
        val result = AgeEstimation()
        val estimates = mutableListOf<DateEstimate>()

        // Method 1: User ID estimation (most reliable for creation date)
        if (!userId.isNullOrBlank()) {
            try {
                val userIdEst = estimateFromUserId(userId)
                if (userIdEst != null) {
                    result.userIdDate = userIdEst.date
                    result.userIdAge = userIdEst.age
                    result.userIdConfidence = userIdEst.confidence
                    result.methodsUsed.add("User ID: ${userIdEst.date} (${userIdEst.age})")
                    estimates.add(DateEstimate(userIdEst.date, "User ID", userIdEst.confidence))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in User ID estimation", e)
            }
        }

        // Method 2: First post timestamp (only for public accounts)
        if (!isPrivate && !mediaEdges.isNullOrEmpty()) {
            try {
                val firstPostEst = estimateFromPosts(mediaEdges)
                if (firstPostEst != null) {
                    result.firstPostDate = firstPostEst.date
                    result.firstPostAge = firstPostEst.age
                    result.methodsUsed.add("First post: ${firstPostEst.date}")
                    estimates.add(DateEstimate(firstPostEst.date, "First Post", 85))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in first post estimation", e)
            }
        }

        // Method 3: Media ID decoding
        if (!isPrivate && !mediaEdges.isNullOrEmpty()) {
            try {
                val mediaIdEst = estimateFromMediaId(mediaEdges)
                if (mediaIdEst != null && mediaIdEst != result.firstPostDate) {
                    result.mediaIdDate = mediaIdEst
                    result.methodsUsed.add("Media ID decode: $mediaIdEst")
                    estimates.add(DateEstimate(mediaIdEst, "Media ID", 80))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in media ID estimation", e)
            }
        }

        // Method 4: Profile Picture ID
        if (!profilePicId.isNullOrBlank()) {
            try {
                val picEst = estimateFromProfilePicId(profilePicId)
                if (picEst != null) {
                    result.profilePicDate = picEst
                    result.methodsUsed.add("Profile pic: $picEst")
                    estimates.add(DateEstimate(picEst, "Profile Pic", 60))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in profile pic estimation", e)
            }
        }

        // Method 5: Wayback Machine
        try {
            val waybackEst = fetchWaybackDate(username)
            if (waybackEst != null) {
                result.waybackDate = waybackEst.date
                result.waybackAge = waybackEst.age
                result.waybackUrl = waybackEst.url
                result.methodsUsed.add("Wayback: ${waybackEst.date} (${waybackEst.age})")
                estimates.add(DateEstimate(waybackEst.date, "Wayback", 75))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in Wayback estimation", e)
        }

        // Determine best estimate
        determineBestEstimate(result, estimates)

        result
    }

    /**
     * Method 1: Estimate from User ID
     * Instagram IDs are sequential - lower = older
     */
    private fun estimateFromUserId(userIdStr: String): SimpleEstimate? {
        return try {
            val userId = userIdStr.toLong()
            
            for (range in USER_ID_RANGES) {
                if (userId < range[0]) {
                    val year = range[1].toInt()
                    val month = range[2].toInt()
                    val monthName = MONTH_NAMES[month - 1]
                    
                    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                    val ageYears = currentYear - year
                    
                    return SimpleEstimate(
                        date = "$monthName $year",
                        age = "$ageYears years",
                        confidence = 90
                    )
                }
            }
            
            // ID is higher than our ranges - very recent account
            SimpleEstimate(
                date = "2025+",
                age = "<1 year",
                confidence = 70
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing user ID: $userIdStr", e)
            null
        }
    }

    /**
     * Method 2: Estimate from post timestamps
     */
    private fun estimateFromPosts(mediaEdges: List<MediaEdge>): SimpleEstimate? {
        if (mediaEdges.isEmpty()) return null

        var oldestTimestamp = Long.MAX_VALUE

        for (edge in mediaEdges) {
            edge.timestamp?.let { ts ->
                if (ts > 0 && ts < oldestTimestamp) {
                    oldestTimestamp = ts
                }
            }
        }

        if (oldestTimestamp == Long.MAX_VALUE) return null

        return try {
            val date = Date(oldestTimestamp * 1000)
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val dateStr = sdf.format(date)

            val ageMs = System.currentTimeMillis() - (oldestTimestamp * 1000)
            val ageDays = ageMs / (1000 * 60 * 60 * 24)
            val ageYears = ageDays / 365

            val ageStr = when {
                ageYears >= 1 -> "$ageYears years"
                ageDays >= 30 -> "${ageDays / 30} months"
                else -> "$ageDays days"
            }

            SimpleEstimate(dateStr, ageStr, 85)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Method 3: Extract timestamp from Instagram Media ID
     * Media IDs contain encoded timestamps: timestamp = media_id >> 23
     */
    private fun estimateFromMediaId(mediaEdges: List<MediaEdge>): String? {
        if (mediaEdges.isEmpty()) return null

        // Get the oldest media ID
        var oldestId: Long? = null
        for (edge in mediaEdges) {
            edge.mediaId?.let { idStr ->
                try {
                    val id = if (idStr.contains("_")) {
                        idStr.split("_")[0].toLong()
                    } else {
                        idStr.toLong()
                    }
                    if (oldestId == null || id < oldestId!!) {
                        oldestId = id
                    }
                } catch (e: Exception) {
                    // Ignore parsing errors
                }
            }
        }

        oldestId?.let { id ->
            // Right shift by 23 bits to get Unix timestamp
            val timestamp = id shr 23

            // Validate timestamp is reasonable (2010-2030)
            if (timestamp in 1262304000L..1893456000L) {
                return try {
                    val date = Date(timestamp * 1000)
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    sdf.format(date)
                } catch (e: Exception) {
                    null
                }
            }
        }

        return null
    }

    /**
     * Method 4: Extract timestamp from Profile Picture ID
     */
    private fun estimateFromProfilePicId(profilePicId: String): String? {
        return try {
            val parts = profilePicId.split("_")
            if (parts.isNotEmpty()) {
                val firstPart = parts[0]
                
                // If it's a large number, it might be an Instagram ID with encoded timestamp
                if (firstPart.length > 10) {
                    val id = firstPart.toLong()
                    val timestamp = id shr 23

                    if (timestamp in 1262304000L..1893456000L) {
                        val date = Date(timestamp * 1000)
                        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        return sdf.format(date)
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Method 5: Fetch Wayback Machine first archive date
     */
    private fun fetchWaybackDate(username: String): WaybackEstimate? {
        return try {
            val url = "https://web.archive.org/cdx/search/cdx?url=instagram.com/$username&output=json&limit=1&from=2010"
            
            val request = Request.Builder()
                .url(url)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()

            if (response.isSuccessful && !body.isNullOrBlank()) {
                val arr = JSONArray(body)
                if (arr.length() > 1) {
                    val row = arr.getJSONArray(1)
                    val timestamp = row.getString(1)
                    
                    if (timestamp.length >= 8) {
                        val dateStr = "${timestamp.substring(0, 4)}-${timestamp.substring(4, 6)}-${timestamp.substring(6, 8)}"
                        val year = timestamp.substring(0, 4).toInt()
                        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                        val age = currentYear - year
                        val archiveUrl = "https://web.archive.org/web/$timestamp/https://instagram.com/$username"
                        
                        return WaybackEstimate(dateStr, "$age+ years", archiveUrl)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Wayback data", e)
            null
        }
    }

    /**
     * Determine the best estimate from all available methods
     */
    private fun determineBestEstimate(result: AgeEstimation, estimates: List<DateEstimate>) {
        if (estimates.isEmpty()) {
            result.bestEstimateDate = "Unknown"
            result.bestEstimateAge = "Unknown"
            result.bestEstimateSource = "No data"
            result.confidenceScore = 0
            return
        }

        // Prefer User ID estimate if available (most reliable for creation date)
        val userIdEst = estimates.find { it.source == "User ID" }
        
        if (userIdEst != null) {
            result.bestEstimateDate = userIdEst.date
            result.bestEstimateSource = "User ID"
            result.confidenceScore = userIdEst.confidence
        } else {
            // Otherwise, use the estimate with highest confidence
            val best = estimates.maxByOrNull { it.confidence }
            if (best != null) {
                result.bestEstimateDate = best.date
                result.bestEstimateSource = best.source
                result.confidenceScore = best.confidence
            }
        }

        // Calculate age from best estimate date
        result.bestEstimateAge = calculateAgeFromDate(result.bestEstimateDate)
    }

    private fun calculateAgeFromDate(dateStr: String?): String {
        if (dateStr.isNullOrBlank() || dateStr == "Unknown") return "Unknown"

        return try {
            val year = when {
                dateStr.contains("-") -> dateStr.substring(0, 4).toInt()
                dateStr.contains(" ") -> {
                    // Format: "Jan 2015"
                    val parts = dateStr.split(" ")
                    parts.last().toInt()
                }
                else -> dateStr.substring(0, 4).toInt()
            }

            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            val age = currentYear - year
            "$age years"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    // Helper data classes
    private data class SimpleEstimate(
        val date: String,
        val age: String,
        val confidence: Int
    )

    private data class WaybackEstimate(
        val date: String,
        val age: String,
        val url: String
    )

    private data class DateEstimate(
        val date: String,
        val source: String,
        val confidence: Int
    )

    data class MediaEdge(
        val mediaId: String?,
        val timestamp: Long?
    )
}
