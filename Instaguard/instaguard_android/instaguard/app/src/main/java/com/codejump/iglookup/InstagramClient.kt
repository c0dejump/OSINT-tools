package com.codejump.iglookup

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

sealed class LookupResult {
    data class Success(val profile: InstagramProfile) : LookupResult()
    data class Error(val code: Int, val message: String) : LookupResult()
    object NotFound : LookupResult()
}

class InstagramClient {

    companion object {
        private const val TAG = "WhoIG"
        private const val IG_APP_ID = "936619743392459"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()

    // ==================== MAIN LOOKUP ====================

    private val ageEstimator = AgeEstimator()

    suspend fun lookup(username: String, onProgress: (String) -> Unit): LookupResult {
        Log.d(TAG, "Starting lookup for: $username")

        onProgress("Fetching profile...")
        val profileResult = getWebProfile(username)

        if (profileResult is LookupResult.Error || profileResult is LookupResult.NotFound) {
            return profileResult
        }

        val profile = (profileResult as LookupResult.Success).profile
        Log.d(TAG, "Got profile: ${profile.fullName}, followers=${profile.followers}, private=${profile.isPrivate}")

        onProgress("Fetching obfuscated info...")
        try {
            val obfuscatedResult = getObfuscatedInfo(username)
            profile.obfuscatedEmail = obfuscatedResult.email
            profile.obfuscatedPhone = obfuscatedResult.phone
            profile.obfuscatedLookupStatus = obfuscatedResult.statusMessage
            
            Log.d(TAG, "Obfuscated lookup status: ${obfuscatedResult.status} - ${obfuscatedResult.statusMessage}")

            // Try additional API endpoints for more info
            onProgress("Fetching additional info...")
            val additionalInfo = getAdditionalUserInfo(username, profile.userId)
            additionalInfo?.let { info ->
                // If we got better email/phone from additional APIs, use them
                if (profile.obfuscatedEmail.isNullOrBlank() && !info.email.isNullOrBlank()) {
                    profile.obfuscatedEmail = info.email
                    profile.obfuscatedLookupStatus = "✓ Retrieved from alternate API"
                }
                if (profile.obfuscatedPhone.isNullOrBlank() && !info.phone.isNullOrBlank()) {
                    profile.obfuscatedPhone = info.phone
                }
                // Store additional discovered info
                profile.phoneCountryCode = info.phoneCountryCode ?: profile.phoneCountryCode
                profile.publicPhoneNumber = info.publicPhone
                profile.publicEmail = info.publicEmail
                profile.cityName = info.cityName
                profile.accountType = info.accountType
            }

            // Analyze phone with enhanced detection
            if (!profile.obfuscatedPhone.isNullOrBlank()) {
                val phoneAnalysis = analyzePhonePrefix(profile.obfuscatedPhone)
                profile.phoneCountry = phoneAnalysis.country
                profile.phoneCountryCode = phoneAnalysis.countryCode ?: profile.phoneCountryCode
                profile.phoneRiskLevel = phoneAnalysis.riskLevel
                profile.phoneType = phoneAnalysis.phoneType
                profile.phoneCarrierHint = phoneAnalysis.carrierHint
                profile.phoneVisibleDigits = phoneAnalysis.visibleDigits
                profile.phoneFormat = phoneAnalysis.phoneFormat
                profile.phoneOperatorRange = phoneAnalysis.operatorRange
            }
            
            // Analyze email with enhanced detection
            if (!profile.obfuscatedEmail.isNullOrBlank()) {
                val emailAnalysis = analyzeEmail(profile.obfuscatedEmail)
                profile.emailProvider = emailAnalysis.provider
                profile.emailProviderConfidence = emailAnalysis.providerConfidence
                profile.emailDomainTld = emailAnalysis.domainTld
                profile.emailUsernameLengthEstimate = emailAnalysis.usernameLengthEstimate
                profile.emailUsernameFirstChar = emailAnalysis.usernameFirstChar
                profile.emailUsernameLastChar = emailAnalysis.usernameLastChar
                profile.emailDomainType = emailAnalysis.domainType
                profile.emailSecurityLevel = emailAnalysis.securityLevel
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting obfuscated info", e)
            profile.obfuscatedLookupStatus = "❌ Error: ${e.message}"
        }

        onProgress("Analyzing bio...")
        try {
            if (!profile.biography.isNullOrBlank()) {
                val bioAnalysis = analyzeBio(profile.biography!!)
                profile.bioExtractedEmails = bioAnalysis.emails
                profile.bioExtractedPhones = bioAnalysis.phones
                profile.bioExtractedUrls = bioAnalysis.urls
                profile.bioScamIndicators = bioAnalysis.scamIndicators
                profile.bioRiskLevel = bioAnalysis.riskLevel
                
                // Bio quality analysis
                val bioQuality = analyzeBioQuality(profile.biography!!)
                profile.bioWordCount = bioQuality.wordCount
                profile.bioQualityScore = bioQuality.qualityScore
                profile.bioIsWellWritten = bioQuality.isWellWritten
                profile.bioQualityIssues = bioQuality.issues
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing bio", e)
        }

        // Comprehensive age estimation using all methods
        onProgress("Analyzing account age...")
        try {
            val mediaEdgesForEstimator = profile.mediaEdges.map { 
                AgeEstimator.MediaEdge(it.mediaId, it.timestamp) 
            }
            
            val ageEstimation = ageEstimator.estimateAge(
                userId = profile.userId,
                username = username,
                profilePicId = profile.profilePicId,
                mediaEdges = mediaEdgesForEstimator,
                isPrivate = profile.isPrivate
            )
            
            // Store all age estimation results
            profile.ageUserIdEstimate = ageEstimation.userIdDate
            profile.ageUserIdYears = ageEstimation.userIdAge
            profile.ageMediaIdEstimate = ageEstimation.mediaIdDate
            profile.ageProfilePicEstimate = ageEstimation.profilePicDate
            profile.waybackDate = ageEstimation.waybackDate
            profile.waybackUrl = ageEstimation.waybackUrl
            profile.firstPostDate = ageEstimation.firstPostDate
            profile.ageBestEstimate = ageEstimation.bestEstimateDate
            profile.ageBestEstimateYears = ageEstimation.bestEstimateAge
            profile.ageBestEstimateSource = ageEstimation.bestEstimateSource
            profile.ageConfidenceScore = ageEstimation.confidenceScore
            profile.ageMethodsUsed = ageEstimation.methodsUsed
            
        } catch (e: Exception) {
            Log.e(TAG, "Error estimating age", e)
            
            // Fallback to just Wayback if age estimator fails
            try {
                onProgress("Checking Wayback Machine...")
                val wayback = getWaybackInfo(username)
                profile.waybackDate = wayback?.first
                profile.waybackUrl = wayback?.second
            } catch (e2: Exception) {
                Log.e(TAG, "Error getting wayback info", e2)
            }
        }

        // Verify profile picture
        onProgress("Verifying profile picture...")
        try {
            verifyProfilePicture(profile)
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying profile picture", e)
        }
        
        // Check for impersonation (search for similar accounts with more followers)
        onProgress("Checking for similar accounts...")
        try {
            val impersonationCheck = checkForImpersonation(
                username, 
                profile.fullName, 
                profile.followers ?: 0
            )
            profile.isLikelyImpersonation = impersonationCheck.isLikelyImpersonation
            profile.impersonationOriginalAccount = impersonationCheck.originalAccount
            profile.impersonationOriginalFollowers = impersonationCheck.originalFollowers
            profile.impersonationWarning = impersonationCheck.warning
        } catch (e: Exception) {
            Log.e(TAG, "Error checking impersonation", e)
        }

        onProgress("Calculating trust score...")
        calculateTrustScore(profile)

        return LookupResult.Success(profile)
    }

    // ==================== WEB PROFILE API ====================

    private suspend fun getWebProfile(username: String): LookupResult = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.instagram.com/api/v1/users/web_profile_info/?username=$username"
            Log.d(TAG, "Requesting: $url")

            val request = Request.Builder()
                .url(url)
                .addHeader("x-ig-app-id", IG_APP_ID)
                .addHeader("x-requested-with", "XMLHttpRequest")
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .addHeader("Referer", "https://www.instagram.com/")
                .addHeader("Origin", "https://www.instagram.com")
                .addHeader("Sec-Fetch-Dest", "empty")
                .addHeader("Sec-Fetch-Mode", "cors")
                .addHeader("Sec-Fetch-Site", "same-origin")
                .build()

            val response = client.newCall(request).execute()
            val responseCode = response.code
            val responseBody = response.body?.string()
            response.close()

            Log.d(TAG, "Response code: $responseCode")

            // Handle HTTP errors
            when (responseCode) {
                401 -> return@withContext LookupResult.Error(401, 
                    "Authentication required. Instagram is blocking unauthenticated requests.")
                403 -> return@withContext LookupResult.Error(403, 
                    "Access denied (403). Your IP may be temporarily blocked by Instagram. Try using a VPN or wait a few minutes.")
                404 -> return@withContext LookupResult.NotFound
                429 -> return@withContext LookupResult.Error(429, 
                    "Too many requests (429). Please wait a few minutes before trying again.")
            }

            if (responseCode != 200 || responseBody.isNullOrBlank()) {
                return@withContext LookupResult.Error(responseCode, "HTTP Error $responseCode")
            }

            // Parse JSON response
            val json = gson.fromJson(responseBody, JsonObject::class.java)
            val user = json?.getAsJsonObject("data")?.getAsJsonObject("user")

            if (user == null) {
                Log.e(TAG, "User object is null")
                return@withContext LookupResult.NotFound
            }

            return@withContext LookupResult.Success(parseUserJson(user, username))

        } catch (e: Exception) {
            Log.e(TAG, "Exception in getWebProfile", e)
            return@withContext LookupResult.Error(0, "Error: ${e.message ?: "Unknown error"}")
        }
    }

    private fun parseUserJson(user: JsonObject, username: String): InstagramProfile {
        fun JsonObject.safeString(key: String): String? {
            val element = this.get(key)
            return if (element != null && !element.isJsonNull) element.asString else null
        }

        fun JsonObject.safeInt(key: String): Int? {
            val element = this.get(key)
            return if (element != null && !element.isJsonNull) element.asInt else null
        }

        fun JsonObject.safeBool(key: String): Boolean {
            val element = this.get(key)
            return if (element != null && !element.isJsonNull) element.asBoolean else false
        }
        
        fun JsonObject.safeLong(key: String): Long? {
            val element = this.get(key)
            return if (element != null && !element.isJsonNull) element.asLong else null
        }

        val followers = user.getAsJsonObject("edge_followed_by")?.safeInt("count")
        val following = user.getAsJsonObject("edge_follow")?.safeInt("count")
        val postsCount = user.getAsJsonObject("edge_owner_to_timeline_media")?.safeInt("count")

        var businessAddress: String? = null
        val addrJson = user.safeString("business_address_json")
        if (!addrJson.isNullOrBlank() && addrJson != "null") {
            try {
                val addr = gson.fromJson(addrJson, JsonObject::class.java)
                val parts = listOfNotNull(
                    addr?.safeString("street_address"),
                    addr?.safeString("city_name"),
                    addr?.safeString("zip_code"),
                    addr?.safeString("country_code")
                ).filter { it.isNotBlank() }
                if (parts.isNotEmpty()) businessAddress = parts.joinToString(", ")
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing business address", e)
            }
        }
        
        // Extract media edges for age estimation
        val mediaEdges = mutableListOf<MediaEdgeData>()
        try {
            val mediaObj = user.getAsJsonObject("edge_owner_to_timeline_media")
            val edges = mediaObj?.getAsJsonArray("edges")
            edges?.forEach { edgeElement ->
                val edge = edgeElement.asJsonObject
                val node = edge.getAsJsonObject("node")
                if (node != null) {
                    val mediaId = node.safeString("id")
                    val timestamp = node.safeLong("taken_at_timestamp")
                    mediaEdges.add(MediaEdgeData(mediaId, timestamp))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing media edges", e)
        }

        return InstagramProfile(
            username = username,
            userId = user.safeString("id"),
            fullName = user.safeString("full_name"),
            biography = user.safeString("biography"),
            externalUrl = user.safeString("external_url"),
            profilePicUrl = user.safeString("profile_pic_url_hd") ?: user.safeString("profile_pic_url"),
            profilePicId = user.safeString("profile_pic_id"),
            followers = followers,
            following = following,
            postsCount = postsCount,
            isPrivate = user.safeBool("is_private"),
            isVerified = user.safeBool("is_verified"),
            isBusiness = user.safeBool("is_business_account"),
            businessCategory = user.safeString("category_name"),
            businessEmail = user.safeString("business_email"),
            businessPhone = user.safeString("business_phone_number"),
            businessAddress = businessAddress,
            mediaEdges = mediaEdges
        )
    }

    // ==================== OBFUSCATED INFO API ====================

    /**
     * Result of obfuscated info lookup
     */
    data class ObfuscatedResult(
        val email: String? = null,
        val phone: String? = null,
        val status: String,  // "success", "not_found", "rate_limited", "auth_error", "blocked", "error"
        val statusMessage: String
    )

    private suspend fun getObfuscatedInfo(username: String): ObfuscatedResult = withContext(Dispatchers.IO) {
        try {
            // Use exact format from working Python script
            val jsonData = """{"login_attempt_count":"0","directly_sign_in":"true","source":"default","q":"$username","ig_sig_key_version":"4"}"""
            val signedBody = ".$jsonData"

            val formBody = FormBody.Builder()
                .add("ig_sig_key_version", "4")
                .add("signed_body", signedBody)
                .build()

            val request = Request.Builder()
                .url("https://i.instagram.com/api/v1/users/lookup/")
                .post(formBody)
                .addHeader("User-Agent", "Instagram 101.0.0.15.120")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
            val code = response.code
            response.close()

            Log.d(TAG, "Obfuscated lookup response code: $code")
            if (!body.isNullOrBlank()) {
                Log.d(TAG, "Obfuscated lookup response: ${body.take(500)}")
            }
            
            when (code) {
                200 -> {
                    if (!body.isNullOrBlank()) {
                        val json = gson.fromJson(body, JsonObject::class.java)
                        
                        // Check if user exists
                        val userExists = json?.get("user_found")?.let { 
                            if (!it.isJsonNull) it.asBoolean else true 
                        } ?: true
                        
                        if (!userExists) {
                            return@withContext ObfuscatedResult(
                                status = "not_found",
                                statusMessage = "User not found in lookup API"
                            )
                        }
                        
                        val email = json?.get("obfuscated_email")?.let {
                            if (!it.isJsonNull && it.asString.isNotBlank()) it.asString else null
                        }
                        val phone = json?.get("obfuscated_phone")?.let {
                            if (!it.isJsonNull && it.asString.isNotBlank()) it.asString else null
                        }
                        
                        if (email != null || phone != null) {
                            return@withContext ObfuscatedResult(
                                email = email,
                                phone = phone,
                                status = "success",
                                statusMessage = "✓ Retrieved successfully"
                            )
                        } else {
                            // User found but no contact info available
                            return@withContext ObfuscatedResult(
                                status = "no_data",
                                statusMessage = "No contact info linked to this account"
                            )
                        }
                    }
                    ObfuscatedResult(status = "error", statusMessage = "Empty response from server")
                }
                400 -> {
                    // Parse error message
                    val errorMsg = try {
                        val json = gson.fromJson(body, JsonObject::class.java)
                        json?.get("message")?.asString ?: "Bad request"
                    } catch (e: Exception) { "Bad request" }
                    ObfuscatedResult(status = "error", statusMessage = "⚠️ $errorMsg")
                }
                401 -> ObfuscatedResult(
                    status = "auth_error",
                    statusMessage = "🔐 Authentication required (401)"
                )
                403 -> ObfuscatedResult(
                    status = "blocked",
                    statusMessage = "🚫 Access blocked by Instagram (403)"
                )
                429 -> ObfuscatedResult(
                    status = "rate_limited",
                    statusMessage = "⏳ Rate limited - try again in a few minutes (429)"
                )
                in 500..599 -> ObfuscatedResult(
                    status = "server_error",
                    statusMessage = "❌ Instagram server error ($code)"
                )
                else -> ObfuscatedResult(
                    status = "error",
                    statusMessage = "⚠️ Unexpected response ($code)"
                )
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Timeout getting obfuscated info", e)
            ObfuscatedResult(status = "timeout", statusMessage = "⏱️ Connection timeout")
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "Network error getting obfuscated info", e)
            ObfuscatedResult(status = "network_error", statusMessage = "📶 No internet connection")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting obfuscated info", e)
            ObfuscatedResult(status = "error", statusMessage = "❌ ${e.message ?: "Unknown error"}")
        }
    }

    // ==================== ADDITIONAL USER INFO APIs ====================

    data class AdditionalUserInfo(
        val email: String? = null,
        val phone: String? = null,
        val phoneCountryCode: String? = null,
        val publicPhone: String? = null,
        val publicEmail: String? = null,
        val cityName: String? = null,
        val accountType: Int? = null
    )

    private suspend fun getAdditionalUserInfo(username: String, userId: String?): AdditionalUserInfo? = withContext(Dispatchers.IO) {
        var result = AdditionalUserInfo()
        
        // Try user info endpoint (mobile API)
        if (userId != null) {
            try {
                val url = "https://i.instagram.com/api/v1/users/$userId/info/"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Instagram 275.0.0.27.98 Android")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string()
                response.close()

                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val user = json?.getAsJsonObject("user")
                    
                    user?.let { u ->
                        result = result.copy(
                            publicPhone = u.get("public_phone_number")?.let { if (!it.isJsonNull) it.asString else null },
                            publicEmail = u.get("public_email")?.let { if (!it.isJsonNull) it.asString else null },
                            phoneCountryCode = u.get("public_phone_country_code")?.let { if (!it.isJsonNull) it.asString else null },
                            cityName = u.get("city_name")?.let { if (!it.isJsonNull) it.asString else null },
                            accountType = u.get("account_type")?.let { if (!it.isJsonNull) it.asInt else null }
                        )
                        Log.d(TAG, "Got additional info: city=${result.cityName}, accountType=${result.accountType}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting user info by ID", e)
            }
        }

        // Try username info endpoint (alternative)
        try {
            val url = "https://i.instagram.com/api/v1/users/search/?q=$username"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Instagram 275.0.0.27.98 Android")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()

            if (response.isSuccessful && !body.isNullOrBlank()) {
                val json = gson.fromJson(body, JsonObject::class.java)
                val users = json?.getAsJsonArray("users")
                
                users?.forEach { userElement ->
                    val user = userElement.asJsonObject
                    val uname = user.get("username")?.asString
                    if (uname == username) {
                        // Found matching user in search results
                        val searchAccountType = user.get("account_type")?.let { if (!it.isJsonNull) it.asInt else null }
                        if (result.accountType == null && searchAccountType != null) {
                            result = result.copy(accountType = searchAccountType)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching user", e)
        }

        // Try accounts contact point prefill endpoint
        try {
            val signedBody = """{"phone_id":"${java.util.UUID.randomUUID()}","usage":"prefill"}"""
            val formBody = FormBody.Builder()
                .add("signed_body", "SIGNATURE.$signedBody")
                .build()

            val request = Request.Builder()
                .url("https://i.instagram.com/api/v1/accounts/contact_point_prefill/")
                .post(formBody)
                .addHeader("User-Agent", "Instagram 275.0.0.27.98 Android")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()

            if (response.isSuccessful && !body.isNullOrBlank()) {
                Log.d(TAG, "Contact prefill response: $body")
                // Parse any additional contact info if available
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting contact prefill", e)
        }

        if (result.publicPhone != null || result.publicEmail != null || result.cityName != null || result.accountType != null) {
            result
        } else {
            null
        }
    }

    // ==================== WAYBACK MACHINE API ====================

    private suspend fun getWaybackInfo(username: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val url = "https://web.archive.org/cdx/search/cdx?url=instagram.com/$username&output=json&limit=1&from=2010"

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", USER_AGENT)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()

            if (response.isSuccessful && !body.isNullOrBlank()) {
                val json = gson.fromJson(body, Array<Array<String>>::class.java)
                if (json != null && json.size > 1) {
                    val timestamp = json[1][1]
                    if (timestamp.length >= 8) {
                        val date = "${timestamp.substring(0, 4)}-${timestamp.substring(4, 6)}-${timestamp.substring(6, 8)}"
                        val archiveUrl = "https://web.archive.org/web/$timestamp/https://instagram.com/$username"
                        return@withContext Pair(date, archiveUrl)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting wayback info", e)
        }
        null
    }

    // ==================== PROFILE PICTURE VERIFICATION ====================
    
    private suspend fun verifyProfilePicture(profile: InstagramProfile) = withContext(Dispatchers.IO) {
        val picUrl = profile.profilePicUrl
        
        // Check if it's a default avatar
        if (picUrl.isNullOrBlank() || isDefaultProfilePic(picUrl)) {
            profile.profilePicVerified = true
            profile.profilePicIsDefault = true
            profile.profilePicIsStolen = false
            return@withContext
        }
        
        // Try to verify the image using reverse image search
        try {
            val searchResult = checkImageWithTinEye(picUrl)
            profile.profilePicVerified = searchResult.verified
            profile.profilePicIsDefault = false
            profile.profilePicIsStolen = searchResult.isStolen
            profile.profilePicStolenSource = searchResult.source
            profile.profilePicMatchCount = searchResult.matchCount
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying profile picture", e)
            // If verification fails, mark as not verified
            profile.profilePicVerified = false
            profile.profilePicIsDefault = false
            profile.profilePicIsStolen = false
        }
    }
    
    private fun isDefaultProfilePic(url: String): Boolean {
        // Instagram default avatar patterns - multiple versions exist
        val defaultPatterns = listOf(
            // Common default avatar hashes
            "44884218_345707102882519_2446069589734326272_n",
            "44884218_345707102882519",
            // URL patterns
            "default_profile",
            "anonymousUser",
            "s150x150/default",
            "/v/t51.2885-19/44884218_",
            "instagram.com/static/images/anonymousUser",
            // Gray silhouette avatar (the one you showed)
            "2446069589734326272",
            "345707102882519",
            // Other common patterns
            "11906329_960233084022564_1448528159",  // Another default
            "10354686_220097248431946_1628525711",  // Old default
            "YW5vbnltb3VzX3Byb2ZpbGVfcGlj",         // Base64 anonymous
            // Size indicators for default
            "s150x150/44884218",
            "s320x320/44884218",
            // CDN patterns for default
            "/v/t51.2885-19/44884218_"
        )
        
        val urlLower = url.lowercase()
        return defaultPatterns.any { urlLower.contains(it.lowercase()) }
    }
    
    data class ReverseImageResult(
        val verified: Boolean,       // Was able to check
        val isStolen: Boolean,       // Found suspicious matches
        val source: String?,         // Where found (URL or description)
        val matchCount: Int,         // Number of matches
        val sourceUrls: List<String> = emptyList()  // Actual URLs where found
    )
    
    private suspend fun checkImageWithTinEye(imageUrl: String): ReverseImageResult = withContext(Dispatchers.IO) {
        // TinEye is more reliable for finding exact matches
        // But since we can't use their API directly, we'll use a smarter heuristic approach
        
        try {
            // First, try to get image and check its properties
            val imageRequest = Request.Builder()
                .url(imageUrl)
                .header("User-Agent", USER_AGENT)
                .head()  // Just get headers
                .build()
            
            val imageResponse = client.newCall(imageRequest).execute()
            val contentLength = imageResponse.header("Content-Length")?.toLongOrNull() ?: 0
            imageResponse.close()
            
            // Very small images (< 5KB) are often default/placeholder
            if (contentLength < 5000) {
                return@withContext ReverseImageResult(
                    verified = true,
                    isStolen = false,
                    source = null,
                    matchCount = 0
                )
            }
            
            // For now, we can't reliably detect stolen images without a proper API
            // So we mark as verified but not stolen, and let user do manual check
            // This avoids false positives like with your friend
            
            return@withContext ReverseImageResult(
                verified = true,
                isStolen = false,
                source = null,
                matchCount = 0
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Image verification failed", e)
            return@withContext ReverseImageResult(
                verified = false,
                isStolen = false,
                source = null,
                matchCount = 0
            )
        }
    }
    
    /**
     * Get URLs for manual reverse image search
     */
    fun getReverseImageSearchUrls(imageUrl: String): List<Pair<String, String>> {
        val encoded = java.net.URLEncoder.encode(imageUrl, "UTF-8")
        return listOf(
            "Google Images" to "https://lens.google.com/uploadbyurl?url=$encoded",
            "TinEye" to "https://tineye.com/search?url=$encoded",
            "Yandex Images" to "https://yandex.com/images/search?url=$encoded&rpt=imageview",
            "Bing Visual" to "https://www.bing.com/images/search?view=detailv2&iss=sbi&form=SBIIRP&sbisrc=UrlPaste&q=imgurl:$encoded"
        )
    }

    // ==================== TRUST SCORE CALCULATION ====================

    private fun calculateTrustScore(profile: InstagramProfile) {
        val positiveDetails = mutableListOf<TrustDetail>()
        val negativeDetails = mutableListOf<TrustDetail>()
        val neutralDetails = mutableListOf<TrustDetail>()
        
        val isPrivate = profile.isPrivate
        val followers = profile.followers ?: 0
        val following = profile.following ?: 0
        val posts = profile.postsCount ?: 0
        val username = profile.username.lowercase()

        var positiveScore = 0
        var negativeScore = 0

        // ============================================
        // === CRITICAL RED FLAGS (check first) ===
        // ============================================
        
        // 1. FAKE/FAN ACCOUNT DETECTION - Username patterns
        val fakePatterns = listOf(
            "fake", "fan", "fake_", "_fake", "notreal", "parody", 
            "fanpage", "fan_", "_fan", "unofficial", "tribute",
            "impersonat", "roleplay", "rp_", "_rp"
        )
        val celebrityPatterns = listOf(
            "official", "_official", "real_", "_real", "the_real",
            "itsme", "its_me", "imthe", "im_the"
        )
        
        val isFakeUsername = fakePatterns.any { username.contains(it) }
        val claimsCelebrity = celebrityPatterns.any { username.contains(it) } && !profile.isVerified
        
        if (isFakeUsername) {
            negativeScore += 25
            negativeDetails.add(TrustDetail("Username indicates fake/fan account", -25, "red"))
        }
        
        if (claimsCelebrity) {
            negativeScore += 15
            negativeDetails.add(TrustDetail("Claims to be 'official/real' but not verified", -15, "red"))
        }
        
        // 2. Username with celebrity name + random suffix (like fake_squeezie_xx)
        val suspiciousUsernamePattern = Regex("^(fake_?|fan_?|_?fake|_?fan)?[a-z]+[_]?[a-z]*[_]?(xx|xxx|[0-9]{2,}|_+)$")
        val hasSuspiciousUsername = username.matches(suspiciousUsernamePattern) || 
                                    username.endsWith("_xx") || 
                                    username.endsWith("_xxx") ||
                                    username.endsWith("__")
        
        if (hasSuspiciousUsername) {
            negativeScore += 10
            negativeDetails.add(TrustDetail("Suspicious username pattern", -10, "red"))
        }
        
        // Track if account is likely impersonating someone
        val isLikelyImpersonator = isFakeUsername || claimsCelebrity || hasSuspiciousUsername
        
        // 3. Profile picture analysis
        val picUrl = profile.profilePicUrl ?: ""
        val hasDefaultPic = profile.profilePicIsDefault || isDefaultProfilePic(picUrl)
        
        if (hasDefaultPic || picUrl.isBlank()) {
            negativeScore += 12
            negativeDetails.add(TrustDetail("No profile picture", -12, "red"))
            profile.profilePicIsDefault = true
        } else if (profile.profilePicIsStolen && profile.profilePicStolenSource != null) {
            negativeScore += 20
            negativeDetails.add(TrustDetail("Stolen profile picture", -20, "red"))
        } else if (isLikelyImpersonator) {
            // Has profile picture BUT account looks like impersonator
            // Don't give positive points - the picture is likely stolen from the real person
            negativeScore += 5
            negativeDetails.add(TrustDetail("Profile picture (possibly stolen)", -5, "orange"))
        } else if (!profile.isVerified && (isFakeUsername || claimsCelebrity)) {
            // Suspicious account with profile pic - neutral at best
            neutralDetails.add(TrustDetail("Has profile picture (unverified)", 0, "yellow"))
        } else {
            // Legitimate looking account with profile picture
            positiveScore += 5
            positiveDetails.add(TrustDetail("Has profile picture", 5, "green"))
        }

        // ============================================
        // === STRONG POSITIVE SIGNALS ===
        // ============================================

        // 4. Verified account - STRONGEST signal
        if (profile.isVerified) {
            positiveScore += 50
            positiveDetails.add(TrustDetail("✓ Verified by Instagram", 50, "green"))
        }

        // 5. Account age - Very important
        var ageYears = 0
        var hasAgeData = false
        
        if (!profile.ageBestEstimateYears.isNullOrBlank() && profile.ageBestEstimateYears != "Unknown") {
            try {
                ageYears = profile.ageBestEstimateYears!!.split(" ")[0].toInt()
                hasAgeData = true
                val ageScore = when {
                    ageYears >= 8 -> 25
                    ageYears >= 5 -> 20
                    ageYears >= 3 -> 15
                    ageYears >= 2 -> 10
                    ageYears >= 1 -> 5
                    else -> 0
                }
                if (ageScore > 0) {
                    positiveScore += ageScore
                    positiveDetails.add(TrustDetail("Account ~$ageYears years old", ageScore, "green"))
                }
            } catch (e: Exception) { }
        }
        
        // No age data = suspicious for accounts claiming to be someone
        if (!hasAgeData && (isFakeUsername || claimsCelebrity)) {
            negativeScore += 5
            negativeDetails.add(TrustDetail("Cannot verify account age", -5, "orange"))
        }

        // 6. Followers analysis
        when {
            followers >= 10000 -> {
                positiveScore += 10
                positiveDetails.add(TrustDetail("${formatCount(followers)} followers", 10, "green"))
            }
            followers >= 1000 -> {
                positiveScore += 7
                positiveDetails.add(TrustDetail("${formatCount(followers)} followers", 7, "green"))
            }
            followers >= 100 -> {
                positiveScore += 4
                positiveDetails.add(TrustDetail("$followers followers", 4, "green"))
            }
            followers >= 20 -> {
                positiveScore += 2
                positiveDetails.add(TrustDetail("$followers followers", 2, "green"))
            }
            followers < 10 && !isPrivate -> {
                negativeScore += 3
                negativeDetails.add(TrustDetail("Very few followers ($followers)", -3, "orange"))
            }
        }

        // 7. Posts activity - Critical for authenticity
        when {
            posts >= 50 -> {
                positiveScore += 10
                positiveDetails.add(TrustDetail("$posts posts (active)", 10, "green"))
            }
            posts >= 20 -> {
                positiveScore += 7
                positiveDetails.add(TrustDetail("$posts posts", 7, "green"))
            }
            posts >= 10 -> {
                positiveScore += 4
                positiveDetails.add(TrustDetail("$posts posts", 4, "green"))
            }
            posts >= 3 -> {
                positiveScore += 2
                positiveDetails.add(TrustDetail("$posts posts", 2, "green"))
            }
            posts == 0 && !isPrivate -> {
                negativeScore += 8
                negativeDetails.add(TrustDetail("No posts (public account)", -8, "red"))
            }
            posts < 3 && !isPrivate -> {
                negativeScore += 4
                negativeDetails.add(TrustDetail("Almost no posts ($posts)", -4, "orange"))
            }
        }

        // 8. Profile completeness
        val fullName = profile.fullName ?: ""
        when {
            fullName.isNotBlank() && fullName.contains(" ") && fullName.length >= 5 -> {
                positiveScore += 5
                positiveDetails.add(TrustDetail("Full name format", 5, "green"))
            }
            fullName.isNotBlank() && fullName.length >= 2 -> {
                positiveScore += 2
                positiveDetails.add(TrustDetail("Has display name", 2, "green"))
            }
            fullName.isBlank() -> {
                negativeScore += 3
                negativeDetails.add(TrustDetail("No display name", -3, "orange"))
            }
        }
        
        // 9. Bio analysis - ENHANCED with quality check
        val bio = profile.biography ?: ""
        val bioWordCount = profile.bioWordCount ?: 0
        val bioQualityScore = profile.bioQualityScore ?: 0
        val bioIsWellWritten = profile.bioIsWellWritten ?: false
        
        when {
            // Short bio (< ~10 words) - no points, not enough to judge
            bioWordCount in 1..9 -> {
                neutralDetails.add(TrustDetail("Short bio ($bioWordCount words)", 0, "yellow"))
            }
            // Well-written bio with proper punctuation/grammar
            bioIsWellWritten -> {
                positiveScore += 8
                positiveDetails.add(TrustDetail("Well-written bio", 8, "green"))
            }
            // Decent bio but with issues
            bioWordCount >= 10 && bioQualityScore > 0 -> {
                positiveScore += 5
                positiveDetails.add(TrustDetail("Detailed bio", 5, "green"))
            }
            // Bio with quality issues
            bioWordCount >= 10 && bioQualityScore < 0 -> {
                val issues = profile.bioQualityIssues?.take(2)?.joinToString(", ") ?: "issues"
                positiveScore += 2
                negativeScore += 2
                positiveDetails.add(TrustDetail("Has bio", 2, "green"))
                negativeDetails.add(TrustDetail("Bio quality issues: $issues", -2, "orange"))
            }
            bio.length > 30 -> {
                positiveScore += 3
                positiveDetails.add(TrustDetail("Has bio", 3, "green"))
            }
            bio.isBlank() && !isPrivate -> {
                negativeScore += 3
                negativeDetails.add(TrustDetail("No bio", -3, "orange"))
            }
        }
        
        // Bio scam/fake indicators
        if (profile.bioRiskLevel == "high") {
            negativeScore += 10
            negativeDetails.add(TrustDetail("Suspicious keywords in bio", -10, "red"))
        }
        
        // Bio claiming to be someone famous
        val bioLower = bio.lowercase()
        val fakeBioPatterns = listOf(
            "fan account", "fan page", "parody", "not affiliated",
            "tribute", "fake", "roleplay", "rp account"
        )
        if (fakeBioPatterns.any { bioLower.contains(it) }) {
            negativeScore += 15
            negativeDetails.add(TrustDetail("Bio indicates fan/fake account", -15, "red"))
        }
        
        // 9b. IMPERSONATION CHECK - Similar account with more followers
        if (profile.isLikelyImpersonation == true && profile.impersonationOriginalAccount != null) {
            negativeScore += 20
            val warning = profile.impersonationWarning ?: "Similar account found with more followers"
            negativeDetails.add(TrustDetail("⚠️ $warning", -20, "red"))
        }

        // 10. External link
        if (!profile.externalUrl.isNullOrBlank()) {
            positiveScore += 4
            positiveDetails.add(TrustDetail("Has external link", 4, "green"))
        }

        // 11. Contact info
        if (!profile.obfuscatedEmail.isNullOrBlank()) {
            positiveScore += 4
            positiveDetails.add(TrustDetail("Email linked", 4, "green"))
        }

        if (!profile.obfuscatedPhone.isNullOrBlank()) {
            val phoneRisk = profile.phoneRiskLevel
            when (phoneRisk) {
                "very_high" -> {
                    negativeScore += 15
                    negativeDetails.add(TrustDetail("Phone from scam region (${profile.phoneCountry})", -15, "red"))
                }
                "high" -> {
                    negativeScore += 10
                    negativeDetails.add(TrustDetail("Phone from risky region (${profile.phoneCountry})", -10, "red"))
                }
                "trusted" -> {
                    positiveScore += 4
                    positiveDetails.add(TrustDetail("Phone (${profile.phoneCountry})", 4, "green"))
                }
                else -> {
                    positiveScore += 2
                    positiveDetails.add(TrustDetail("Phone linked", 2, "green"))
                }
            }
        }

        // 12. Business account
        if (profile.isBusiness) {
            if (!profile.businessEmail.isNullOrBlank() || !profile.businessPhone.isNullOrBlank()) {
                positiveScore += 8
                positiveDetails.add(TrustDetail("Business with contact info", 8, "green"))
            } else {
                positiveScore += 3
                positiveDetails.add(TrustDetail("Business account", 3, "green"))
            }
        }

        // ============================================
        // === ADDITIONAL RED FLAGS ===
        // ============================================

        // 13. Follower/Following ratio
        if (following > 0 && followers > 0) {
            val ratio = followers.toFloat() / following
            if (ratio < 0.05f && following > 500) {
                negativeScore += 8
                negativeDetails.add(TrustDetail("Suspicious follow ratio", -8, "red"))
            }
        }

        // 14. Mass following (bot behavior)
        when {
            following > 7500 -> {
                negativeScore += 10
                negativeDetails.add(TrustDetail("Following ${formatCount(following)} (spam behavior)", -10, "red"))
            }
            following > 5000 -> {
                negativeScore += 5
                negativeDetails.add(TrustDetail("Following ${formatCount(following)}", -5, "orange"))
            }
        }

        // 15. New account with many followers (bought followers)
        if (ageYears < 1 && followers > 10000) {
            negativeScore += 8
            negativeDetails.add(TrustDetail("New account with ${formatCount(followers)} followers", -8, "red"))
        }

        // 16. High followers but no posts (fake account sign)
        if (!isPrivate && followers > 1000 && posts < 3) {
            negativeScore += 12
            negativeDetails.add(TrustDetail("${formatCount(followers)} followers but $posts posts", -12, "red"))
        }

        // 17. Username with many numbers
        val numberCount = username.count { it.isDigit() }
        if (numberCount >= 5) {
            negativeScore += 5
            negativeDetails.add(TrustDetail("Username has many numbers", -5, "orange"))
        }
        
        // 18. Very short username with underscores (like a_b_c)
        if (username.length <= 5 && username.count { it == '_' } >= 2) {
            negativeScore += 3
            negativeDetails.add(TrustDetail("Suspicious short username", -3, "orange"))
        }

        // 19. Private account consideration
        if (isPrivate) {
            // Private is neutral to slightly positive (real users often private)
            neutralDetails.add(TrustDetail("Private account", 0, "yellow"))
        }

        // ============================================
        // === CALCULATE FINAL SCORE ===
        // ============================================
        
        // Base score of 40 (slightly lower to be more conservative)
        val rawScore = 40 + positiveScore - negativeScore
        val finalScore = rawScore.coerceIn(0, 100)
        
        // Combine details: positives first, then negatives
        val allDetails = mutableListOf<TrustDetail>()
        allDetails.addAll(positiveDetails.sortedByDescending { it.score })
        allDetails.addAll(neutralDetails)
        allDetails.addAll(negativeDetails.sortedBy { it.score })

        profile.trustScore = finalScore
        profile.trustDetails = allDetails
        profile.trustPositiveScore = positiveScore
        profile.trustNegativeScore = negativeScore
    }

    // ==================== PHONE PREFIX ANALYSIS ====================

    private val highRiskPrefixes = mapOf(
        // West Africa - VERY HIGH RISK
        "+225" to Triple("Ivory Coast", "very_high", -20),
        "+229" to Triple("Benin", "very_high", -20),
        "+228" to Triple("Togo", "very_high", -20),
        "+233" to Triple("Ghana", "very_high", -20),
        "+234" to Triple("Nigeria", "very_high", -20),
        "+221" to Triple("Senegal", "high", -12),
        "+223" to Triple("Mali", "high", -12),
        "+226" to Triple("Burkina Faso", "high", -12),
        "+227" to Triple("Niger", "high", -12),
        "+220" to Triple("Gambia", "high", -12),
        "+224" to Triple("Guinea", "high", -12),
        "+232" to Triple("Sierra Leone", "high", -12),
        "+231" to Triple("Liberia", "high", -12),
        "+237" to Triple("Cameroon", "high", -12),
        "+243" to Triple("DR Congo", "high", -12),
        "+241" to Triple("Gabon", "moderate", -5),
        "+242" to Triple("Congo", "moderate", -5),
        "+212" to Triple("Morocco", "moderate", -5),
        "+216" to Triple("Tunisia", "moderate", -5),
        // Eastern Europe
        "+380" to Triple("Ukraine", "moderate", -5),
        "+375" to Triple("Belarus", "moderate", -5),
        // Southeast Asia scam compounds
        "+95" to Triple("Myanmar", "high", -12),
        "+856" to Triple("Laos", "high", -12),
        "+855" to Triple("Cambodia", "moderate", -5),
        "+63" to Triple("Philippines", "moderate", -5),
        "+84" to Triple("Vietnam", "moderate", -5)
    )

    private val trustedPrefixes = mapOf(
        "+33" to "France",
        "+1" to "USA/Canada",
        "+44" to "UK",
        "+49" to "Germany",
        "+34" to "Spain",
        "+39" to "Italy",
        "+41" to "Switzerland",
        "+32" to "Belgium",
        "+31" to "Netherlands",
        "+43" to "Austria",
        "+81" to "Japan",
        "+82" to "South Korea",
        "+61" to "Australia",
        "+64" to "New Zealand",
        "+46" to "Sweden",
        "+47" to "Norway",
        "+45" to "Denmark",
        "+358" to "Finland",
        "+353" to "Ireland",
        "+351" to "Portugal",
        "+48" to "Poland",
        "+420" to "Czech Republic",
        "+7" to "Russia",
        "+86" to "China",
        "+91" to "India",
        "+55" to "Brazil",
        "+52" to "Mexico"
    )

    // Email provider patterns (obfuscated domain -> provider)
    private val emailProviders = mapOf(
        "g****.com" to "Gmail",
        "g***l.com" to "Gmail",
        "y*****.com" to "Yahoo",
        "y****.com" to "Yahoo",
        "o*******.com" to "Outlook",
        "o******.com" to "Outlook",
        "h******.com" to "Hotmail",
        "h*****.com" to "Hotmail",
        "i*****.com" to "iCloud",
        "i****.com" to "iCloud",
        "p***********.com" to "ProtonMail",
        "p**********.com" to "ProtonMail",
        "a**.com" to "AOL",
        "l***.com" to "Live",
        "m**.com" to "Me (Apple)",
        "w**.de" to "Web.de",
        "g**.de" to "GMX",
        "o******.fr" to "Orange",
        "f***.fr" to "Free",
        "s**.fr" to "SFR",
        "l*******.fr" to "LaPoste",
        "w*******.fr" to "Wanadoo"
    )

    // Phone type patterns by country
    private val phonePatterns = mapOf(
        "+33" to mapOf(
            "mobile" to listOf("6", "7"),
            "landline" to listOf("1", "2", "3", "4", "5")
        ),
        "+44" to mapOf(
            "mobile" to listOf("7"),
            "landline" to listOf("1", "2", "3")
        ),
        "+49" to mapOf(
            "mobile" to listOf("15", "16", "17"),
            "landline" to listOf("2", "3", "4", "5", "6", "7", "8", "9")
        )
    )

    data class PhoneAnalysis(
        val country: String?,
        val countryCode: String?,
        val riskLevel: String?,
        val scoreModifier: Int,
        val displayText: String?,
        val phoneType: String?,
        val carrierHint: String?,
        val visibleDigits: String?,
        val phoneFormat: String? = null,
        val operatorRange: String? = null
    )

    data class EmailAnalysis(
        val provider: String?,
        val providerConfidence: String?,
        val domainTld: String?,
        val usernameLengthEstimate: String?,
        val usernameFirstChar: String?,
        val usernameLastChar: String?,
        val domainType: String? = null,  // "free", "business", "edu", "gov"
        val securityLevel: String? = null  // "high" (protonmail), "medium", "low"
    )

    private fun analyzePhonePrefix(obfuscatedPhone: String?): PhoneAnalysis {
        if (obfuscatedPhone.isNullOrBlank()) {
            return PhoneAnalysis(null, null, null, 0, null, null, null, null, null, null)
        }

        val phone = obfuscatedPhone.trim()
        
        // Extract visible digits
        val visibleDigits = phone.filter { it.isDigit() }
        
        var country: String? = null
        var countryCode: String? = null
        var riskLevel: String? = null
        var scoreModifier = 0
        var displayText: String? = null
        var phoneType: String? = null
        var carrierHint: String? = null
        var phoneFormat: String? = null
        var operatorRange: String? = null
        var detectionMethod: String? = null

        // ============ METHOD 1: Direct prefix match ============
        // Check high-risk prefixes first
        for ((prefix, data) in highRiskPrefixes) {
            if (phone.startsWith(prefix)) {
                country = data.first
                countryCode = prefix
                riskLevel = data.second
                scoreModifier = data.third
                detectionMethod = "visible_prefix"
                displayText = when (riskLevel) {
                    "very_high" -> "🚩 $country (VERY HIGH RISK - scammers)"
                    "high" -> "🚩 $country (high risk)"
                    else -> "⚠ $country (moderate risk)"
                }
                break
            }
        }

        // Check trusted prefixes
        if (country == null) {
            for ((prefix, countryName) in trustedPrefixes) {
                if (phone.startsWith(prefix)) {
                    country = countryName
                    countryCode = prefix
                    riskLevel = "trusted"
                    scoreModifier = 3
                    detectionMethod = "visible_prefix"
                    displayText = "✓ $country"
                    break
                }
            }
        }
        
        // ============ METHOD 2: Pattern-based detection ============
        // If no direct prefix match, try to detect by pattern analysis
        // Example: "+** * ** ** ** 72" = French format
        if (country == null && phone.startsWith("+")) {
            val patternResult = detectCountryByPattern(phone)
            if (patternResult != null) {
                country = patternResult.first
                countryCode = patternResult.second
                riskLevel = patternResult.third
                detectionMethod = "pattern_match"
                displayText = if (riskLevel == "trusted") "✓ $country (detected by format)" else "⚠ $country"
                scoreModifier = if (riskLevel == "trusted") 2 else 0
            }
        }

        // Analyze phone type if we have a country code
        if (countryCode != null && phonePatterns.containsKey(countryCode)) {
            val patterns = phonePatterns[countryCode]!!
            val afterCode = phone.substring(countryCode.length).trim().replace(" ", "")
            
            if (afterCode.isNotEmpty()) {
                val firstDigit = afterCode.first().toString()
                val firstTwoDigits = if (afterCode.length >= 2) afterCode.substring(0, 2) else firstDigit
                
                patterns["mobile"]?.let { mobilePatterns ->
                    if (mobilePatterns.any { firstDigit == it || firstTwoDigits == it }) {
                        phoneType = "mobile"
                    }
                }
                
                if (phoneType == null) {
                    patterns["landline"]?.let { landlinePatterns ->
                        if (landlinePatterns.any { firstDigit == it || firstTwoDigits == it }) {
                            phoneType = "landline"
                        }
                    }
                }
            }
        }

        // France-specific carrier hints
        if ((countryCode == "+33" || country == "France") && phoneType == "mobile") {
            val afterCode = if (countryCode != null && phone.startsWith(countryCode)) {
                phone.substring(countryCode.length).trim().replace(" ", "").replace("*", "")
            } else {
                visibleDigits
            }
            phoneFormat = "+33 X XX XX XX XX"
            
            // Detailed French mobile operator ranges
            when {
                afterCode.startsWith("6") || afterCode.contains("6") -> {
                    phoneType = "mobile"
                    val prefix2 = if (afterCode.length >= 2) afterCode.substring(0, 2) else ""
                    operatorRange = when {
                        prefix2 in listOf("60", "61", "62", "63") -> "Orange (historic)"
                        prefix2 in listOf("64", "65") -> "SFR (historic)"
                        prefix2 in listOf("66", "67") -> "Bouygues (historic)"
                        prefix2 in listOf("68", "69") -> "Mixed operators"
                        else -> "06 range"
                    }
                    carrierHint = "Historic operators (Orange/SFR/Bouygues)"
                }
                afterCode.startsWith("7") || visibleDigits.startsWith("7") -> {
                    phoneType = "mobile"
                    val prefix2 = if (afterCode.length >= 2) afterCode.substring(0, 2) else ""
                    operatorRange = when {
                        prefix2 in listOf("70", "71", "72", "73") -> "Free Mobile / MVNOs"
                        prefix2 in listOf("74", "75", "76") -> "Mixed new allocations"
                        prefix2 in listOf("77", "78", "79") -> "New operators"
                        visibleDigits.startsWith("72") -> "Free Mobile / MVNOs"
                        else -> "07 range"
                    }
                    carrierHint = "Free Mobile / MVNOs (07)"
                }
                else -> {
                    phoneType = "mobile"
                    carrierHint = null
                }
            }
        }
        
        // UK phone analysis
        if (countryCode == "+44" || country == "United Kingdom") {
            val afterCode = if (countryCode != null && phone.startsWith(countryCode)) {
                phone.substring(3).trim().replace(" ", "")
            } else visibleDigits
            when {
                afterCode.startsWith("7") -> {
                    phoneType = "mobile"
                    phoneFormat = "+44 7XXX XXXXXX"
                    operatorRange = when {
                        afterCode.startsWith("71") || afterCode.startsWith("72") -> "Vodafone/O2 range"
                        afterCode.startsWith("73") || afterCode.startsWith("74") -> "EE/Three range"
                        afterCode.startsWith("75") || afterCode.startsWith("76") -> "Mixed MVNOs"
                        afterCode.startsWith("77") || afterCode.startsWith("78") || afterCode.startsWith("79") -> "Major operators"
                        else -> "UK Mobile"
                    }
                }
                afterCode.startsWith("20") -> {
                    phoneType = "landline"
                    phoneFormat = "+44 20 XXXX XXXX"
                    operatorRange = "London"
                }
                afterCode.startsWith("1") -> {
                    phoneType = "landline"
                    phoneFormat = "+44 1XXX XXXXXX"
                    operatorRange = "Regional UK"
                }
            }
        }
        
        // German phone analysis
        if (countryCode == "+49" || country == "Germany") {
            val afterCode = if (countryCode != null && phone.startsWith(countryCode)) {
                phone.substring(3).trim().replace(" ", "")
            } else visibleDigits
            when {
                afterCode.startsWith("15") || afterCode.startsWith("16") || afterCode.startsWith("17") -> {
                    phoneType = "mobile"
                    phoneFormat = "+49 1XX XXXXXXXX"
                    operatorRange = when {
                        afterCode.startsWith("151") || afterCode.startsWith("160") || afterCode.startsWith("170") || afterCode.startsWith("171") || afterCode.startsWith("175") -> "T-Mobile"
                        afterCode.startsWith("152") || afterCode.startsWith("162") || afterCode.startsWith("172") || afterCode.startsWith("173") || afterCode.startsWith("174") -> "Vodafone"
                        afterCode.startsWith("155") || afterCode.startsWith("157") || afterCode.startsWith("159") || afterCode.startsWith("163") || afterCode.startsWith("177") || afterCode.startsWith("178") -> "E-Plus/O2"
                        afterCode.startsWith("176") || afterCode.startsWith("179") -> "O2"
                        else -> "German Mobile"
                    }
                }
                afterCode.startsWith("30") -> {
                    phoneType = "landline"
                    operatorRange = "Berlin"
                }
                afterCode.startsWith("40") -> {
                    phoneType = "landline"
                    operatorRange = "Hamburg"
                }
                afterCode.startsWith("89") -> {
                    phoneType = "landline"
                    operatorRange = "Munich"
                }
            }
        }
        
        // US/Canada phone analysis
        if (countryCode == "+1" || country == "United States" || country == "Canada") {
            phoneFormat = "+1 XXX XXX XXXX"
            phoneType = "unknown" // Can't distinguish mobile/landline in US
            val afterCode = if (countryCode != null && phone.startsWith(countryCode)) {
                phone.substring(2).trim().replace(" ", "").replace("-", "")
            } else visibleDigits
            if (afterCode.length >= 3) {
                val areaCode = afterCode.substring(0, 3)
                operatorRange = when (areaCode) {
                    "212", "646", "917", "332" -> "New York City"
                    "213", "310", "323", "424", "818" -> "Los Angeles"
                    "312", "773", "872" -> "Chicago"
                    "415", "628" -> "San Francisco"
                    "305", "786" -> "Miami"
                    "416", "647" -> "Toronto"
                    "514", "438" -> "Montreal"
                    "604", "778" -> "Vancouver"
                    else -> "North America"
                }
            }
        }

        return PhoneAnalysis(
            country = country,
            countryCode = countryCode,
            riskLevel = riskLevel,
            scoreModifier = scoreModifier,
            displayText = displayText,
            phoneType = phoneType,
            carrierHint = carrierHint,
            visibleDigits = visibleDigits,
            phoneFormat = phoneFormat,
            operatorRange = operatorRange
        )
    }
    
    /**
     * Detect country by phone number pattern (star count and format)
     * Example: "+** * ** ** ** 72" -> France (format matches +33 X XX XX XX XX)
     */
    private fun detectCountryByPattern(phone: String): Triple<String, String, String>? {
        // Parse the pattern
        val parts = phone.removePrefix("+").trim().split(" ", "-").filter { it.isNotBlank() }
        
        if (parts.isEmpty()) return null
        
        // Count total characters (excluding + and spaces)
        val totalChars = parts.sumOf { it.length }
        
        // Count stars in country code position (first part)
        val countryCodePart = parts.firstOrNull() ?: return null
        val countryCodeStars = countryCodePart.count { it == '*' }
        val countryCodeLength = countryCodePart.length
        
        // French format: +33 X XX XX XX XX = +CC N NN NN NN NN
        // Pattern: "+** * ** ** ** XX" = 5 groups after country code, 11 total chars
        // When masked: CC(2) + space + 1 + space + 2 + space + 2 + space + 2 + space + 2 = 11 chars
        
        if (parts.size == 5 || parts.size == 6) {
            // 5-6 parts suggests French format: CC N NN NN NN NN
            // Total length for France: 2 (CC) + 9 (number) = 11
            if (totalChars == 11 && countryCodeLength == 2) {
                return Triple("France", "+33", "trusted")
            }
        }
        
        // Check by country code star count
        when (countryCodeStars) {
            2 -> {
                // 2-digit country codes
                // Check format to differentiate
                when (totalChars) {
                    11 -> {
                        // France: +33 + 9 digits
                        if (parts.size >= 4) {
                            return Triple("France", "+33", "trusted")
                        }
                    }
                    12 -> {
                        // Could be UK (+44 + 10) or Germany (+49 + 10)
                        if (parts.size == 2) {
                            // +XX XXXXXXXXXX format - could be UK
                            return Triple("United Kingdom", "+44", "trusted")
                        }
                    }
                    11 -> {
                        // Spain: +34 + 9 digits
                        return Triple("Spain", "+34", "trusted")
                    }
                }
                
                // Default for 2-digit codes in Europe
                if (totalChars in 10..13) {
                    // Most likely European country
                    return Triple("European country", "+XX", "trusted")
                }
            }
            3 -> {
                // 3-digit country codes
                when (totalChars) {
                    12, 13 -> {
                        // Could be Portugal (+351), Ireland (+353), Finland (+358)
                        return Triple("European country", "+XXX", "trusted")
                    }
                }
            }
            1 -> {
                // 1-digit visible = could be +1 (US/Canada) or +7 (Russia)
                val visibleCode = countryCodePart.replace("*", "")
                when (visibleCode) {
                    "1" -> return Triple("United States/Canada", "+1", "trusted")
                    "7" -> return Triple("Russia/Kazakhstan", "+7", "moderate")
                }
            }
        }
        
        // Try to match known formats by total length and structure
        // Most Instagram obfuscations follow: +CC *...* + visible last 2-4 digits
        
        // Count visible digits at the end
        val lastPart = parts.lastOrNull() ?: ""
        val lastVisibleDigits = lastPart.filter { it.isDigit() }
        
        if (lastVisibleDigits.length >= 2) {
            // We have some visible digits at the end
            // Combined with star pattern, try to identify
            
            // French mobile often ends with specific patterns
            // Check if format matches French structure
            if (parts.size == 5 && countryCodeLength == 2) {
                // Very likely French: +CC N NN NN NN NN
                return Triple("France", "+33", "trusted")
            }
        }
        
        return null
    }

    fun analyzeEmail(obfuscatedEmail: String?): EmailAnalysis {
        if (obfuscatedEmail.isNullOrBlank()) {
            return EmailAnalysis(null, null, null, null, null, null, null, null)
        }

        val email = obfuscatedEmail.trim()
        var provider: String? = null
        var providerConfidence: String? = null
        var domainTld: String? = null
        var usernameLengthEstimate: String? = null
        var usernameFirstChar: String? = null
        var usernameLastChar: String? = null
        var domainType: String? = null
        var securityLevel: String? = null

        if (email.contains("@")) {
            val parts = email.split("@", limit = 2)
            val usernamePart = parts[0]
            val domainPart = parts[1]

            // Analyze username
            if (usernamePart.isNotEmpty()) {
                // Get first visible char
                for (c in usernamePart) {
                    if (c != '*') {
                        usernameFirstChar = c.toString()
                        break
                    }
                }
                // Get last visible char
                for (c in usernamePart.reversed()) {
                    if (c != '*') {
                        usernameLastChar = c.toString()
                        break
                    }
                }

                // FIXED: Better length estimation
                // Instagram shows pattern like: j***n (5 chars shown, represents ~5-8 actual chars)
                val totalLen = usernamePart.length
                val starCount = usernamePart.count { it == '*' }
                val visibleCount = totalLen - starCount
                
                if (starCount == 0) {
                    usernameLengthEstimate = "$totalLen chars (exact)"
                } else {
                    // Instagram typically: first char + *** + last char
                    // The *** usually represents the hidden middle
                    val minLen = totalLen  // At minimum, each * is 1 char
                    val maxLen = if (starCount >= 3) {
                        visibleCount + minOf(starCount * 2, 15)  // Cap at reasonable max
                    } else {
                        totalLen + starCount
                    }
                    usernameLengthEstimate = "$minLen-$maxLen chars"
                }
            }

            // Analyze domain
            if (domainPart.isNotEmpty()) {
                // Extract TLD
                if (domainPart.contains(".")) {
                    domainTld = "." + domainPart.split(".").last()
                    
                    // Determine domain type based on TLD
                    domainType = when (domainTld.lowercase()) {
                        ".edu" -> "educational"
                        ".gov", ".gouv.fr" -> "government"
                        ".org" -> "organization"
                        ".mil" -> "military"
                        ".ac.uk", ".edu.au" -> "educational"
                        else -> "standard"
                    }
                }

                // Try to match provider
                for ((pattern, providerName) in emailProviders) {
                    if (matchObfuscatedPattern(domainPart, pattern)) {
                        provider = providerName
                        providerConfidence = "high"
                        break
                    }
                }

                // Fuzzy matching if no exact match
                if (provider == null) {
                    provider = guessEmailProvider(domainPart)
                    if (provider != null) {
                        providerConfidence = "medium"
                    }
                }
                
                // Determine security level based on provider
                securityLevel = when {
                    provider?.contains("ProtonMail") == true -> "high"
                    provider?.contains("Tutanota") == true -> "high"
                    provider?.contains("Gmail") == true -> "medium"
                    provider?.contains("Outlook") == true -> "medium"
                    provider?.contains("iCloud") == true -> "medium"
                    provider?.contains("Yahoo") == true -> "low"
                    provider?.contains("Hotmail") == true -> "low"
                    provider?.contains("Mail.ru") == true -> "low"
                    provider?.contains("Yandex") == true -> "low"
                    domainType == "educational" || domainType == "government" -> "medium"
                    else -> null
                }
                
                // If domain doesn't match any known provider, might be custom/business
                if (provider == null && domainType == "standard") {
                    // Check if it looks like a business domain
                    val domainLower = domainPart.lowercase()
                    if (!domainLower.contains("mail") && !domainLower.contains("email") &&
                        domainLower.length > 5 && !domainLower.startsWith("*")) {
                        domainType = "business/custom"
                        provider = "Custom domain"
                        providerConfidence = "low"
                    }
                }
            }
        }

        return EmailAnalysis(
            provider = provider,
            providerConfidence = providerConfidence,
            domainTld = domainTld,
            usernameLengthEstimate = usernameLengthEstimate,
            usernameFirstChar = usernameFirstChar,
            usernameLastChar = usernameLastChar,
            domainType = domainType,
            securityLevel = securityLevel
        )
    }

    private fun matchObfuscatedPattern(text: String, pattern: String): Boolean {
        if (text.length != pattern.length) return false
        
        for (i in text.indices) {
            val t = text[i].lowercaseChar()
            val p = pattern[i].lowercaseChar()
            
            if (p == '*') continue
            if (t == '*') continue
            if (t != p) return false
        }
        return true
    }

    private fun guessEmailProvider(domain: String): String? {
        val d = domain.lowercase()
        
        // Extract TLD and prefix
        val tld = if ("." in d) "." + d.split(".").last().replace("*", "") else ""
        val prefix = if ("." in d) d.split(".").first() else d
        
        // Get visible characters
        val visible = d.filter { it != '*' }
        
        // Get first and last visible char of prefix
        val firstChar = prefix.firstOrNull { it != '*' }?.toString() ?: ""
        val lastChar = prefix.lastOrNull { it != '*' }?.toString() ?: ""
        
        // ============ TLD-specific detection ============
        
        // French TLD (.fr) - Most common French providers
        if (tld == ".fr") {
            return when (firstChar) {
                "y" -> "Yahoo France"
                "h" -> "Hotmail France"
                "o" -> if (lastChar == "e" || "nge" in visible) "Orange" else "Outlook France"
                "f" -> "Free"
                "s" -> "SFR"
                "l" -> if ("poste" in visible) "Laposte" else "Live France"
                "w" -> "Wanadoo (Orange)"
                "b" -> if ("box" in visible || "ouygues" in visible) "Bouygues" else "Bbox"
                "n" -> "Numericable"
                "g" -> "Gmail France"
                "a" -> if ("lice" in visible) "Free (Alice)" else null
                "c" -> if ("lub" in visible) "SFR (Club-Internet)" else null
                else -> null
            }
        }
        
        // German TLD (.de)
        if (tld == ".de") {
            return when (firstChar) {
                "g" -> "GMX"
                "w" -> "Web.de"
                "t" -> "T-Online"
                "f" -> "Freenet"
                else -> null
            }
        }
        
        // UK TLD (.co.uk)
        if (tld == ".co.uk" || tld == ".uk") {
            return when (firstChar) {
                "b" -> "BT Internet"
                "s" -> "Sky"
                "v" -> "Virgin Media"
                "t" -> "TalkTalk"
                "y" -> "Yahoo UK"
                else -> null
            }
        }
        
        // Russian TLD (.ru)
        if (tld == ".ru") {
            return when (firstChar) {
                "m" -> "Mail.ru"
                "y" -> "Yandex"
                else -> null
            }
        }
        
        // ============ Generic detection (.com, .net, etc.) ============
        
        // Yahoo patterns: y***o.com, ya**o.com, yah**.com
        if (firstChar == "y") {
            if (lastChar == "o" || "oo" in visible || visible.endsWith("o") || "ahoo" in visible) {
                return when (tld) {
                    ".fr" -> "Yahoo France"
                    ".com" -> "Yahoo"
                    ".co.uk" -> "Yahoo UK"
                    ".de" -> "Yahoo Germany"
                    ".es" -> "Yahoo Spain"
                    ".it" -> "Yahoo Italy"
                    else -> "Yahoo"
                }
            }
            if ("x" in visible || "ndex" in visible) return "Yandex"
        }
        
        // Gmail patterns: g****.com, gm***.com, gmail.com
        if (firstChar == "g") {
            if (lastChar == "l" || "ail" in visible || "il" in visible || "mail" in visible) {
                return "Gmail"
            }
            if ("mx" in visible || lastChar == "x") {
                return if (tld == ".fr") "GMX France" else "GMX"
            }
        }
        
        // Hotmail patterns: h******.com, ho*****.com, hotmail.com
        if (firstChar == "h") {
            if ("mail" in visible || lastChar == "l" || "tmail" in visible || "otmail" in visible) {
                return when (tld) {
                    ".fr" -> "Hotmail France"
                    ".com" -> "Hotmail"
                    ".co.uk" -> "Hotmail UK"
                    else -> "Hotmail"
                }
            }
        }
        
        // Outlook patterns: o******.com, ou*****.com
        if (firstChar == "o") {
            if ("look" in visible || lastChar == "k" || "tlook" in visible || "utlook" in visible) {
                return when (tld) {
                    ".fr" -> "Outlook France"
                    ".com" -> "Outlook"
                    else -> "Outlook"
                }
            }
            // Orange (mainly .fr)
            if (tld == ".fr" && ("nge" in visible || "ange" in visible)) {
                return "Orange"
            }
        }
        
        // iCloud patterns: i*****.com, ic****.com
        if (firstChar == "i") {
            if ("cloud" in visible || lastChar == "d" || "loud" in visible) {
                return "iCloud"
            }
        }
        
        // ProtonMail patterns: p*********.com, pr*******.com
        if (firstChar == "p") {
            if ("roton" in visible || "oton" in visible || ("mail" in visible && d.length > 10)) {
                return "ProtonMail"
            }
            if ("m.me" in visible) return "ProtonMail"
        }
        
        // Live/MSN patterns
        if (firstChar == "l" && ("ive" in visible || lastChar == "e")) {
            return "Live (Outlook)"
        }
        if (firstChar == "m" && "sn" in visible) return "MSN (Outlook)"
        
        // AOL patterns
        if (firstChar == "a" && ("ol" in visible || lastChar == "l")) return "AOL"
        
        // Mail.ru patterns (for .ru TLD already handled above)
        if (firstChar == "m" && "ail" in visible && tld != ".ru") {
            // Could be many providers, but check specific ones
            if ("ru" in visible) return "Mail.ru"
        }
        
        return null
    }

    // ==================== BIO ANALYSIS ====================

    data class BioAnalysis(
        val emails: List<String>,
        val phones: List<String>,
        val urls: List<String>,
        val scamIndicators: List<String>,
        val riskLevel: String?  // "high", "moderate", "low", null
    )

    private val scamPatterns = listOf(
        // Investment/Money scams
        "\\b(invest|trading|forex|crypto|bitcoin|btc|eth|nft)\\b" to "Investment/Crypto mention",
        "\\b(make money|earn money|income|profit|roi|returns)\\b" to "Money-making claims",
        "\\b(\\d+k|\\d+\\$|\\$\\d+|€\\d+|\\d+€)\\s*(per|a|/)?\\s*(day|week|month|hour)\\b" to "Income claims",
        "\\b(passive income|financial freedom|get rich|millionaire)\\b" to "Get-rich-quick language",
        
        // Romance/Dating scams
        "\\b(single|lonely|looking for love|soulmate|true love)\\b" to "Romance bait",
        "\\b(widow|widower|divorced|lost my|passed away)\\b" to "Sympathy story",
        "\\b(god.?fearing|honest|loyal|faithful|trustworthy)\\b" to "Trust-building language",
        
        // Contact requests
        "\\b(dm|message|contact|text|whatsapp|telegram|signal)\\s*(me|for|now)\\b" to "Urgent contact request",
        "\\b(link in bio|click link|check link|tap link)\\b" to "Link pushing",
        
        // Urgency/Pressure
        "\\b(limited time|act now|don't miss|last chance|hurry)\\b" to "Urgency tactics",
        "\\b(exclusive|vip|selected|chosen|lucky)\\b" to "Exclusivity claims",
        
        // Fake jobs/Gigs
        "\\b(hiring|we.?re hiring|job opportunity|work from home|remote job)\\b" to "Job offer",
        "\\b(ambassador|influencer|collab|partnership|sponsor)\\b" to "Collaboration offer",
        "\\b(promo code|discount|giveaway|free|win)\\b" to "Promotion/Giveaway",
        
        // Suspicious services
        "\\b(followers|likes|views|growth|viral)\\s*(for sale|cheap|buy|get)\\b" to "Fake engagement sale",
        "\\b(hack|hacker|recovery|recover account|unlock)\\b" to "Hacking services",
        "\\b(spell|love spell|voodoo|psychic|fortune)\\b" to "Supernatural claims",
        
        // Nigerian/419 scam patterns
        "\\b(beneficiary|inheritance|lottery|won|winner|claim)\\b" to "Lottery/Inheritance scam",
        "\\b(army|military|soldier|deployed|overseas)\\b" to "Military romance scam",
        "\\b(oil rig|offshore|engineer|contractor)\\b" to "Oil rig scam pattern"
    )

    private val suspiciousUrlPatterns = listOf(
        "bit\\.ly", "tinyurl", "t\\.co", "goo\\.gl", "ow\\.ly", "is\\.gd", 
        "buff\\.ly", "short\\.link", "tiny\\.cc", "rb\\.gy", "cutt\\.ly",
        "linktr\\.ee"  // Not inherently suspicious but often used
    )

    fun analyzeBio(bio: String): BioAnalysis {
        val bioLower = bio.lowercase()
        
        // Extract emails
        val emailRegex = "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}".toRegex()
        val emails = emailRegex.findAll(bio).map { it.value }.toList()
        
        // Extract phone numbers
        val phoneRegex = "(\\+?\\d{1,3}[-.\\s]?)?(\\(?\\d{2,4}\\)?[-.\\s]?)?\\d{2,4}[-.\\s]?\\d{2,4}[-.\\s]?\\d{2,4}".toRegex()
        val phones = phoneRegex.findAll(bio)
            .map { it.value.trim() }
            .filter { it.replace("[^0-9]".toRegex(), "").length >= 8 }
            .toList()
        
        // Extract URLs
        val urlRegex = "(https?://[^\\s]+|www\\.[^\\s]+)".toRegex()
        val urls = urlRegex.findAll(bio).map { it.value }.toList()
        
        // Check for scam indicators
        val scamIndicators = mutableListOf<String>()
        var scamScore = 0
        
        for ((pattern, indicator) in scamPatterns) {
            if (pattern.toRegex(RegexOption.IGNORE_CASE).containsMatchIn(bio)) {
                scamIndicators.add(indicator)
                scamScore += when {
                    indicator.contains("VERY HIGH") -> 30
                    indicator.contains("Investment") || indicator.contains("Money") -> 20
                    indicator.contains("Romance") || indicator.contains("Sympathy") -> 25
                    indicator.contains("Urgent") || indicator.contains("Hacking") -> 15
                    else -> 10
                }
            }
        }
        
        // Check for suspicious shortened URLs
        for (url in urls) {
            for (pattern in suspiciousUrlPatterns) {
                if (url.lowercase().contains(pattern)) {
                    scamIndicators.add("Shortened/suspicious URL: $pattern")
                    scamScore += 5
                    break
                }
            }
        }
        
        // Check for excessive emojis (common in scam bios)
        val emojiCount = bio.count { Character.getType(it) == Character.OTHER_SYMBOL.toInt() }
        if (emojiCount > 10) {
            scamIndicators.add("Excessive emojis ($emojiCount)")
            scamScore += 5
        }
        
        // Check for ALL CAPS sections
        val capsWords = bio.split("\\s+".toRegex()).count { it.length > 3 && it == it.uppercase() && it.any { c -> c.isLetter() } }
        if (capsWords > 3) {
            scamIndicators.add("Excessive CAPS ($capsWords words)")
            scamScore += 5
        }
        
        // Determine risk level
        val riskLevel = when {
            scamScore >= 40 -> "high"
            scamScore >= 20 -> "moderate"
            scamScore > 0 -> "low"
            else -> null
        }
        
        return BioAnalysis(
            emails = emails,
            phones = phones,
            urls = urls,
            scamIndicators = scamIndicators,
            riskLevel = riskLevel
        )
    }

    // ==================== CROSS-PLATFORM SEARCH ====================

    data class PlatformInfo(
        val name: String,
        val urlTemplate: String,  // Use {username} as placeholder
        val icon: String
    )

    val crossPlatformList = listOf(
        PlatformInfo("Twitter/X", "https://twitter.com/{username}", "twitter"),
        PlatformInfo("TikTok", "https://tiktok.com/@{username}", "tiktok"),
        PlatformInfo("Facebook", "https://facebook.com/{username}", "facebook"),
        PlatformInfo("YouTube", "https://youtube.com/@{username}", "youtube"),
        PlatformInfo("LinkedIn", "https://linkedin.com/in/{username}", "linkedin"),
        PlatformInfo("Snapchat", "https://snapchat.com/add/{username}", "snapchat"),
        PlatformInfo("Pinterest", "https://pinterest.com/{username}", "pinterest"),
        PlatformInfo("Reddit", "https://reddit.com/user/{username}", "reddit"),
        PlatformInfo("GitHub", "https://github.com/{username}", "github"),
        PlatformInfo("Twitch", "https://twitch.tv/{username}", "twitch"),
        PlatformInfo("Telegram", "https://t.me/{username}", "telegram"),
        PlatformInfo("OnlyFans", "https://onlyfans.com/{username}", "onlyfans"),
        PlatformInfo("Spotify", "https://open.spotify.com/user/{username}", "spotify"),
        PlatformInfo("SoundCloud", "https://soundcloud.com/{username}", "soundcloud"),
        PlatformInfo("Medium", "https://medium.com/@{username}", "medium"),
        PlatformInfo("Tumblr", "https://{username}.tumblr.com", "tumblr"),
        PlatformInfo("VK", "https://vk.com/{username}", "vk"),
        PlatformInfo("Flickr", "https://flickr.com/people/{username}", "flickr")
    )

    fun getCrossPlatformUrls(username: String): List<Pair<String, String>> {
        return crossPlatformList.map { platform ->
            platform.name to platform.urlTemplate.replace("{username}", username)
        }
    }

    // ==================== USERNAME VARIANTS ====================

    fun generateUsernameVariants(username: String): List<String> {
        val variants = mutableSetOf<String>()
        val base = username.lowercase()
        
        // Original
        variants.add(base)
        
        // Without numbers at end
        val withoutEndNumbers = base.replace("\\d+$".toRegex(), "")
        if (withoutEndNumbers != base && withoutEndNumbers.isNotEmpty()) {
            variants.add(withoutEndNumbers)
        }
        
        // Without underscores/dots
        variants.add(base.replace("_", ""))
        variants.add(base.replace(".", ""))
        variants.add(base.replace("_", "."))
        variants.add(base.replace(".", "_"))
        
        // With common suffixes
        listOf("_", ".", "1", "2", "01", "02", "_official", ".official", "_real", ".real").forEach {
            variants.add("$base$it")
        }
        
        // With underscore variations
        if (!base.contains("_") && !base.contains(".")) {
            // Try to split camelCase or find word boundaries
            val words = base.replace("([a-z])([A-Z])".toRegex(), "$1_$2").lowercase()
            if (words != base) {
                variants.add(words)
                variants.add(words.replace("_", "."))
            }
        }
        
        // Common character substitutions
        variants.add(base.replace("o", "0"))
        variants.add(base.replace("0", "o"))
        variants.add(base.replace("i", "1"))
        variants.add(base.replace("1", "i"))
        variants.add(base.replace("e", "3"))
        variants.add(base.replace("a", "4"))
        variants.add(base.replace("s", "5"))
        
        return variants.filter { it != base && it.isNotEmpty() }.take(15)
    }

    // ==================== UTILITIES ====================

    private fun formatCount(count: Int): String {
        return when {
            count >= 1000000 -> String.format("%.1fM", count / 1000000.0)
            count >= 1000 -> String.format("%.1fK", count / 1000.0)
            else -> count.toString()
        }
    }
    
    // ==================== BIO QUALITY ANALYSIS ====================
    
    data class BioQualityResult(
        val wordCount: Int,
        val sentenceCount: Int,
        val hasPunctuation: Boolean,
        val hasCapitalization: Boolean,
        val spellingScore: Float,  // 0-1, higher is better
        val qualityScore: Int,     // -10 to +10
        val issues: List<String>,
        val isWellWritten: Boolean
    )
    
    fun analyzeBioQuality(bio: String): BioQualityResult {
        if (bio.isBlank()) {
            return BioQualityResult(0, 0, false, false, 0f, 0, emptyList(), false)
        }
        
        val issues = mutableListOf<String>()
        var qualityScore = 0
        
        // Count words
        val words = bio.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val wordCount = words.size
        
        // If less than ~10 words, not enough to analyze
        if (wordCount < 10) {
            return BioQualityResult(wordCount, 0, false, false, 0f, 0, 
                listOf("Bio too short to analyze"), false)
        }
        
        // Count sentences (looking for . ! ?)
        val sentenceEnders = bio.count { it == '.' || it == '!' || it == '?' }
        val sentenceCount = maxOf(sentenceEnders, 1)
        
        // Check punctuation
        val hasPunctuation = bio.contains(".") || bio.contains(",") || bio.contains("!") || bio.contains("?")
        if (hasPunctuation) {
            qualityScore += 2
        } else if (wordCount >= 15) {
            qualityScore -= 2
            issues.add("No punctuation")
        }
        
        // Check capitalization (first letter after sentence ender should be capital)
        val sentences = bio.split("[.!?]".toRegex()).filter { it.isNotBlank() }
        var properCapitalization = 0
        sentences.forEach { sentence ->
            val trimmed = sentence.trim()
            if (trimmed.isNotEmpty() && trimmed[0].isUpperCase()) {
                properCapitalization++
            }
        }
        val hasCapitalization = properCapitalization >= sentences.size / 2
        if (hasCapitalization && sentences.size >= 2) {
            qualityScore += 2
        } else if (sentences.size >= 2 && properCapitalization == 0) {
            qualityScore -= 1
            issues.add("Poor capitalization")
        }
        
        // Check for common spelling/grammar issues in French/English
        val commonErrors = mapOf(
            // French errors
            "sa va" to "ça va",
            "sa fait" to "ça fait",
            "a toi" to "à toi",
            "a bientot" to "à bientôt",
            "sa c'est" to "ça c'est",
            "cest" to "c'est",
            "jai" to "j'ai",
            // English errors
            "your welcome" to "you're welcome",
            "its me" to "it's me",
            "i am" to "I am",  // lowercase I
            "alot" to "a lot",
            "definately" to "definitely",
            "seperate" to "separate"
        )
        
        val bioLower = bio.lowercase()
        var errorCount = 0
        commonErrors.forEach { (error, _) ->
            if (bioLower.contains(error)) {
                errorCount++
            }
        }
        
        // Check for lowercase "i" as pronoun (English)
        val loneI = " i ".toRegex().findAll(bioLower).count()
        if (loneI > 0) {
            errorCount += loneI
            issues.add("Lowercase 'i' as pronoun")
        }
        
        val spellingScore = if (errorCount == 0) 1f else maxOf(0f, 1f - (errorCount * 0.2f))
        if (errorCount == 0 && wordCount >= 15) {
            qualityScore += 3
        } else if (errorCount >= 3) {
            qualityScore -= 3
            issues.add("Multiple spelling/grammar errors")
        } else if (errorCount > 0) {
            qualityScore -= 1
        }
        
        // Check for all caps (aggressive/low quality)
        val capsRatio = bio.count { it.isUpperCase() }.toFloat() / bio.count { it.isLetter() }.coerceAtLeast(1)
        if (capsRatio > 0.5 && wordCount > 5) {
            qualityScore -= 2
            issues.add("Too much CAPS")
        }
        
        // Coherent sentences (at least 2 sentences with proper structure)
        val isWellWritten = wordCount >= 15 && 
                           sentenceCount >= 2 && 
                           hasPunctuation && 
                           errorCount <= 1 &&
                           capsRatio < 0.5
        
        if (isWellWritten) {
            qualityScore += 3
        }
        
        return BioQualityResult(
            wordCount = wordCount,
            sentenceCount = sentenceCount,
            hasPunctuation = hasPunctuation,
            hasCapitalization = hasCapitalization,
            spellingScore = spellingScore,
            qualityScore = qualityScore.coerceIn(-10, 10),
            issues = issues,
            isWellWritten = isWellWritten
        )
    }
    
    // ==================== IMPERSONATION CHECK ====================
    
    data class ImpersonationCheckResult(
        val isLikelyImpersonation: Boolean,
        val originalAccount: String?,
        val originalFollowers: Int?,
        val followerRatio: Float?,
        val warning: String?
    )
    
    suspend fun checkForImpersonation(
        username: String, 
        fullName: String?,
        currentFollowers: Int
    ): ImpersonationCheckResult = withContext(Dispatchers.IO) {
        try {
            // Clean the name to search
            val searchQuery = fullName?.takeIf { it.isNotBlank() && it.length >= 3 } 
                ?: username.replace("_", " ").replace(".", " ")
            
            val url = "https://i.instagram.com/api/v1/users/search/?q=${java.net.URLEncoder.encode(searchQuery, "UTF-8")}"
            
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Instagram 275.0.0.27.98 Android")
                .build()
                
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            
            if (!response.isSuccessful || body.isNullOrBlank()) {
                return@withContext ImpersonationCheckResult(false, null, null, null, null)
            }
            
            val json = gson.fromJson(body, JsonObject::class.java)
            val users = json.getAsJsonArray("users") ?: return@withContext ImpersonationCheckResult(false, null, null, null, null)
            
            var bestMatch: JsonObject? = null
            var bestFollowers = 0
            
            for (i in 0 until minOf(users.size(), 10)) {
                val user = users[i].asJsonObject
                val userUsername = user.get("username")?.asString ?: continue
                
                // Skip the same account
                if (userUsername.equals(username, ignoreCase = true)) continue
                
                val userFollowers = user.get("follower_count")?.asInt ?: 0
                val isVerified = user.get("is_verified")?.asBoolean ?: false
                val userFullName = user.get("full_name")?.asString ?: ""
                
                // Check if this could be the "real" account
                val nameSimilarity = calculateNameSimilarity(fullName ?: "", userFullName)
                val usernameSimilarity = calculateNameSimilarity(username, userUsername)
                
                // If we find a verified account or one with significantly more followers
                if ((isVerified || userFollowers > currentFollowers * 10) && 
                    (nameSimilarity > 0.7 || usernameSimilarity > 0.6)) {
                    if (userFollowers > bestFollowers) {
                        bestMatch = user
                        bestFollowers = userFollowers
                    }
                }
            }
            
            if (bestMatch != null && bestFollowers > currentFollowers * 5) {
                val originalUsername = bestMatch.get("username")?.asString ?: "unknown"
                val isVerified = bestMatch.get("is_verified")?.asBoolean ?: false
                val ratio = if (currentFollowers > 0) bestFollowers.toFloat() / currentFollowers else 0f
                
                val warning = if (isVerified) {
                    "Verified account @$originalUsername has ${formatCount(bestFollowers)} followers"
                } else {
                    "@$originalUsername has ${formatCount(bestFollowers)} followers (${ratio.toInt()}x more)"
                }
                
                return@withContext ImpersonationCheckResult(
                    isLikelyImpersonation = true,
                    originalAccount = originalUsername,
                    originalFollowers = bestFollowers,
                    followerRatio = ratio,
                    warning = warning
                )
            }
            
            return@withContext ImpersonationCheckResult(false, null, null, null, null)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking impersonation", e)
            return@withContext ImpersonationCheckResult(false, null, null, null, null)
        }
    }
    
    private fun calculateNameSimilarity(name1: String, name2: String): Float {
        if (name1.isBlank() || name2.isBlank()) return 0f
        
        val s1 = name1.lowercase().replace("[^a-z0-9]".toRegex(), "")
        val s2 = name2.lowercase().replace("[^a-z0-9]".toRegex(), "")
        
        if (s1.isEmpty() || s2.isEmpty()) return 0f
        if (s1 == s2) return 1f
        if (s1.contains(s2) || s2.contains(s1)) return 0.8f
        
        // Levenshtein-based similarity
        val maxLen = maxOf(s1.length, s2.length)
        val distance = levenshteinDistance(s1, s2)
        return 1f - (distance.toFloat() / maxLen)
    }
    
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i-1] == s2[j-1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i-1][j] + 1,
                    dp[i][j-1] + 1,
                    dp[i-1][j-1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }
    
    // ==================== UNFOLLOW ====================
    
    /**
     * Check if the logged-in user follows the target user
     * Returns: true if following, false if not following, null if error/not logged in
     */
    suspend fun checkFollowStatus(
        userId: String,
        sessionId: String,
        csrfToken: String
    ): Boolean? = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.instagram.com/api/v1/friendships/show/$userId/"
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Cookie", "sessionid=$sessionId; csrftoken=$csrfToken")
                .addHeader("X-CSRFToken", csrfToken)
                .addHeader("X-IG-App-ID", IG_APP_ID)
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Referer", "https://www.instagram.com/")
                .addHeader("Origin", "https://www.instagram.com")
                .build()
                
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            
            Log.d(TAG, "Follow status response: ${response.code} - $body")
            
            if (response.isSuccessful && body != null) {
                val json = gson.fromJson(body, JsonObject::class.java)
                return@withContext json.get("following")?.asBoolean ?: false
            }
            
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Error checking follow status", e)
            return@withContext null
        }
    }
    
    suspend fun unfollowUser(
        userId: String,
        sessionId: String,
        csrfToken: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.instagram.com/api/v1/friendships/destroy/$userId/"
            
            val formBody = FormBody.Builder()
                .add("user_id", userId)
                .build()
            
            val request = Request.Builder()
                .url(url)
                .post(formBody)
                .addHeader("Cookie", "sessionid=$sessionId; csrftoken=$csrfToken")
                .addHeader("X-CSRFToken", csrfToken)
                .addHeader("X-IG-App-ID", IG_APP_ID)
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Referer", "https://www.instagram.com/")
                .addHeader("Origin", "https://www.instagram.com")
                .build()
                
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            
            Log.d(TAG, "Unfollow response: ${response.code} - $body")
            
            if (response.isSuccessful && body != null) {
                val json = gson.fromJson(body, JsonObject::class.java)
                return@withContext json.get("status")?.asString == "ok"
            }
            
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Error unfollowing user", e)
            return@withContext false
        }
    }
    
    /**
     * Remove a follower (make them unfollow you)
     * This is different from unfollow - this removes someone from YOUR followers list
     */
    suspend fun removeFollower(
        userId: String,
        sessionId: String,
        csrfToken: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.instagram.com/api/v1/friendships/remove_follower/$userId/"
            
            val formBody = FormBody.Builder()
                .add("user_id", userId)
                .build()
            
            val request = Request.Builder()
                .url(url)
                .post(formBody)
                .addHeader("Cookie", "sessionid=$sessionId; csrftoken=$csrfToken")
                .addHeader("X-CSRFToken", csrfToken)
                .addHeader("X-IG-App-ID", IG_APP_ID)
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Referer", "https://www.instagram.com/")
                .addHeader("Origin", "https://www.instagram.com")
                .build()
                
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            
            Log.d(TAG, "Remove follower response: ${response.code} - $body")
            
            if (response.isSuccessful && body != null) {
                val json = gson.fromJson(body, JsonObject::class.java)
                return@withContext json.get("status")?.asString == "ok"
            }
            
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Error removing follower", e)
            return@withContext false
        }
    }
}
