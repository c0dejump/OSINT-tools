package com.codejump.iglookup

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Scanner for analyzing followers for risky accounts
 */
class FollowersScanner {

    companion object {
        private const val TAG = "FollowersScanner"
        
        // Scam keywords in names/bios
        private val SCAM_KEYWORDS = listOf(
            "bitcoin", "crypto", "forex", "trading", "invest",
            "make money", "earn", "profit", "income", "roi",
            "dm me", "message me", "text me", "contact me",
            "sugar", "daddy", "mommy", "dating",
            "loan", "grant", "beneficiary", "inheritance",
            "hack", "recovery", "unlock", "verify",
            "lottery", "winner", "claim", "prize"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val ageEstimator = AgeEstimator()

    // Session data
    var sessionId: String? = null
    var csrfToken: String? = null
    var loggedInUserId: String? = null
    var loggedInUsername: String? = null

    /**
     * Login result
     */
    sealed class LoginResult {
        data class Success(val username: String, val userId: String?) : LoginResult()
        data class TwoFactorRequired(val message: String) : LoginResult()
        data class ChallengeRequired(val message: String) : LoginResult()
        data class Error(val message: String) : LoginResult()
    }

    /**
     * Scan result
     */
    data class ScanResult(
        val totalScanned: Int,
        val highRiskCount: Int,
        val suspiciousCount: Int,
        val normalCount: Int,
        val highRiskFollowers: List<FollowerAnalysis>,
        val suspiciousFollowers: List<FollowerAnalysis>,
        val errorMessage: String? = null
    )

    /**
     * Individual follower analysis
     */
    data class FollowerAnalysis(
        val username: String,
        val fullName: String?,
        val pk: String?,
        val riskScore: Int,
        val riskLevel: String,  // "high", "suspicious", "normal"
        val flags: List<String>,
        val hasProfilePic: Boolean,
        val isPrivate: Boolean,
        val isVerified: Boolean,
        val estimatedAge: String?
    )

    /**
     * Login to Instagram using web API (more reliable)
     */
    suspend fun login(username: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        try {
            // Step 1: Get CSRF token from Instagram homepage
            Log.d(TAG, "Step 1: Getting CSRF token...")
            val homeRequest = Request.Builder()
                .url("https://www.instagram.com/")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .build()
            
            val homeResponse = client.newCall(homeRequest).execute()
            
            // Extract CSRF token from cookies
            homeResponse.headers("Set-Cookie").forEach { cookie ->
                if (cookie.contains("csrftoken=")) {
                    val start = cookie.indexOf("csrftoken=") + 10
                    val end = cookie.indexOf(";", start).takeIf { it > start } ?: cookie.length
                    csrfToken = cookie.substring(start, end)
                }
            }
            homeResponse.close()
            
            if (csrfToken.isNullOrBlank()) {
                // Try alternative: extract from page content
                Log.w(TAG, "CSRF token not in cookies, trying alternative...")
                csrfToken = System.currentTimeMillis().toString()
            }
            
            Log.d(TAG, "Step 2: Logging in with CSRF: ${csrfToken?.take(10)}...")
            
            // Step 2: Login request
            val timestamp = System.currentTimeMillis() / 1000
            
            val formBody = FormBody.Builder()
                .add("username", username)
                .add("enc_password", "#PWD_INSTAGRAM:0:$timestamp:$password")
                .add("queryParams", "{}")
                .add("optIntoOneTap", "false")
                .add("trustedDeviceRecords", "{}")
                .build()

            val loginRequest = Request.Builder()
                .url("https://www.instagram.com/api/v1/web/accounts/login/ajax/")
                .post(formBody)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .addHeader("X-CSRFToken", csrfToken ?: "")
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("X-IG-App-ID", "936619743392459")
                .addHeader("Referer", "https://www.instagram.com/accounts/login/")
                .addHeader("Origin", "https://www.instagram.com")
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .build()

            val response = client.newCall(loginRequest).execute()
            val responseBody = response.body?.string()
            
            // Extract session cookie
            response.headers("Set-Cookie").forEach { cookie ->
                if (cookie.contains("sessionid=")) {
                    val start = cookie.indexOf("sessionid=") + 10
                    val end = cookie.indexOf(";", start).takeIf { it > start } ?: cookie.length
                    sessionId = cookie.substring(start, end)
                    Log.d(TAG, "Got session ID: ${sessionId?.take(10)}...")
                }
                if (cookie.contains("csrftoken=")) {
                    val start = cookie.indexOf("csrftoken=") + 10
                    val end = cookie.indexOf(";", start).takeIf { it > start } ?: cookie.length
                    csrfToken = cookie.substring(start, end)
                }
            }

            response.close()

            if (responseBody.isNullOrBlank()) {
                return@withContext LoginResult.Error("Empty response from server")
            }
            
            Log.d(TAG, "Login response: ${responseBody.take(200)}")

            val json = try {
                JSONObject(responseBody)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse JSON: $responseBody")
                return@withContext LoginResult.Error("Invalid response from Instagram. Try again later.")
            }

            when {
                json.optBoolean("authenticated", false) -> {
                    loggedInUserId = json.optString("userId", null)
                    loggedInUsername = username
                    Log.d(TAG, "Login successful! User ID: $loggedInUserId")
                    LoginResult.Success(loggedInUsername!!, loggedInUserId)
                }
                json.has("logged_in_user") -> {
                    val user = json.optJSONObject("logged_in_user")
                    loggedInUserId = user?.optString("pk")
                    loggedInUsername = user?.optString("username") ?: username
                    LoginResult.Success(loggedInUsername!!, loggedInUserId)
                }
                json.optBoolean("two_factor_required", false) -> {
                    LoginResult.TwoFactorRequired("Two-factor authentication required.\n\nPlease disable 2FA temporarily in Instagram settings, or try logging in from the Instagram app first.")
                }
                json.has("checkpoint_url") || json.has("challenge") -> {
                    LoginResult.ChallengeRequired("Security verification required.\n\nPlease open Instagram app, verify your identity, then try again here.")
                }
                else -> {
                    var message = json.optString("message", "Login failed")
                    // Make error messages more user-friendly
                    if (message.contains("password", ignoreCase = true)) {
                        message = "Incorrect password. Please try again."
                    } else if (message.contains("user", ignoreCase = true) || message.contains("username", ignoreCase = true)) {
                        message = "Username not found."
                    } else if (message.contains("wait", ignoreCase = true) || message.contains("try again", ignoreCase = true)) {
                        message = "Too many attempts. Please wait a few minutes."
                    }
                    LoginResult.Error(message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login error", e)
            val errorMsg = when {
                e.message?.contains("timeout", ignoreCase = true) == true -> "Connection timeout. Check your internet."
                e.message?.contains("network", ignoreCase = true) == true -> "Network error. Check your connection."
                e.message?.contains("SSL", ignoreCase = true) == true -> "Security error. Try again later."
                else -> e.message ?: "Unknown error occurred"
            }
            LoginResult.Error(errorMsg)
        }
    }

    /**
     * Check if logged in
     */
    fun isLoggedIn(): Boolean = sessionId != null

    /**
     * Clear session
     */
    fun logout() {
        sessionId = null
        csrfToken = null
        loggedInUserId = null
        loggedInUsername = null
    }

    /**
     * Scan followers for risky accounts
     */
    suspend fun scanFollowers(
        onProgress: (current: Int, total: Int, username: String) -> Unit
    ): ScanResult = withContext(Dispatchers.IO) {
        if (sessionId == null) {
            return@withContext ScanResult(0, 0, 0, 0, emptyList(), emptyList(), "Not logged in")
        }

        try {
            // Get user ID if not available
            if (loggedInUserId == null && loggedInUsername != null) {
                loggedInUserId = fetchUserId(loggedInUsername!!)
            }

            if (loggedInUserId == null) {
                return@withContext ScanResult(0, 0, 0, 0, emptyList(), emptyList(), "Could not get user ID")
            }

            // Fetch followers
            val followers = fetchFollowers(loggedInUserId!!)

            if (followers.isEmpty()) {
                return@withContext ScanResult(0, 0, 0, 0, emptyList(), emptyList(), 
                    "No followers found or unable to fetch. May be rate limited.")
            }

            val highRiskList = mutableListOf<FollowerAnalysis>()
            val suspiciousList = mutableListOf<FollowerAnalysis>()
            var normalCount = 0

            followers.forEachIndexed { index, follower ->
                val username = follower.optString("username", "")
                onProgress(index + 1, followers.size, username)

                val analysis = analyzeFollower(follower)

                when (analysis.riskLevel) {
                    "high" -> highRiskList.add(analysis)
                    "suspicious" -> suspiciousList.add(analysis)
                    else -> normalCount++
                }

                // Rate limiting
                if (index % 10 == 0 && index > 0) {
                    delay(200)
                }
            }

            ScanResult(
                totalScanned = followers.size,
                highRiskCount = highRiskList.size,
                suspiciousCount = suspiciousList.size,
                normalCount = normalCount,
                highRiskFollowers = highRiskList.sortedByDescending { it.riskScore },
                suspiciousFollowers = suspiciousList.sortedByDescending { it.riskScore }
            )

        } catch (e: Exception) {
            Log.e(TAG, "Scan error", e)
            ScanResult(0, 0, 0, 0, emptyList(), emptyList(), e.message)
        }
    }

    /**
     * Fetch user ID from username
     */
    private fun fetchUserId(username: String): String? {
        return try {
            val request = Request.Builder()
                .url("https://www.instagram.com/api/v1/users/web_profile_info/?username=$username")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("x-ig-app-id", "936619743392459")
                .addHeader("Cookie", "sessionid=$sessionId")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()

            if (!body.isNullOrBlank()) {
                val json = JSONObject(body)
                json.optJSONObject("data")
                    ?.optJSONObject("user")
                    ?.optString("id")
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching user ID", e)
            null
        }
    }

    /**
     * Fetch followers list
     */
    private fun fetchFollowers(userId: String): List<JSONObject> {
        val followers = mutableListOf<JSONObject>()
        var maxId: String? = null
        val maxPages = 10  // Limit to prevent too many requests

        try {
            for (page in 0 until maxPages) {
                val urlBuilder = StringBuilder("https://i.instagram.com/api/v1/friendships/$userId/followers/")
                if (maxId != null) {
                    urlBuilder.append("?max_id=$maxId")
                }

                val request = Request.Builder()
                    .url(urlBuilder.toString())
                    .addHeader("User-Agent", "Instagram 275.0.0.27.98 Android")
                    .addHeader("Cookie", "sessionid=$sessionId")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string()
                response.close()

                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val json = JSONObject(body)
                    val users = json.optJSONArray("users")

                    if (users != null) {
                        for (i in 0 until users.length()) {
                            followers.add(users.getJSONObject(i))
                        }
                    }

                    // Check for next page
                    if (json.optBoolean("big_list", false) && json.has("next_max_id")) {
                        maxId = json.getString("next_max_id")
                    } else {
                        break
                    }
                } else {
                    break
                }

                // Rate limiting between pages
                Thread.sleep(1000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching followers", e)
        }

        return followers
    }

    /**
     * Analyze a single follower for risk
     */
    private fun analyzeFollower(follower: JSONObject): FollowerAnalysis {
        val username = follower.optString("username", "")
        val fullName = follower.optString("full_name", "")
        val pk = follower.optString("pk", null)
        val hasProfilePic = !follower.optBoolean("has_anonymous_profile_picture", true)
        val isPrivate = follower.optBoolean("is_private", false)
        val isVerified = follower.optBoolean("is_verified", false)

        // Verified accounts are safe
        if (isVerified) {
            return FollowerAnalysis(
                username = username,
                fullName = fullName,
                pk = pk,
                riskScore = 0,
                riskLevel = "normal",
                flags = listOf("Verified account"),
                hasProfilePic = hasProfilePic,
                isPrivate = isPrivate,
                isVerified = true,
                estimatedAge = null
            )
        }

        var riskScore = 0
        val flags = mutableListOf<String>()

        // No profile picture
        if (!hasProfilePic) {
            riskScore += 25
            flags.add("No profile picture")
        }

        // No full name
        if (fullName.isBlank()) {
            riskScore += 15
            flags.add("No display name")
        }

        // Username patterns
        if (username.matches(Regex(".*\\d{5,}.*"))) {
            riskScore += 20
            flags.add("Many numbers in username")
        }
        if (username.matches(Regex(".*[a-z]{10,}\\d+"))) {
            riskScore += 15
            flags.add("Bot-like username pattern")
        }
        if (username.count { it == '_' } > 3) {
            riskScore += 10
            flags.add("Many underscores")
        }

        // Scam keywords in name
        val nameLower = fullName.lowercase()
        for (keyword in SCAM_KEYWORDS) {
            if (nameLower.contains(keyword)) {
                riskScore += 30
                flags.add("Suspicious keyword: $keyword")
                break  // Only count once
            }
        }

        // Private account with no pic is extra suspicious
        if (isPrivate && !hasProfilePic) {
            riskScore += 10
            flags.add("Private with no picture")
        }

        // Account age from User ID
        var estimatedAge: String? = null
        if (pk != null) {
            try {
                val id = pk.toLong()
                // Very new accounts (created in last ~6 months)
                if (id > 70_000_000_000L) {
                    riskScore += 15
                    flags.add("Very new account (2023+)")
                    estimatedAge = "< 1 year"
                } else if (id > 60_000_000_000L) {
                    riskScore += 10
                    flags.add("New account (2022-2023)")
                    estimatedAge = "1-2 years"
                } else if (id > 40_000_000_000L) {
                    estimatedAge = "2-4 years"
                } else if (id > 20_000_000_000L) {
                    estimatedAge = "4-6 years"
                } else {
                    estimatedAge = "6+ years"
                }
            } catch (e: Exception) { }
        }

        // Cap at 100
        riskScore = minOf(100, riskScore)

        // Determine risk level
        val riskLevel = when {
            riskScore >= 50 -> "high"
            riskScore >= 30 -> "suspicious"
            else -> "normal"
        }

        return FollowerAnalysis(
            username = username,
            fullName = fullName,
            pk = pk,
            riskScore = riskScore,
            riskLevel = riskLevel,
            flags = flags,
            hasProfilePic = hasProfilePic,
            isPrivate = isPrivate,
            isVerified = false,
            estimatedAge = estimatedAge
        )
    }
}
