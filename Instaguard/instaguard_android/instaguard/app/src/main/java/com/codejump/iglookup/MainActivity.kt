package com.codejump.iglookup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var usernameInput: EditText
    private lateinit var lookupButton: Button
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var loadingCard: CardView
    private lateinit var loadingText: TextView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var resultsContainer: LinearLayout
    private lateinit var historyContainer: LinearLayout
    private lateinit var historyList: LinearLayout
    private lateinit var emptyState: LinearLayout

    // Profile header views
    private lateinit var profileCard: CardView
    private lateinit var profileImage: ImageView
    private lateinit var profileName: TextView
    private lateinit var profileUsername: TextView
    private lateinit var profileBadges: LinearLayout
    private lateinit var profileBio: TextView
    private lateinit var profileWebsite: TextView

    // Stats views
    private lateinit var statsCard: CardView
    private lateinit var followersCount: TextView
    private lateinit var followingCount: TextView
    private lateinit var postsCount: TextView

    // Section cards
    private lateinit var businessCard: CardView
    private lateinit var businessContent: LinearLayout
    private lateinit var obfuscatedCard: CardView
    private lateinit var obfuscatedContent: LinearLayout
    private lateinit var ageCard: CardView
    private lateinit var ageContent: LinearLayout
    private lateinit var trustCard: CardView
    private lateinit var trustTitle: TextView
    private lateinit var trustBar: TextView
    private lateinit var trustScoreText: TextView
    private lateinit var trustVerdict: TextView
    private lateinit var trustDetails: LinearLayout
    
    // New OSINT cards
    private lateinit var bioAnalysisCard: CardView
    private lateinit var bioAnalysisTitle: TextView
    private lateinit var bioAnalysisContent: LinearLayout
    private lateinit var reverseImageCard: CardView
    private lateinit var reverseImageContent: LinearLayout
    private lateinit var crossPlatformCard: CardView
    private lateinit var crossPlatformContent: LinearLayout
    
    // Scan followers views
    private lateinit var scanFollowersCard: CardView
    private lateinit var scanFollowersButton: Button
    private lateinit var loginStatus: TextView
    private lateinit var scanResultsContainer: LinearLayout
    
    // Unfollow and impersonation
    private lateinit var unfollowButton: Button
    private lateinit var removeFollowerButton: Button
    private lateinit var impersonationWarning: TextView

    private val client = InstagramClient()
    private val followersScanner = FollowersScanner()
    private lateinit var historyManager: HistoryManager
    private var currentProfile: InstagramProfile? = null
    private var lastUsername: String = ""
    
    // SharedPreferences keys for session
    private val PREFS_NAME = "WhoIGPrefs"
    private val KEY_SESSION_ID = "sessionId"
    private val KEY_CSRF_TOKEN = "csrfToken"
    private val KEY_USER_ID = "userId"
    private val KEY_USERNAME = "username"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        historyManager = HistoryManager(this)
        initViews()
        setupToolbar()
        setupListeners()
        restoreSession()
        showHistory()
    }
    
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar
    private var isShowingResults = false

    private fun setupToolbar() {
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        updateToolbarForState()
    }
    
    private fun updateToolbarForState() {
        if (isShowingResults) {
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_arrow_back)
            supportActionBar?.title = "@$lastUsername"
        } else {
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
            supportActionBar?.title = getString(R.string.app_name)
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        return if (isShowingResults) {
            goBackToHistory()
            true
        } else {
            super.onSupportNavigateUp()
        }
    }
    
    override fun onBackPressed() {
        if (isShowingResults) {
            goBackToHistory()
        } else {
            super.onBackPressed()
        }
    }
    
    private fun goBackToHistory() {
        isShowingResults = false
        updateToolbarForState()
        hideResults()
        showHistory()
        currentProfile = null
    }

    private fun initViews() {
        usernameInput = findViewById(R.id.username_input)
        lookupButton = findViewById(R.id.lookup_button)
        swipeRefresh = findViewById(R.id.swipe_refresh)
        loadingCard = findViewById(R.id.loading_card)
        loadingText = findViewById(R.id.loading_text)
        loadingSpinner = findViewById(R.id.loading_spinner)
        resultsContainer = findViewById(R.id.results_container)
        historyContainer = findViewById(R.id.history_container)
        historyList = findViewById(R.id.history_list)
        emptyState = findViewById(R.id.empty_state)

        profileCard = findViewById(R.id.profile_card)
        profileImage = findViewById(R.id.profile_image)
        profileName = findViewById(R.id.profile_name)
        profileUsername = findViewById(R.id.profile_username)
        profileBadges = findViewById(R.id.profile_badges)
        profileBio = findViewById(R.id.profile_bio)
        profileWebsite = findViewById(R.id.profile_website)

        statsCard = findViewById(R.id.stats_card)
        followersCount = findViewById(R.id.followers_count)
        followingCount = findViewById(R.id.following_count)
        postsCount = findViewById(R.id.posts_count)

        businessCard = findViewById(R.id.business_card)
        businessContent = findViewById(R.id.business_content)
        obfuscatedCard = findViewById(R.id.obfuscated_card)
        obfuscatedContent = findViewById(R.id.obfuscated_content)
        ageCard = findViewById(R.id.age_card)
        ageContent = findViewById(R.id.age_content)
        trustCard = findViewById(R.id.trust_card)
        trustTitle = findViewById(R.id.trust_title)
        trustBar = findViewById(R.id.trust_bar)
        trustScoreText = findViewById(R.id.trust_score)
        trustVerdict = findViewById(R.id.trust_verdict)
        trustDetails = findViewById(R.id.trust_details)
        
        // New OSINT cards
        bioAnalysisCard = findViewById(R.id.bio_analysis_card)
        bioAnalysisTitle = findViewById(R.id.bio_analysis_title)
        bioAnalysisContent = findViewById(R.id.bio_analysis_content)
        reverseImageCard = findViewById(R.id.reverse_image_card)
        reverseImageContent = findViewById(R.id.reverse_image_content)
        crossPlatformCard = findViewById(R.id.cross_platform_card)
        crossPlatformContent = findViewById(R.id.cross_platform_content)
        
        // Scan followers views
        scanFollowersCard = findViewById(R.id.scan_followers_card)
        scanFollowersButton = findViewById(R.id.scan_followers_button)
        loginStatus = findViewById(R.id.login_status)
        scanResultsContainer = findViewById(R.id.scan_results_container)
        
        // Unfollow button and impersonation warning
        unfollowButton = findViewById(R.id.unfollow_button)
        removeFollowerButton = findViewById(R.id.remove_follower_button)
        impersonationWarning = findViewById(R.id.impersonation_warning)
    }

    private fun setupListeners() {
        lookupButton.setOnClickListener {
            val username = usernameInput.text.toString().trim().removePrefix("@")
            if (username.isNotEmpty()) {
                hideKeyboard()
                lookup(username)
            } else {
                showSnackbar("Please enter a username")
            }
        }

        usernameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                lookupButton.performClick()
                true
            } else false
        }
        
        // Scan followers button
        scanFollowersButton.setOnClickListener {
            if (followersScanner.isLoggedIn()) {
                // Check if we have cached results
                val cachedResult = historyManager.getCachedScanResult(maxAgeHours = 24)
                if (cachedResult != null) {
                    val ageMinutes = historyManager.getScanResultAgeMinutes()
                    val ageText = when {
                        ageMinutes < 60 -> "${ageMinutes}min"
                        ageMinutes < 1440 -> "${ageMinutes / 60}h"
                        else -> "${ageMinutes / 1440}d"
                    }
                    
                    AlertDialog.Builder(this, R.style.AlertDialogTheme)
                        .setTitle("Followers Scan")
                        .setMessage("You have a scan from $ageText ago.\n\n• ${cachedResult.totalScanned} followers scanned\n• ${cachedResult.highRiskCount} high risk\n• ${cachedResult.suspiciousCount} suspicious")
                        .setPositiveButton("View Results") { _, _ ->
                            startFollowersScan(forceRefresh = false)
                        }
                        .setNegativeButton("New Scan") { _, _ ->
                            startFollowersScan(forceRefresh = true)
                        }
                        .setNeutralButton("Cancel", null)
                        .show()
                } else {
                    startFollowersScan()
                }
            } else {
                // Not logged in - check if we have cached results anyway
                val cachedResult = historyManager.getCachedScanResult(maxAgeHours = 168) // 7 days
                if (cachedResult != null) {
                    val ageMinutes = historyManager.getScanResultAgeMinutes()
                    val ageText = when {
                        ageMinutes < 60 -> "${ageMinutes}min"
                        ageMinutes < 1440 -> "${ageMinutes / 60}h"
                        else -> "${ageMinutes / 1440}d"
                    }
                    
                    AlertDialog.Builder(this, R.style.AlertDialogTheme)
                        .setTitle("View Previous Scan?")
                        .setMessage("You have a scan from $ageText ago.\n\nLogin to perform a new scan, or view previous results.")
                        .setPositiveButton("View Results") { _, _ ->
                            displayScanResults(cachedResult)
                            showSnackbar("📦 Loaded from cache ($ageText ago)")
                        }
                        .setNegativeButton("Login") { _, _ ->
                            showLoginDialog()
                        }
                        .setNeutralButton("Cancel", null)
                        .show()
                } else {
                    showLoginDialog()
                }
            }
        }

        // Disable swipe refresh when showing results to avoid accidental re-fetch
        swipeRefresh.setOnRefreshListener {
            // Only refresh history, never re-fetch current profile
            swipeRefresh.isRefreshing = false
            if (!isShowingResults) {
                showHistory()
            }
        }

        swipeRefresh.setColorSchemeColors(getColor(R.color.cyan))
        
        // Unfollow button listener
        unfollowButton.setOnClickListener {
            currentProfile?.let { profile ->
                showUnfollowConfirmDialog(profile)
            }
        }
        
        // Remove follower button listener
        removeFollowerButton.setOnClickListener {
            currentProfile?.let { profile ->
                showRemoveFollowerConfirmDialogForProfile(profile)
            }
        }
    }

    private fun lookup(username: String, forceRefresh: Boolean = false) {
        // Check if we already have this profile in history (avoid re-fetching)
        if (!forceRefresh) {
            val cached = historyManager.getFromHistory(username)
            if (cached != null) {
                // Use cached version if less than 1 hour old
                val ageMs = System.currentTimeMillis() - cached.timestamp
                val oneHourMs = 60 * 60 * 1000L
                if (ageMs < oneHourMs) {
                    lastUsername = username
                    currentProfile = cached
                    // Hide scan results if showing
                    scanResultsContainer.visibility = View.GONE
                    scanResultsContainer.removeAllViews()
                    hideHistory()
                    displayResults(cached)
                    showSnackbar("📦 Loaded from cache (${ageMs / 60000}min ago)")
                    return
                }
            }
        }
        
        lastUsername = username
        lookupButton.isEnabled = false
        showLoading(true)
        // Hide scan results
        scanResultsContainer.visibility = View.GONE
        scanResultsContainer.removeAllViews()
        hideResults()
        hideHistory()

        lifecycleScope.launch {
            try {
                val result = client.lookup(username) { status ->
                    runOnUiThread { loadingText.text = status }
                }

                when (result) {
                    is LookupResult.Success -> {
                        currentProfile = result.profile
                        historyManager.addToHistory(result.profile)
                        displayResults(result.profile)
                    }
                    is LookupResult.NotFound -> {
                        showSnackbar("❌ User not found: @$username")
                        showHistory()
                    }
                    is LookupResult.Error -> {
                        showErrorDialog(result.code, result.message)
                        showHistory()
                    }
                }
            } catch (e: Exception) {
                showSnackbar("Error: ${e.message}")
                showHistory()
            } finally {
                runOnUiThread {
                    lookupButton.isEnabled = true
                    showLoading(false)
                    swipeRefresh.isRefreshing = false
                }
            }
        }
    }
    
    private fun showErrorDialog(code: Int, message: String) {
        runOnUiThread {
            val title = when (code) {
                401 -> "🔒 Authentication Required"
                403 -> "🚫 Access Denied"
                429 -> "⏳ Rate Limited"
                else -> "❌ Error"
            }
            
            androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_IGLookup_Dialog)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .setNeutralButton("Retry") { _, _ ->
                    usernameInput.text?.toString()?.trim()?.let { lookup(it) }
                }
                .show()
        }
    }

    private fun displayResults(profile: InstagramProfile) {
        runOnUiThread {
            isShowingResults = true
            updateToolbarForState()
            supportActionBar?.title = "@${profile.username}"
            
            // Hide scan results if showing
            scanResultsContainer.visibility = View.GONE
            scanResultsContainer.removeAllViews()
            
            resultsContainer.visibility = View.VISIBLE
            
            // Animate cards
            val fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in)
            val slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up)

            // Profile header
            profileCard.visibility = View.VISIBLE
            profileCard.startAnimation(fadeIn)
            
            // Load profile image
            Glide.with(this)
                .load(profile.profilePicUrl)
                .transform(CircleCrop())
                .placeholder(R.drawable.ic_profile_placeholder)
                .error(R.drawable.ic_profile_placeholder)
                .into(profileImage)

            profileImage.setOnClickListener {
                profile.profilePicUrl?.let { url -> openUrl(url) }
            }

            profileName.text = profile.fullName ?: profile.username
            profileUsername.text = "@${profile.username}"
            profileUsername.setOnClickListener { 
                openUrl("https://instagram.com/${profile.username}")
            }

            // Badges
            profileBadges.removeAllViews()
            addBadge(if (profile.isPrivate) "PRIVATE" else "PUBLIC", 
                if (profile.isPrivate) R.drawable.badge_private else R.drawable.badge_public)
            if (profile.isVerified) addBadge("VERIFIED", R.drawable.badge_verified)
            if (profile.isBusiness) addBadge("BUSINESS", R.drawable.badge_business)
            // Add account type badge if available
            profile.getAccountTypeString()?.let { accType ->
                if (accType != "Personal") {
                    addBadge(accType.uppercase(), R.drawable.badge_business)
                }
            }

            // Bio
            if (!profile.biography.isNullOrBlank()) {
                profileBio.visibility = View.VISIBLE
                profileBio.text = profile.biography
                profileBio.setOnLongClickListener { copyToClipboard("Bio", profile.biography!!); true }
            } else {
                profileBio.visibility = View.GONE
            }

            // Website
            if (!profile.externalUrl.isNullOrBlank()) {
                profileWebsite.visibility = View.VISIBLE
                profileWebsite.text = profile.externalUrl
                profileWebsite.setOnClickListener { openUrl(profile.externalUrl!!) }
            } else {
                profileWebsite.visibility = View.GONE
            }
            
            // Impersonation warning
            if (profile.isLikelyImpersonation == true && profile.impersonationWarning != null) {
                impersonationWarning.visibility = View.VISIBLE
                impersonationWarning.text = "⚠️ POSSIBLE IMPERSONATION\n${profile.impersonationWarning}"
                impersonationWarning.setOnClickListener {
                    profile.impersonationOriginalAccount?.let { original ->
                        AlertDialog.Builder(this, R.style.AlertDialogTheme)
                            .setTitle("Possible Impersonation")
                            .setMessage("A similar account @$original has significantly more followers.\n\nThis could indicate this account is impersonating someone else.")
                            .setPositiveButton("View @$original") { _, _ ->
                                openUrl("https://instagram.com/$original")
                            }
                            .setNegativeButton("Close", null)
                            .show()
                    }
                }
            } else {
                impersonationWarning.visibility = View.GONE
            }
            
            // Unfollow button (visible when logged in)
            if (followersScanner.isLoggedIn() && profile.userId != null) {
                unfollowButton.visibility = View.VISIBLE
                unfollowButton.text = "UNFOLLOW @${profile.username}"
                removeFollowerButton.visibility = View.VISIBLE
                removeFollowerButton.text = "REMOVE @${profile.username} FROM FOLLOWERS"
            } else {
                unfollowButton.visibility = View.GONE
                removeFollowerButton.visibility = View.GONE
            }

            // Stats
            statsCard.visibility = View.VISIBLE
            statsCard.startAnimation(slideUp)
            followersCount.text = formatNumber(profile.followers)
            followingCount.text = formatNumber(profile.following)
            postsCount.text = formatNumber(profile.postsCount)

            // Business
            if (profile.isBusiness) {
                businessCard.visibility = View.VISIBLE
                businessCard.startAnimation(slideUp)
                businessContent.removeAllViews()
                
                val hasData = listOfNotNull(
                    profile.businessCategory,
                    profile.businessEmail,
                    profile.businessPhone,
                    profile.businessAddress
                ).isNotEmpty()

                if (hasData) {
                    profile.businessCategory?.let { addField(businessContent, "Category", it) }
                    profile.businessEmail?.let { addField(businessContent, "Email", it, true, true) }
                    profile.businessPhone?.let { addField(businessContent, "Phone", it, true, true) }
                    profile.businessAddress?.let { addField(businessContent, "Address", it) }
                } else {
                    addField(businessContent, "", "No business data available", warning = true)
                }
            } else {
                businessCard.visibility = View.GONE
            }

            // Obfuscated
            obfuscatedCard.visibility = View.VISIBLE
            obfuscatedCard.startAnimation(slideUp)
            obfuscatedContent.removeAllViews()
            
            // Show additional info from APIs if available
            if (profile.cityName != null || profile.publicEmail != null || profile.publicPhoneNumber != null) {
                addField(obfuscatedContent, "📍 Location", profile.cityName ?: "N/A", 
                    highlight = profile.cityName != null)
                if (profile.publicEmail != null) {
                    addField(obfuscatedContent, "📧 Public Email", profile.publicEmail!!, 
                        highlight = true, copyable = true)
                }
                if (profile.publicPhoneNumber != null) {
                    addField(obfuscatedContent, "📱 Public Phone", profile.publicPhoneNumber!!, 
                        highlight = true, copyable = true)
                }
                // Add separator
                addField(obfuscatedContent, "", "─────────────────────")
            }
            
            // Email with enhanced provider detection
            if (!profile.obfuscatedEmail.isNullOrBlank()) {
                // Show full email as-is (with all asterisks)
                addField(obfuscatedContent, "📧 Email", profile.obfuscatedEmail!!, 
                    highlight = true, copyable = true)
                
                // Show provider if detected with high confidence
                if (profile.emailProvider != null && profile.emailProviderConfidence == "high") {
                    addField(obfuscatedContent, "  └ Provider", "✓ ${profile.emailProvider}", highlight = true)
                } else if (profile.emailProvider != null) {
                    addField(obfuscatedContent, "  └ Provider", "~ ${profile.emailProvider} (probable)")
                }
                
                // Show domain type only if special (educational, gov, etc.)
                if (profile.emailDomainType != null && profile.emailDomainType !in listOf("standard", "free")) {
                    val typeEmoji = when (profile.emailDomainType) {
                        "educational" -> "🎓"
                        "government" -> "🏛"
                        "organization" -> "🏢"
                        "business/custom" -> "💼"
                        else -> ""
                    }
                    addField(obfuscatedContent, "  └ Type", "$typeEmoji ${profile.emailDomainType}")
                }
            } else {
                // No email - show status
                val statusMsg = profile.obfuscatedLookupStatus ?: "Not available"
                val isError = statusMsg.contains("🚫") || statusMsg.contains("❌") || 
                              statusMsg.contains("🔐") || statusMsg.contains("⏳")
                addField(obfuscatedContent, "📧 Email", statusMsg, warning = isError)
            }
            
            // Add separator
            addField(obfuscatedContent, "", "")
            
            // Phone with country detection
            if (!profile.obfuscatedPhone.isNullOrBlank()) {
                addField(obfuscatedContent, "📱 Phone", profile.obfuscatedPhone!!,
                    highlight = profile.phoneRiskLevel !in listOf("very_high", "high"),
                    copyable = true,
                    warning = profile.phoneRiskLevel == "high",
                    danger = profile.phoneRiskLevel == "very_high")
                
                // Show country with risk indicator
                if (profile.phoneCountry != null) {
                    val riskEmoji = when (profile.phoneRiskLevel) {
                        "very_high" -> "🚩"
                        "high" -> "🚩"
                        "moderate" -> "⚠"
                        "trusted" -> "✓"
                        else -> "○"
                    }
                    addField(obfuscatedContent, "  └ Country", "$riskEmoji ${profile.phoneCountry}",
                        warning = profile.phoneRiskLevel == "moderate",
                        danger = profile.phoneRiskLevel in listOf("very_high", "high"),
                        highlight = profile.phoneRiskLevel == "trusted")
                    
                    // Show risk warning for dangerous countries
                    if (profile.phoneRiskLevel == "very_high") {
                        addField(obfuscatedContent, "  └ ⚠️ Warning", "SCAM HOTSPOT - Be very careful!", danger = true)
                    } else if (profile.phoneRiskLevel == "high") {
                        addField(obfuscatedContent, "  └ ⚠️ Warning", "Known scam region", danger = true)
                    }
                }
                
                // Show carrier hint if French number
                if (profile.phoneCarrierHint != null && profile.phoneCountry == "France") {
                    addField(obfuscatedContent, "  └ Operator", profile.phoneCarrierHint!!)
                }
            } else {
                addField(obfuscatedContent, "📱 Phone", "Not linked")
            }
            
            // Show lookup status if there were issues
            if (!profile.obfuscatedLookupStatus.isNullOrBlank() && 
                profile.obfuscatedEmail.isNullOrBlank() && 
                profile.obfuscatedPhone.isNullOrBlank()) {
                addField(obfuscatedContent, "", "")
                addField(obfuscatedContent, "Lookup status", profile.obfuscatedLookupStatus!!, 
                    warning = profile.obfuscatedLookupStatus!!.contains("⏳") || profile.obfuscatedLookupStatus!!.contains("⚠"),
                    danger = profile.obfuscatedLookupStatus!!.contains("🚫") || profile.obfuscatedLookupStatus!!.contains("❌"))
            }

            // Account age - Show all estimation methods
            ageCard.visibility = View.VISIBLE
            ageCard.startAnimation(slideUp)
            ageContent.removeAllViews()
            
            // Show best estimate prominently
            if (!profile.ageBestEstimate.isNullOrBlank() && profile.ageBestEstimate != "Unknown") {
                val confLabel = when {
                    profile.ageConfidenceScore >= 80 -> "High confidence"
                    profile.ageConfidenceScore >= 60 -> "Medium confidence"
                    else -> "Low confidence"
                }
                addField(ageContent, "📅 Best estimate", "${profile.ageBestEstimate} (~${profile.ageBestEstimateYears})", highlight = true)
                addField(ageContent, "  └ Source", "${profile.ageBestEstimateSource} • $confLabel (${profile.ageConfidenceScore}%)")
            }
            
            // Show all methods used
            if (profile.ageMethodsUsed.isNotEmpty()) {
                addField(ageContent, "", "───── All Methods ─────")
                
                // User ID estimation
                if (!profile.ageUserIdEstimate.isNullOrBlank()) {
                    addField(ageContent, "🔢 User ID", "${profile.ageUserIdEstimate} (~${profile.ageUserIdYears})")
                }
                
                // First post
                if (!profile.firstPostDate.isNullOrBlank()) {
                    addField(ageContent, "📸 First post", profile.firstPostDate!!)
                }
                
                // Media ID decode
                if (!profile.ageMediaIdEstimate.isNullOrBlank() && profile.ageMediaIdEstimate != profile.firstPostDate) {
                    addField(ageContent, "🔓 Media ID decode", profile.ageMediaIdEstimate!!)
                }
                
                // Profile pic ID
                if (!profile.ageProfilePicEstimate.isNullOrBlank()) {
                    addField(ageContent, "🖼 Profile pic ID", profile.ageProfilePicEstimate!!)
                }
                
                // Wayback
                if (!profile.waybackDate.isNullOrBlank()) {
                    addField(ageContent, "🕰 Wayback archive", profile.waybackDate!!)
                    addField(ageContent, "  └ View archive", "Open in browser", clickable = true) {
                        profile.waybackUrl?.let { openUrl(it) }
                    }
                }
            } else {
                // Fallback to old display if no methods returned
                if (profile.waybackDate != null) {
                    addField(ageContent, "Wayback archive", profile.waybackDate!!, highlight = true)
                    addField(ageContent, "Archive URL", "View on Wayback", clickable = true) {
                        profile.waybackUrl?.let { openUrl(it) }
                    }
                } else if (profile.isPrivate) {
                    addField(ageContent, "", "Cannot fully estimate (private account)", warning = true)
                    if (!profile.ageUserIdEstimate.isNullOrBlank()) {
                        addField(ageContent, "User ID estimate", "${profile.ageUserIdEstimate} (~${profile.ageUserIdYears})")
                    }
                } else if (profile.postsCount == 0) {
                    addField(ageContent, "", "Limited data (no posts)", warning = true)
                    if (!profile.ageUserIdEstimate.isNullOrBlank()) {
                        addField(ageContent, "User ID estimate", "${profile.ageUserIdEstimate} (~${profile.ageUserIdYears})")
                    }
                } else {
                    addField(ageContent, "", "Could not determine account age", warning = true)
                }
            }

            // Trust Score
            trustCard.visibility = View.VISIBLE
            trustCard.startAnimation(slideUp)
            
            val score = profile.trustScore
            val scoreColor = when {
                score >= 70 -> getColor(R.color.green)
                score >= 50 -> getColor(R.color.yellow)
                score >= 30 -> getColor(R.color.orange)
                else -> getColor(R.color.red)
            }
            val verdict = when {
                score >= 80 -> "HIGHLY TRUSTWORTHY"
                score >= 70 -> "TRUSTWORTHY"
                score >= 60 -> "LIKELY LEGITIMATE"
                score >= 50 -> "MODERATE"
                score >= 40 -> "LOW CONFIDENCE"
                score >= 25 -> "SUSPICIOUS"
                else -> "HIGH RISK"
            }
            
            trustTitle.setTextColor(scoreColor)
            
            val barFilled = score / 5
            val barEmpty = 20 - barFilled
            trustBar.text = "█".repeat(barFilled) + "░".repeat(barEmpty)
            trustBar.setTextColor(scoreColor)
            
            trustScoreText.text = "$score/100"
            trustScoreText.setTextColor(scoreColor)
            
            trustVerdict.text = verdict
            trustVerdict.setTextColor(scoreColor)
            
            // Separate positive and negative details
            trustDetails.removeAllViews()
            
            val positiveDetails = profile.trustDetails.filter { it.score > 0 }
            val neutralDetails = profile.trustDetails.filter { it.score == 0 }
            val negativeDetails = profile.trustDetails.filter { it.score < 0 }
            
            // Create two-column layout
            val columnsLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            
            // Left column - Positive
            val leftColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, 0, 8.dpToPx(), 0)
            }
            
            if (positiveDetails.isNotEmpty()) {
                leftColumn.addView(TextView(this).apply {
                    text = "✓ POSITIVE"
                    setTextColor(getColor(R.color.green))
                    textSize = 11f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 0, 0, 8.dpToPx())
                })
                
                positiveDetails.forEach { detail ->
                    leftColumn.addView(TextView(this).apply {
                        text = "${detail.text} (+${detail.score})"
                        setTextColor(getColor(R.color.green))
                        textSize = 11f
                        setPadding(0, 2.dpToPx(), 0, 2.dpToPx())
                    })
                }
            }
            
            // Add neutral items to left column
            neutralDetails.forEach { detail ->
                leftColumn.addView(TextView(this).apply {
                    text = "○ ${detail.text}"
                    setTextColor(getColor(R.color.gray))
                    textSize = 11f
                    setPadding(0, 2.dpToPx(), 0, 2.dpToPx())
                })
            }
            
            // Right column - Negative
            val rightColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(8.dpToPx(), 0, 0, 0)
            }
            
            if (negativeDetails.isNotEmpty()) {
                rightColumn.addView(TextView(this).apply {
                    text = "✗ NEGATIVE"
                    setTextColor(getColor(R.color.red))
                    textSize = 11f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 0, 0, 8.dpToPx())
                })
                
                negativeDetails.forEach { detail ->
                    val detailColor = if (detail.color == "orange") getColor(R.color.orange) else getColor(R.color.red)
                    rightColumn.addView(TextView(this).apply {
                        text = "${detail.text} (${detail.score})"
                        setTextColor(detailColor)
                        textSize = 11f
                        setPadding(0, 2.dpToPx(), 0, 2.dpToPx())
                    })
                }
            } else {
                rightColumn.addView(TextView(this).apply {
                    text = "✓ No red flags"
                    setTextColor(getColor(R.color.green))
                    textSize = 11f
                    setPadding(0, 0, 0, 8.dpToPx())
                })
            }
            
            columnsLayout.addView(leftColumn)
            columnsLayout.addView(rightColumn)
            trustDetails.addView(columnsLayout)

            // ========== BIO ANALYSIS ==========
            if (profile.bioScamIndicators.isNotEmpty() || 
                profile.bioExtractedEmails.isNotEmpty() || 
                profile.bioExtractedPhones.isNotEmpty() ||
                profile.bioExtractedUrls.isNotEmpty()) {
                
                bioAnalysisCard.visibility = View.VISIBLE
                bioAnalysisCard.startAnimation(slideUp)
                bioAnalysisContent.removeAllViews()
                
                // Risk level
                if (profile.bioRiskLevel != null) {
                    val riskText = when (profile.bioRiskLevel) {
                        "high" -> "🚩 HIGH RISK - Multiple scam indicators detected"
                        "moderate" -> "⚠️ MODERATE RISK - Some suspicious patterns"
                        "low" -> "○ LOW RISK - Minor flags"
                        else -> null
                    }
                    if (riskText != null) {
                        addField(bioAnalysisContent, "Risk Level", riskText,
                            danger = profile.bioRiskLevel == "high",
                            warning = profile.bioRiskLevel == "moderate")
                    }
                    bioAnalysisTitle.setTextColor(when (profile.bioRiskLevel) {
                        "high" -> getColor(R.color.red)
                        "moderate" -> getColor(R.color.orange)
                        else -> getColor(R.color.yellow)
                    })
                }
                
                // Scam indicators
                if (profile.bioScamIndicators.isNotEmpty()) {
                    addField(bioAnalysisContent, "⚠️ Scam Indicators", "", danger = true)
                    profile.bioScamIndicators.forEach { indicator ->
                        addField(bioAnalysisContent, "  •", indicator, danger = true)
                    }
                }
                
                // Extracted emails
                if (profile.bioExtractedEmails.isNotEmpty()) {
                    profile.bioExtractedEmails.forEach { email ->
                        addField(bioAnalysisContent, "📧 Email in bio", email, 
                            highlight = true, copyable = true)
                    }
                }
                
                // Extracted phones
                if (profile.bioExtractedPhones.isNotEmpty()) {
                    profile.bioExtractedPhones.forEach { phone ->
                        addField(bioAnalysisContent, "📱 Phone in bio", phone, 
                            highlight = true, copyable = true)
                    }
                }
                
                // Extracted URLs
                if (profile.bioExtractedUrls.isNotEmpty()) {
                    profile.bioExtractedUrls.forEach { url ->
                        addField(bioAnalysisContent, "🔗 URL", url, clickable = true) {
                            openUrl(url)
                        }
                    }
                }
            } else {
                bioAnalysisCard.visibility = View.GONE
            }

            // ========== REVERSE IMAGE SEARCH ==========
            if (!profile.profilePicUrl.isNullOrBlank() && !profile.profilePicIsDefault) {
                reverseImageCard.visibility = View.VISIBLE
                reverseImageCard.startAnimation(slideUp)
                reverseImageContent.removeAllViews()
                
                // Show status if stolen was detected
                if (profile.profilePicIsStolen && profile.profilePicStolenSource != null) {
                    addField(reverseImageContent, "⚠️ Warning", "Image may be stolen", danger = true)
                    addField(reverseImageContent, "  └ Found at", profile.profilePicStolenSource!!, 
                        copyable = true, warning = true)
                    addField(reverseImageContent, "", "─────────────────────")
                }
                
                // Manual search links - always show
                addField(reverseImageContent, "", "Verify manually:")
                val reverseUrls = client.getReverseImageSearchUrls(profile.profilePicUrl!!)
                reverseUrls.forEach { (name, url) ->
                    addField(reverseImageContent, "🔍", name, clickable = true) {
                        openUrl(url)
                    }
                }
            } else {
                reverseImageCard.visibility = View.GONE
            }

            // ========== CROSS-PLATFORM SEARCH ==========
            crossPlatformCard.visibility = View.VISIBLE
            crossPlatformCard.startAnimation(slideUp)
            crossPlatformContent.removeAllViews()
            
            val platforms = client.getCrossPlatformUrls(profile.username)
            // Show first 8 most popular ones
            val popularPlatforms = listOf("Twitter/X", "TikTok", "Facebook", "YouTube", 
                "LinkedIn", "Snapchat", "GitHub", "Reddit")
            platforms.filter { it.first in popularPlatforms }.forEach { (name, url) ->
                addField(crossPlatformContent, "🌐", name, clickable = true) {
                    openUrl(url)
                }
            }
            
            // "More platforms" button
            addField(crossPlatformContent, "", "Show all ${platforms.size} platforms →", clickable = true) {
                showAllPlatformsDialog(profile.username)
            }

            // Copy user ID on long press
            profileCard.setOnLongClickListener {
                profile.userId?.let { copyToClipboard("User ID", it) }
                true
            }
        }
    }
    
    private fun showAllPlatformsDialog(username: String) {
        val platforms = client.getCrossPlatformUrls(username)
        val names = platforms.map { it.first }.toTypedArray()
        
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("Search @$username on...")
            .setItems(names) { _, which ->
                openUrl(platforms[which].second)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addBadge(text: String, backgroundRes: Int) {
        val badge = TextView(this).apply {
            this.text = text
            setBackgroundResource(backgroundRes)
            setTextColor(getColor(R.color.white))
            textSize = 10f
            setPadding(16, 4, 16, 4)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = 8
            layoutParams = params
        }
        profileBadges.addView(badge)
    }

    private fun addField(
        container: LinearLayout,
        label: String,
        value: String,
        highlight: Boolean = false,
        copyable: Boolean = false,
        warning: Boolean = false,
        danger: Boolean = false,
        clickable: Boolean = false,
        onClick: (() -> Unit)? = null
    ) {
        val fieldView = layoutInflater.inflate(R.layout.item_field, container, false)
        val labelView = fieldView.findViewById<TextView>(R.id.field_label)
        val valueView = fieldView.findViewById<TextView>(R.id.field_value)

        if (label.isNotEmpty()) {
            labelView.text = label
            labelView.visibility = View.VISIBLE
        } else {
            labelView.visibility = View.GONE
        }

        valueView.text = value
        when {
            danger -> valueView.setTextColor(getColor(R.color.red))
            highlight -> valueView.setTextColor(getColor(R.color.green))
            warning -> valueView.setTextColor(getColor(R.color.yellow))
            clickable -> valueView.setTextColor(getColor(R.color.cyan))
        }

        if (copyable) {
            fieldView.setOnClickListener { copyToClipboard(label, value) }
            fieldView.setOnLongClickListener { copyToClipboard(label, value); true }
        }

        if (clickable && onClick != null) {
            fieldView.setOnClickListener { onClick() }
        }

        container.addView(fieldView)
    }

    private fun showHistory() {
        val history = historyManager.getHistory()
        
        if (history.isEmpty()) {
            historyContainer.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
            return
        }

        emptyState.visibility = View.GONE
        historyContainer.visibility = View.VISIBLE
        historyList.removeAllViews()

        history.forEach { profile ->
            val itemView = layoutInflater.inflate(R.layout.item_history, historyList, false)
            val image = itemView.findViewById<ImageView>(R.id.history_image)
            val name = itemView.findViewById<TextView>(R.id.history_name)
            val username = itemView.findViewById<TextView>(R.id.history_username)
            val time = itemView.findViewById<TextView>(R.id.history_time)

            Glide.with(this)
                .load(profile.profilePicUrl)
                .transform(CircleCrop())
                .placeholder(R.drawable.ic_profile_placeholder)
                .into(image)

            name.text = profile.fullName ?: profile.username
            username.text = "@${profile.username}"
            time.text = getRelativeTime(profile.timestamp)

            itemView.setOnClickListener {
                usernameInput.setText(profile.username)
                lookup(profile.username)
            }

            itemView.setOnLongClickListener {
                showDeleteHistoryDialog(profile.username)
                true
            }

            historyList.addView(itemView)
        }
    }

    private fun hideHistory() {
        historyContainer.visibility = View.GONE
        emptyState.visibility = View.GONE
    }

    private fun hideResults() {
        resultsContainer.visibility = View.GONE
        scanResultsContainer.visibility = View.GONE
        scanResultsContainer.removeAllViews()
        profileCard.visibility = View.GONE
        statsCard.visibility = View.GONE
        businessCard.visibility = View.GONE
        obfuscatedCard.visibility = View.GONE
        ageCard.visibility = View.GONE
        trustCard.visibility = View.GONE
        bioAnalysisCard.visibility = View.GONE
        reverseImageCard.visibility = View.GONE
        crossPlatformCard.visibility = View.GONE
    }

    private fun showLoading(show: Boolean, message: String = "Loading...") {
        loadingCard.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            loadingText.text = message
            loadingCard.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_in))
        }
    }
    
    private fun hideLoading() {
        loadingCard.visibility = View.GONE
    }

    private fun showDeleteHistoryDialog(username: String) {
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("Remove from history")
            .setMessage("Remove @$username from search history?")
            .setPositiveButton("Remove") { _, _ ->
                historyManager.removeFromHistory(username)
                showHistory()
                showSnackbar("Removed from history")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        showSnackbar("$label copied to clipboard")
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            showSnackbar("Cannot open URL")
        }
    }

    private fun exportResults() {
        val profile = currentProfile ?: run {
            showSnackbar("No results to export")
            return
        }

        val json = profile.toJson()
        val filename = "ig_lookup_${profile.username}_${System.currentTimeMillis()}.json"
        
        try {
            val file = File(getExternalFilesDir(null), filename)
            file.writeText(json)
            
            showSnackbar("Exported to ${file.name}")
            
            // Share option
            AlertDialog.Builder(this, R.style.AlertDialogTheme)
                .setTitle("Export Complete")
                .setMessage("Share the exported file?")
                .setPositiveButton("Share") { _, _ ->
                    val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share results"))
                }
                .setNegativeButton("Close", null)
                .show()
        } catch (e: Exception) {
            showSnackbar("Export failed: ${e.message}")
        }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT)
            .setBackgroundTint(getColor(R.color.surface))
            .setTextColor(getColor(R.color.white))
            .show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(usernameInput.windowToken, 0)
    }

    private fun formatNumber(num: Int?): String {
        if (num == null) return "N/A"
        return when {
            num >= 1_000_000 -> String.format("%.1fM", num / 1_000_000.0)
            num >= 1_000 -> String.format("%.1fK", num / 1_000.0)
            else -> num.toString()
        }
    }

    private fun getRelativeTime(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000 -> "Just now"
            diff < 3600_000 -> "${diff / 60_000}m ago"
            diff < 86400_000 -> "${diff / 3600_000}h ago"
            diff < 604800_000 -> "${diff / 86400_000}d ago"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export -> {
                exportResults()
                true
            }
            R.id.action_clear_history -> {
                AlertDialog.Builder(this, R.style.AlertDialogTheme)
                    .setTitle("Clear History")
                    .setMessage("Remove all search history?")
                    .setPositiveButton("Clear") { _, _ ->
                        historyManager.clearHistory()
                        showHistory()
                        showSnackbar("History cleared")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            R.id.action_clear_cache -> {
                clearAppCache()
                true
            }
            R.id.action_logout -> {
                performLogout()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun clearAppCache() {
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("Clear Cache")
            .setMessage("This will clear all cached data. Continue?")
            .setPositiveButton("Clear") { _, _ ->
                try {
                    // Clear app cache
                    cacheDir.deleteRecursively()
                    cacheDir.mkdir()
                    
                    // Clear Glide cache
                    lifecycleScope.launch(Dispatchers.IO) {
                        com.bumptech.glide.Glide.get(this@MainActivity).clearDiskCache()
                    }
                    com.bumptech.glide.Glide.get(this).clearMemory()
                    
                    showSnackbar("Cache cleared")
                } catch (e: Exception) {
                    showSnackbar("Error clearing cache")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun performLogout() {
        if (!followersScanner.isLoggedIn()) {
            showSnackbar("Not logged in")
            return
        }
        
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("Logout")
            .setMessage("Logout from Instagram session?\nYou'll need to login again to scan followers.")
            .setPositiveButton("Logout") { _, _ ->
                // Clear session data
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .remove(KEY_SESSION_ID)
                    .remove(KEY_CSRF_TOKEN)
                    .remove(KEY_USER_ID)
                    .remove(KEY_USERNAME)
                    .apply()
                
                // Clear scanner session
                followersScanner.sessionId = null
                followersScanner.csrfToken = null
                followersScanner.loggedInUserId = null
                followersScanner.loggedInUsername = null
                
                // Update UI
                updateLoginStatus()
                showSnackbar("Logged out successfully")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    // ==================== SESSION MANAGEMENT ====================
    
    private fun restoreSession() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        followersScanner.sessionId = prefs.getString(KEY_SESSION_ID, null)
        followersScanner.csrfToken = prefs.getString(KEY_CSRF_TOKEN, null)
        followersScanner.loggedInUserId = prefs.getString(KEY_USER_ID, null)
        followersScanner.loggedInUsername = prefs.getString(KEY_USERNAME, null)
        updateLoginStatus()
    }
    
    private fun saveSession() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_SESSION_ID, followersScanner.sessionId)
            putString(KEY_CSRF_TOKEN, followersScanner.csrfToken)
            putString(KEY_USER_ID, followersScanner.loggedInUserId)
            putString(KEY_USERNAME, followersScanner.loggedInUsername)
            apply()
        }
    }
    
    private fun clearSession() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        followersScanner.logout()
        updateLoginStatus()
    }
    
    private fun updateLoginStatus() {
        if (followersScanner.isLoggedIn() && followersScanner.loggedInUsername != null) {
            loginStatus.text = "Logged in as @${followersScanner.loggedInUsername}"
            loginStatus.setTextColor(getColor(R.color.cyan))
            scanFollowersButton.text = "🔍 SCAN MY FOLLOWERS"
        } else {
            loginStatus.text = "Not logged in - Tap to login"
            loginStatus.setTextColor(getColor(R.color.gray))
            scanFollowersButton.text = "🔐 LOGIN & SCAN FOLLOWERS"
        }
    }
    
    // ==================== LOGIN DIALOG ====================
    
    private fun showLoginDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_login, null)
        val usernameEdit = dialogView.findViewById<EditText>(R.id.login_username)
        val passwordEdit = dialogView.findViewById<EditText>(R.id.login_password)
        
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setView(dialogView)
            .setPositiveButton("Login") { _, _ ->
                val username = usernameEdit.text.toString().trim()
                val password = passwordEdit.text.toString()
                if (username.isNotEmpty() && password.isNotEmpty()) {
                    performLogin(username, password)
                } else {
                    showSnackbar("Please enter username and password")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun performLogin(username: String, password: String) {
        showLoading(true, "Logging in...")
        
        lifecycleScope.launch {
            val result = followersScanner.login(username, password)
            hideLoading()
            
            when (result) {
                is FollowersScanner.LoginResult.Success -> {
                    saveSession()
                    updateLoginStatus()
                    showSnackbar("Logged in as @${result.username}")
                    startFollowersScan()
                }
                is FollowersScanner.LoginResult.TwoFactorRequired -> {
                    showErrorDialog("Two-Factor Authentication", result.message)
                }
                is FollowersScanner.LoginResult.ChallengeRequired -> {
                    showErrorDialog("Security Challenge", result.message)
                }
                is FollowersScanner.LoginResult.Error -> {
                    showErrorDialog("Login Failed", result.message)
                }
            }
        }
    }
    
    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
    
    // ==================== FOLLOWERS SCAN ====================
    
    private fun startFollowersScan(forceRefresh: Boolean = false) {
        // Check for cached scan results first
        if (!forceRefresh) {
            val cached = historyManager.getCachedScanResult(maxAgeHours = 24)
            if (cached != null) {
                val ageMinutes = historyManager.getScanResultAgeMinutes()
                val ageText = when {
                    ageMinutes < 60 -> "${ageMinutes}min"
                    ageMinutes < 1440 -> "${ageMinutes / 60}h"
                    else -> "${ageMinutes / 1440}d"
                }
                displayScanResults(cached)
                showSnackbar("📦 Loaded from cache ($ageText ago) • Pull to refresh")
                return
            }
        }
        
        showLoading(true, "Starting scan...")
        hideResults()
        historyContainer.visibility = View.GONE
        emptyState.visibility = View.GONE
        
        lifecycleScope.launch {
            val result = followersScanner.scanFollowers { current, total, username ->
                runOnUiThread {
                    loadingText.text = "Scanning: $current/$total\n@$username"
                }
            }
            
            hideLoading()
            
            if (result.errorMessage != null) {
                showSnackbar("Error: ${result.errorMessage}")
                showHistory()
            } else {
                // Save scan results to cache
                historyManager.saveScanResult(result)
                displayScanResults(result)
                showSnackbar("✅ Scan complete • ${result.totalScanned} followers analyzed")
            }
        }
    }
    
    private fun displayScanResults(result: FollowersScanner.ScanResult) {
        isShowingResults = true
        updateToolbarForState()
        supportActionBar?.title = "Scan Results"
        
        // Hide profile cards, show scan results container
        hideResults()
        scanResultsContainer.removeAllViews()
        scanResultsContainer.visibility = View.VISIBLE
        resultsContainer.visibility = View.VISIBLE  // Parent needs to be visible
        
        val slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up)
        
        // Summary card
        scanResultsContainer.addView(createSectionCard("📊 SCAN SUMMARY", listOf(
            "Total scanned" to result.totalScanned.toString(),
            "🚩 High risk" to result.highRiskCount.toString(),
            "⚠️ Suspicious" to result.suspiciousCount.toString(),
            "✅ Normal" to result.normalCount.toString()
        )))
        
        // High risk followers
        if (result.highRiskFollowers.isNotEmpty()) {
            val highRiskCard = createFollowersSection("🚩 HIGH RISK FOLLOWERS", result.highRiskFollowers, R.color.red)
            scanResultsContainer.addView(highRiskCard)
            highRiskCard.startAnimation(slideUp)
        }
        
        // Suspicious followers (show max 10)
        if (result.suspiciousFollowers.isNotEmpty()) {
            val displayList = result.suspiciousFollowers.take(10)
            val title = if (result.suspiciousFollowers.size > 10) {
                "⚠️ SUSPICIOUS (showing 10 of ${result.suspiciousFollowers.size})"
            } else {
                "⚠️ SUSPICIOUS FOLLOWERS"
            }
            val suspiciousCard = createFollowersSection(title, displayList, R.color.orange)
            scanResultsContainer.addView(suspiciousCard)
            suspiciousCard.startAnimation(slideUp)
        }
        
        // No issues found
        if (result.highRiskFollowers.isEmpty() && result.suspiciousFollowers.isEmpty()) {
            val noIssuesCard = CardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 16.dpToPx() }
                setCardBackgroundColor(getColor(R.color.surface))
                radius = 12.dpToPx().toFloat()
                cardElevation = 4.dpToPx().toFloat()
            }
            
            val content = TextView(this).apply {
                text = "✅ No risky followers detected!\n\nYour followers list looks clean."
                setTextColor(getColor(R.color.green))
                textSize = 16f
                setPadding(20.dpToPx(), 20.dpToPx(), 20.dpToPx(), 20.dpToPx())
            }
            noIssuesCard.addView(content)
            scanResultsContainer.addView(noIssuesCard)
        }
        
        // Add Rescan button at the bottom
        val rescanButton = Button(this).apply {
            text = "🔄 RESCAN FOLLOWERS"
            setTextColor(getColor(R.color.cyan))
            setBackgroundColor(getColor(R.color.surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { 
                topMargin = 16.dpToPx()
                bottomMargin = 32.dpToPx()
            }
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
            setOnClickListener {
                startFollowersScan(forceRefresh = true)
            }
        }
        scanResultsContainer.addView(rescanButton)
    }
    
    private fun createSectionCard(title: String, items: List<Pair<String, String>>): CardView {
        val card = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16.dpToPx() }
            setCardBackgroundColor(getColor(R.color.surface))
            radius = 12.dpToPx().toFloat()
            cardElevation = 4.dpToPx().toFloat()
        }
        
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
        }
        
        // Title
        container.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.cyan))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 12.dpToPx())
        })
        
        // Items
        items.forEach { (label, value) ->
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 4.dpToPx(), 0, 4.dpToPx())
                
                addView(TextView(this@MainActivity).apply {
                    text = label
                    setTextColor(getColor(R.color.gray))
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                
                addView(TextView(this@MainActivity).apply {
                    text = value
                    setTextColor(getColor(R.color.white))
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
            })
        }
        
        card.addView(container)
        return card
    }
    
    private fun createFollowersSection(title: String, followers: List<FollowersScanner.FollowerAnalysis>, colorRes: Int): CardView {
        val card = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16.dpToPx() }
            setCardBackgroundColor(getColor(R.color.surface))
            radius = 12.dpToPx().toFloat()
            cardElevation = 4.dpToPx().toFloat()
        }
        
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
        }
        
        // Title
        container.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(colorRes))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 12.dpToPx())
        })
        
        // Followers
        followers.forEach { follower ->
            val itemView = layoutInflater.inflate(R.layout.item_scan_result, container, false)
            
            // The CardView already has foreground ripple defined in XML, don't override background
            
            itemView.findViewById<TextView>(R.id.follower_username).text = "@${follower.username}"
            
            val riskBadge = itemView.findViewById<TextView>(R.id.risk_badge)
            riskBadge.text = "Risk: ${follower.riskScore}%"
            riskBadge.setTextColor(getColor(when {
                follower.riskScore >= 70 -> R.color.red
                follower.riskScore >= 50 -> R.color.orange
                else -> R.color.yellow
            }))
            
            if (!follower.fullName.isNullOrBlank()) {
                itemView.findViewById<TextView>(R.id.follower_name).apply {
                    text = follower.fullName
                    visibility = View.VISIBLE
                }
            }
            
            if (!follower.estimatedAge.isNullOrBlank()) {
                itemView.findViewById<TextView>(R.id.follower_age).apply {
                    text = "Account age: ~${follower.estimatedAge}"
                    visibility = View.VISIBLE
                }
            }
            
            if (follower.flags.isNotEmpty()) {
                itemView.findViewById<TextView>(R.id.follower_flags).apply {
                    text = "⚠️ ${follower.flags.joinToString(", ")}"
                    visibility = View.VISIBLE
                }
            }
            
            // Click to lookup this user
            itemView.setOnClickListener {
                // Show loading toast
                Toast.makeText(this, "Looking up @${follower.username}...", Toast.LENGTH_SHORT).show()
                lookup(follower.username)
            }
            
            // Long click for options menu
            itemView.setOnLongClickListener {
                showFollowerOptionsMenu(follower)
                true
            }
            
            container.addView(itemView)
        }
        
        card.addView(container)
        return card
    }
    
    private fun showFollowerOptionsMenu(follower: FollowersScanner.FollowerAnalysis) {
        val options = if (followersScanner.isLoggedIn()) {
            arrayOf(
                "🔍 Lookup profile",
                "📱 Open in Instagram",
                "📋 Copy username",
                "🌐 Search on Google",
                "❌ Unfollow",
                "🚫 Remove follower"
            )
        } else {
            arrayOf(
                "🔍 Lookup profile",
                "📱 Open in Instagram",
                "📋 Copy username",
                "🌐 Search on Google"
            )
        }
        
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("@${follower.username}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        Toast.makeText(this, "Looking up @${follower.username}...", Toast.LENGTH_SHORT).show()
                        lookup(follower.username)
                    }
                    1 -> {
                        // Open in Instagram app or browser
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("instagram://user?username=${follower.username}"))
                            startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://instagram.com/${follower.username}"))
                            startActivity(intent)
                        }
                    }
                    2 -> {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("username", follower.username))
                        Toast.makeText(this, "Username copied!", Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${follower.username}+instagram"))
                        startActivity(intent)
                    }
                    4 -> {
                        // Unfollow
                        showUnfollowConfirmDialogForFollower(follower)
                    }
                    5 -> {
                        // Remove follower
                        showRemoveFollowerConfirmDialog(follower)
                    }
                }
            }
            .show()
    }
    
    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
    
    // ==================== UNFOLLOW ====================
    
    private fun showUnfollowConfirmDialog(profile: InstagramProfile) {
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("Confirm Unfollow")
            .setMessage("Are you sure you want to unfollow @${profile.username}?")
            .setPositiveButton("Unfollow") { _, _ ->
                performUnfollow(profile)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showUnfollowConfirmDialogForFollower(follower: FollowersScanner.FollowerAnalysis) {
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("Confirm Unfollow")
            .setMessage("Are you sure you want to unfollow @${follower.username}?")
            .setPositiveButton("Unfollow") { _, _ ->
                performUnfollowFollower(follower)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showRemoveFollowerConfirmDialog(follower: FollowersScanner.FollowerAnalysis) {
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("Remove Follower")
            .setMessage("Remove @${follower.username} from your followers?\n\nThey will no longer follow you and won't be notified.")
            .setPositiveButton("Remove") { _, _ ->
                performRemoveFollower(follower)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showRemoveFollowerConfirmDialogForProfile(profile: InstagramProfile) {
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("Remove Follower")
            .setMessage("Remove @${profile.username} from your followers?\n\nThey will no longer follow you and won't be notified.")
            .setPositiveButton("Remove") { _, _ ->
                performRemoveFollowerForProfile(profile)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun performUnfollow(profile: InstagramProfile) {
        val userId = profile.userId ?: return
        val sessionId = followersScanner.sessionId ?: return
        val csrfToken = followersScanner.csrfToken ?: return
        
        showLoading(true, "Unfollowing @${profile.username}...")
        
        lifecycleScope.launch {
            val success = client.unfollowUser(userId, sessionId, csrfToken)
            hideLoading()
            
            if (success) {
                // Clear scan cache to force refresh on next scan
                historyManager.clearScanResult()
                
                showSnackbar("Successfully unfollowed @${profile.username}")
                unfollowButton.visibility = View.GONE
            } else {
                showSnackbar("Failed to unfollow. Please try again.")
            }
        }
    }
    
    private fun performUnfollowFollower(follower: FollowersScanner.FollowerAnalysis) {
        val userId = follower.pk ?: return
        val sessionId = followersScanner.sessionId ?: return
        val csrfToken = followersScanner.csrfToken ?: return
        
        showLoading(true, "Unfollowing @${follower.username}...")
        
        lifecycleScope.launch {
            val success = client.unfollowUser(userId, sessionId, csrfToken)
            hideLoading()
            
            if (success) {
                historyManager.clearScanResult()
                scanResultsContainer.visibility = View.GONE
                scanResultsContainer.removeAllViews()
                showSnackbar("Unfollowed @${follower.username} - Scan again to refresh")
            } else {
                showSnackbar("Failed to unfollow. Please try again.")
            }
        }
    }
    
    private fun performRemoveFollower(follower: FollowersScanner.FollowerAnalysis) {
        val userId = follower.pk ?: return
        val sessionId = followersScanner.sessionId ?: return
        val csrfToken = followersScanner.csrfToken ?: return
        
        showLoading(true, "Removing @${follower.username} from followers...")
        
        lifecycleScope.launch {
            val success = client.removeFollower(userId, sessionId, csrfToken)
            hideLoading()
            
            if (success) {
                // Clear the entire scan cache to force a fresh scan next time
                historyManager.clearScanResult()
                
                // Hide scan results and show message
                scanResultsContainer.visibility = View.GONE
                scanResultsContainer.removeAllViews()
                
                showSnackbar("Removed @${follower.username} - Scan again to refresh")
            } else {
                showSnackbar("Failed to remove follower. Please try again.")
            }
        }
    }
    
    private fun performRemoveFollowerForProfile(profile: InstagramProfile) {
        val userId = profile.userId ?: return
        val sessionId = followersScanner.sessionId ?: return
        val csrfToken = followersScanner.csrfToken ?: return
        
        showLoading(true, "Removing @${profile.username} from followers...")
        
        lifecycleScope.launch {
            val success = client.removeFollower(userId, sessionId, csrfToken)
            hideLoading()
            
            if (success) {
                // Clear scan cache to force refresh on next scan
                historyManager.clearScanResult()
                
                showSnackbar("Removed @${profile.username} from your followers")
                removeFollowerButton.visibility = View.GONE
            } else {
                showSnackbar("Failed to remove follower. Please try again.")
            }
        }
    }
}
