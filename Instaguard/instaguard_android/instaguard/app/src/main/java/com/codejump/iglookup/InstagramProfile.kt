package com.codejump.iglookup

data class InstagramProfile(
    val username: String,
    val userId: String? = null,
    val fullName: String? = null,
    val biography: String? = null,
    val externalUrl: String? = null,
    val profilePicUrl: String? = null,
    val profilePicId: String? = null,
    val followers: Int? = null,
    val following: Int? = null,
    val postsCount: Int? = null,
    val isPrivate: Boolean = false,
    val isVerified: Boolean = false,
    val isBusiness: Boolean = false,
    val businessCategory: String? = null,
    val businessEmail: String? = null,
    val businessPhone: String? = null,
    val businessAddress: String? = null,
    
    // Obfuscated info
    var obfuscatedEmail: String? = null,
    var obfuscatedPhone: String? = null,
    var obfuscatedLookupStatus: String? = null,  // Status message for UI
    
    // Phone analysis (enhanced)
    var phoneCountry: String? = null,
    var phoneCountryCode: String? = null,
    var phoneRiskLevel: String? = null,
    var phoneType: String? = null,           // mobile, landline, unknown
    var phoneCarrierHint: String? = null,
    var phoneVisibleDigits: String? = null,
    var phoneFormat: String? = null,          // Expected format pattern
    var phoneOperatorRange: String? = null,   // Operator/region from number range
    
    // Email analysis (enhanced)
    var emailProvider: String? = null,
    var emailProviderConfidence: String? = null,  // high, medium, low
    var emailDomainTld: String? = null,
    var emailUsernameLengthEstimate: String? = null,
    var emailUsernameFirstChar: String? = null,
    var emailUsernameLastChar: String? = null,
    var emailDomainType: String? = null,      // free, business, edu, gov
    var emailSecurityLevel: String? = null,   // high, medium, low
    
    // Additional info from extra APIs
    var publicPhoneNumber: String? = null,    // If publicly visible
    var publicEmail: String? = null,          // If publicly visible
    var cityName: String? = null,
    var accountType: Int? = null,             // 1=personal, 2=business, 3=creator
    
    // Bio analysis
    var bioExtractedEmails: List<String> = emptyList(),
    var bioExtractedPhones: List<String> = emptyList(),
    var bioExtractedUrls: List<String> = emptyList(),
    var bioScamIndicators: List<String> = emptyList(),
    var bioRiskLevel: String? = null,
    
    // Bio quality analysis
    var bioWordCount: Int? = null,
    var bioQualityScore: Int? = null,
    var bioIsWellWritten: Boolean? = null,
    var bioQualityIssues: List<String>? = null,
    
    // Impersonation detection
    var isLikelyImpersonation: Boolean? = null,
    var impersonationOriginalAccount: String? = null,
    var impersonationOriginalFollowers: Int? = null,
    var impersonationWarning: String? = null,
    
    // Account age - Comprehensive estimation
    var waybackDate: String? = null,
    var waybackUrl: String? = null,
    var firstPostDate: String? = null,
    
    // New: Multiple age estimation methods
    var ageUserIdEstimate: String? = null,        // From User ID (most reliable)
    var ageUserIdYears: String? = null,
    var ageMediaIdEstimate: String? = null,       // From Media ID decoding
    var ageProfilePicEstimate: String? = null,    // From Profile Pic ID
    var ageBestEstimate: String? = null,          // Best overall estimate
    var ageBestEstimateYears: String? = null,
    var ageBestEstimateSource: String? = null,    // Which method was used
    var ageConfidenceScore: Int = 0,              // 0-100
    var ageMethodsUsed: List<String> = emptyList(), // All methods that returned data
    
    // Profile picture verification
    var profilePicVerified: Boolean = false,      // Has been checked
    var profilePicIsDefault: Boolean = false,     // Is default Instagram avatar
    var profilePicIsStolen: Boolean = false,      // Found elsewhere (celebrity, stock, etc.)
    var profilePicStolenSource: String? = null,   // Where it was found
    var profilePicMatchCount: Int = 0,            // Number of matches found
    
    // Media edges for analysis
    var mediaEdges: List<MediaEdgeData> = emptyList(),
    
    // Trust
    var timestamp: Long = System.currentTimeMillis(),
    var trustScore: Int = 0,
    var trustDetails: List<TrustDetail> = emptyList(),
    var trustPositiveScore: Int = 0,
    var trustNegativeScore: Int = 0
) {
    fun toJson(): String {
        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
        return gson.toJson(this)
    }
    
    fun getAccountTypeString(): String? {
        return when (accountType) {
            1 -> "Personal"
            2 -> "Business"
            3 -> "Creator"
            else -> null
        }
    }
}

data class TrustDetail(
    val text: String,
    val score: Int,
    val color: String // "green", "yellow", "red", "orange"
)

data class MediaEdgeData(
    val mediaId: String?,
    val timestamp: Long?
)

// Cross-platform search results
data class CrossPlatformResult(
    val platform: String,
    val url: String,
    val exists: Boolean?,  // null = unknown/not checked
    val iconResId: Int
)
