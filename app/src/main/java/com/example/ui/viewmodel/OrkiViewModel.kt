package com.example.ui.viewmodel

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.audio.AudioManager
import com.example.data.audio.AudioPlayerState
import com.example.data.billing.PlayBillingManager
import com.example.data.local.AppDatabase
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ConversationEntity
import com.example.data.network.ImageGenerationService
import com.example.data.network.OrkiApiService
import com.example.data.preferences.UserPreferences
import com.example.data.preferences.VoiceOptions
import com.example.util.AttachedFile
import com.example.util.FileUploadHelper
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class LiveTalkStatus {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

data class UiState(
    val currentConversationId: String? = null,
    val messages: List<ChatMessageEntity> = emptyList(),
    val isGenerating: Boolean = false,
    val currentStreamingResponse: String = "",
    val isThinking: Boolean = false,
    val isRecording: Boolean = false,
    val liveTalkStatus: LiveTalkStatus = LiveTalkStatus.IDLE,
    val liveTalkTranscript: String = "",
    val script: String = "deva",
    val uiLanguage: String = "deva",
    val userName: String = "Guest",
    val userPersona: String = "",
    val currentPlan: String = "Guest",
    val dailyUsage: Int = 0,
    val dailyLimit: Int = 4,
    val dailyUploadUsage: Int = 0,
    val dailyUploadLimit: Int = 0,
    val attachedFile: AttachedFile? = null,
    val isProcessingFile: Boolean = false,
    val selectedModel: String = "orki-3.0",
    val selectedVoice: String = "female_mainao",
    val isIncognito: Boolean = false,
    val isLoggedIn: Boolean = false,
    val isEmailVerified: Boolean = false,
    val authMethod: String = "Guest",
    val userEmail: String = "",
    val errorMessage: String? = null,
    val isGeneratingImage: Boolean = false,
    val imageGenProgress: Int = 0,
    val imageGenStage: String = "",
    val imageGenPrompt: String = "",
    val imageGenEngine: String = "Orki AI (Cloudflare)",
    val isImageGenFallback: Boolean = false,
    val isGeneratingVideo: Boolean = false,
    val videoGenProgress: Int = 0,
    val videoGenStage: String = "",
    val videoGenPrompt: String = "",
    val videoGenEngine: String = "Json2video AI Studio (Primary)",
    val isVideoGenFallback: Boolean = false,
    val triggerUpgradeDialog: Boolean = false,
    val triggerLoginDialog: Boolean = false,
    val isRestoringSession: Boolean = true,
    val isConversationHistoryLoading: Boolean = false,
    val ttsEngineMode: String = "auto",
    val isVoicePackInstalled: Boolean = false,
    val isDownloadingVoicePack: Boolean = false,
    val voicePackDownloadProgress: Float = 0f,
    val voicePackStatusMessage: String = ""
)

class OrkiViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val STARTUP_TAG = "OrkiStartup"
    }

    // getSharedPreferences() starts its disk load without waiting for it. All preference reads are
    // performed on Dispatchers.IO below so a slow disk cannot hold the first Compose frame.
    private val prefs = UserPreferences(application)

    private val databaseDelegate = lazy { AppDatabase.getInstance(application) }
    private val database by databaseDelegate
    private val dao get() = database.conversationDao()

    private val apiServiceDelegate = lazy { OrkiApiService() }
    private val apiService by apiServiceDelegate
    private val billingManagerDelegate = lazy { PlayBillingManager(application, viewModelScope) }
    private val billingManager by billingManagerDelegate
    private val imageService by lazy { ImageGenerationService(application) }

    // AudioManager is cheap to construct; its disk cache is itself lazy (see AudioManager).
    val audioManager = AudioManager(application, viewModelScope)

    val voicePackManager by lazy { com.example.data.audio.BodoVoicePackManager(application) }
    private var neuralEngine: com.example.data.audio.BodoNeuralTtsEngine? = null

    private val optionalServicesStarted = AtomicBoolean(false)
    private val activeUserEmailFlow = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val conversations: StateFlow<List<ConversationEntity>> = activeUserEmailFlow
        .flatMapLatest { email ->
            if (email.isBlank()) {
                flowOf(emptyList()) // Guests or logged out users do not see previous user chats.
            } else {
                // Creating/opening Room and obtaining the initial rows are never startup-main-thread work.
                flow { emitAll(dao.getConversationsForUser(email)) }
                    .flowOn(Dispatchers.IO)
                    .onStart {
                        _uiState.value = _uiState.value.copy(isConversationHistoryLoading = true)
                    }
                    .onEach {
                        _uiState.value = _uiState.value.copy(isConversationHistoryLoading = false)
                    }
                    .catch { error ->
                        Log.e(STARTUP_TAG, "Conversation history initialization failed", error)
                        _uiState.value = _uiState.value.copy(
                            isConversationHistoryLoading = false,
                            errorMessage = "Chat history could not be loaded. You can still start a new chat."
                        )
                        emit(emptyList())
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val audioPlayerState: StateFlow<AudioPlayerState> = audioManager.playerState
    val amplitudeFlow: StateFlow<Float> = audioManager.amplitudeFlow

    // A usable Guest state is available synchronously. Saved account/settings data replaces only
    // the preference-backed fields as soon as its background read completes.
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var activeStreamJob: Job? = null
    private var activeConversationJob: Job? = null

    // In-memory messages for incognito mode
    private val incognitoMessages = mutableListOf<ChatMessageEntity>()

    init {
        val restoreStartedAtMs = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            try {
                val restored = withContext(Dispatchers.IO) { readSavedUiState() }
                applyRestoredUiState(restored)
                activeUserEmailFlow.value = restored.userEmail
                Log.i(
                    STARTUP_TAG,
                    "saved_state_restored_ms=${SystemClock.elapsedRealtime() - restoreStartedAtMs}"
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(STARTUP_TAG, "Saved state initialization failed; continuing with defaults", error)
                _uiState.value = _uiState.value.copy(
                    isRestoringSession = false,
                    errorMessage = "Some saved settings could not be restored. Orki is ready with defaults."
                )
            }
        }
    }

    /**
     * Called by MainActivity after the first frame is drawn. There is no timer or artificial delay:
     * optional network/Billing setup simply cannot compete with the first visible frame.
     */
    fun onFirstFrameDrawn() {
        startOptionalServicesIfNeeded()
    }

    private fun startOptionalServicesIfNeeded() {
        if (!optionalServicesStarted.compareAndSet(false, true)) return

        viewModelScope.launch {
            try {
                val manager = withContext(Dispatchers.IO) {
                    billingManager.also { it.startConnection() }
                }
                launch {
                    manager.purchasedPlan.collect { plan ->
                        if (plan != null) upgradePlan(plan)
                    }
                }
                launch {
                    manager.billingMessage.collect { message ->
                        _uiState.value = _uiState.value.copy(errorMessage = message)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Billing is optional at launch. Keep chat usable and retry when purchase is opened.
                optionalServicesStarted.set(false)
                Log.e(STARTUP_TAG, "Optional Billing initialization failed", error)
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                apiService.warmUpEdgeFunctions()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Warm-up is best effort; actual requests retain their normal error handling.
                Log.w(STARTUP_TAG, "Optional AI endpoint warm-up failed", error)
            }
        }
    }

    private fun readSavedUiState(): UiState {
        val loggedIn = prefs.isLoggedIn
        val email = if (loggedIn) prefs.userEmail else ""
        val plan = if (loggedIn) prefs.currentPlan else "Guest"
        return UiState(
            script = prefs.script,
            uiLanguage = prefs.uiLanguage,
            userName = if (loggedIn) prefs.userName.ifBlank { "User" } else "Guest",
            userPersona = prefs.userPersona,
            currentPlan = plan,
            dailyUsage = prefs.getDailyUsage(email),
            dailyLimit = prefs.getDailyLimit(plan),
            dailyUploadUsage = prefs.getDailyUploadUsage(email),
            dailyUploadLimit = prefs.getDailyUploadLimit(plan),
            selectedModel = prefs.selectedModel,
            selectedVoice = prefs.selectedVoice,
            isIncognito = prefs.isIncognito,
            isLoggedIn = loggedIn,
            isEmailVerified = loggedIn && prefs.isEmailVerified,
            authMethod = if (loggedIn) prefs.authMethod else "Guest",
            userEmail = email,
            isRestoringSession = false,
            ttsEngineMode = prefs.ttsEngineMode,
            isVoicePackInstalled = voicePackManager.isVoicePackInstalled() || run {
                val rootModel = java.io.File("/app/applet/model.onnx")
                val rootTokens = java.io.File("/app/applet/tokens.txt")
                if (rootModel.exists() && rootTokens.exists()) {
                    voicePackManager.installFromLocalFiles(rootModel, rootTokens)
                } else false
            }
        )
    }

    private fun applyRestoredUiState(restored: UiState) {
        // Preserve any transient interaction that happened during the very short background restore.
        val current = _uiState.value
        _uiState.value = current.copy(
            script = restored.script,
            uiLanguage = restored.uiLanguage,
            userName = restored.userName,
            userPersona = restored.userPersona,
            currentPlan = restored.currentPlan,
            dailyUsage = restored.dailyUsage,
            dailyLimit = restored.dailyLimit,
            dailyUploadUsage = restored.dailyUploadUsage,
            dailyUploadLimit = restored.dailyUploadLimit,
            selectedModel = restored.selectedModel,
            selectedVoice = restored.selectedVoice,
            isIncognito = restored.isIncognito,
            isLoggedIn = restored.isLoggedIn,
            isEmailVerified = restored.isEmailVerified,
            authMethod = restored.authMethod,
            userEmail = restored.userEmail,
            isRestoringSession = false,
            isConversationHistoryLoading = restored.userEmail.isNotBlank(),
            ttsEngineMode = restored.ttsEngineMode,
            isVoicePackInstalled = restored.isVoicePackInstalled
        )
    }

    fun startNewChat() {
        cancelGeneration()
        audioManager.stopPlayback()
        _uiState.value = _uiState.value.copy(
            currentConversationId = null,
            messages = emptyList(),
            currentStreamingResponse = ""
        )
    }

    fun selectConversation(id: String) {
        if (_uiState.value.isIncognito) return
        cancelGeneration()
        audioManager.stopPlayback()

        activeConversationJob?.cancel()
        _uiState.value = _uiState.value.copy(
            currentConversationId = id,
            currentStreamingResponse = ""
        )

        activeConversationJob = viewModelScope.launch {
            dao.getMessagesForConversation(id).collect { msgs ->
                _uiState.value = _uiState.value.copy(messages = msgs)
            }
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            dao.deleteConversation(id)
            if (_uiState.value.currentConversationId == id) {
                startNewChat()
            }
        }
    }

    fun clearAllConversations() {
        viewModelScope.launch {
            dao.clearAllConversations()
            startNewChat()
        }
    }

    fun toggleIncognito() {
        val next = !_uiState.value.isIncognito
        prefs.isIncognito = next
        incognitoMessages.clear()
        _uiState.value = _uiState.value.copy(
            isIncognito = next,
            currentConversationId = null,
            messages = emptyList(),
            currentStreamingResponse = ""
        )
    }

    fun setModel(model: String) {
        if (model == "okafwr-2.1" && _uiState.value.currentPlan != "Pro") {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Okafwr 2.1 requires an active Pro subscription."
            )
            return
        }
        prefs.selectedModel = model
        _uiState.value = _uiState.value.copy(selectedModel = model)
    }

    fun updateSettings(
        script: String,
        uiLang: String,
        name: String,
        persona: String,
        voice: String,
        ttsSpeed: Float = prefs.ttsSpeed,
        ttsPitch: Float = prefs.ttsPitch
    ) {
        prefs.script = script
        prefs.uiLanguage = uiLang
        prefs.userName = name
        prefs.userPersona = persona
        prefs.selectedVoice = voice
        prefs.ttsSpeed = ttsSpeed
        prefs.ttsPitch = ttsPitch
        _uiState.value = _uiState.value.copy(
            script = script,
            uiLanguage = uiLang,
            userName = name,
            userPersona = persona,
            selectedVoice = voice
        )
    }

    fun clearTtsCache() {
        audioManager.clearTtsCache()
    }

    fun previewVoice(voiceId: String) {
        val profile = VoiceOptions.get(voiceId)
        audioManager.playRawResource(
            resId = profile.rawResId,
            ttsText = profile.samplePhrase,
            pitch = prefs.ttsPitch,
            speed = prefs.ttsSpeed
        )
    }

    fun launchPlayBillingFlow(activity: Activity, plan: String, cycle: String) {
        startOptionalServicesIfNeeded()
        billingManager.launchPurchaseFlow(activity, plan, cycle)
    }

    fun signIn(email: String, name: String, method: String = "Google", verified: Boolean = true) {
        val cleanEmail = email.trim()
        val displayName = if (name.isNotBlank()) name else (cleanEmail.substringBefore("@").replaceFirstChar { it.uppercase() })
        val userPlan = prefs.getPlanForEmail(cleanEmail) ?: "Free"

        prefs.hasExplicitlyLoggedIn = true
        prefs.isLoggedIn = true
        prefs.isEmailVerified = verified
        prefs.authMethod = method
        prefs.userEmail = cleanEmail
        prefs.userName = displayName
        prefs.currentPlan = userPlan

        cancelGeneration()
        audioManager.stopPlayback()
        activeUserEmailFlow.value = cleanEmail

        val limit = prefs.getDailyLimit(userPlan)
        val usage = prefs.getDailyUsage(cleanEmail)
        val uploadLimit = prefs.getDailyUploadLimit(userPlan)
        val uploadUsage = prefs.getDailyUploadUsage(cleanEmail)

        _uiState.value = _uiState.value.copy(
            isLoggedIn = true,
            isEmailVerified = verified,
            authMethod = method,
            userEmail = cleanEmail,
            userName = displayName,
            currentPlan = userPlan,
            dailyLimit = limit,
            dailyUsage = usage,
            dailyUploadLimit = uploadLimit,
            dailyUploadUsage = uploadUsage,
            currentConversationId = null,
            messages = emptyList(),
            currentStreamingResponse = "",
            isConversationHistoryLoading = true
        )
    }

    fun signOut() {
        cancelGeneration()
        audioManager.stopPlayback()

        prefs.hasExplicitlyLoggedIn = false
        prefs.isLoggedIn = false
        prefs.isEmailVerified = false
        prefs.authMethod = "Guest"
        prefs.userEmail = ""
        prefs.userName = "Guest"
        prefs.currentPlan = "Guest"
        prefs.resetGuestUsage()

        activeUserEmailFlow.value = ""

        val guestLimit = prefs.getDailyLimit("Guest")
        val guestUploadLimit = prefs.getDailyUploadLimit("Guest")

        _uiState.value = _uiState.value.copy(
            isLoggedIn = false,
            isEmailVerified = false,
            authMethod = "Guest",
            userEmail = "",
            userName = "Guest",
            currentPlan = "Guest",
            dailyLimit = guestLimit,
            dailyUsage = 0,
            dailyUploadLimit = guestUploadLimit,
            dailyUploadUsage = 0,
            currentConversationId = null,
            messages = emptyList(),
            currentStreamingResponse = "",
            isConversationHistoryLoading = false
        )
    }

    fun upgradePlan(plan: String) {
        prefs.currentPlan = plan
        if (_uiState.value.userEmail.isNotBlank()) {
            prefs.setPlanForEmail(_uiState.value.userEmail, plan)
        }
        val newLimit = prefs.getDailyLimit(plan)
        val newUploadLimit = prefs.getDailyUploadLimit(plan)
        _uiState.value = _uiState.value.copy(
            currentPlan = plan,
            dailyLimit = newLimit,
            dailyUploadLimit = newUploadLimit
        )
    }

    fun attachFile(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessingFile = true)
            val attached = FileUploadHelper.processUri(getApplication(), uri)
            if (attached != null) {
                _uiState.value = _uiState.value.copy(
                    attachedFile = attached,
                    isProcessingFile = false
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isProcessingFile = false,
                    errorMessage = "Could not process file. Please select a valid Image, TXT, or PDF file."
                )
            }
        }
    }

    fun attachCapturedBitmap(bitmap: android.graphics.Bitmap) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isProcessingFile = true)
                val file = java.io.File(getApplication<android.app.Application>().cacheDir, "camera_${System.currentTimeMillis()}.jpg")
                java.io.FileOutputStream(file).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                }
                val attached = FileUploadHelper.processUri(getApplication(), android.net.Uri.fromFile(file))
                if (attached != null) {
                    _uiState.value = _uiState.value.copy(
                        attachedFile = attached,
                        isProcessingFile = false
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isProcessingFile = false,
                        errorMessage = "Could not process captured photo."
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessingFile = false,
                    errorMessage = "Failed to attach photo: ${e.localizedMessage}"
                )
            }
        }
    }

    fun removeAttachedFile() {
        _uiState.value = _uiState.value.copy(attachedFile = null)
    }

    fun checkUploadQuota(): Boolean {
        if (_uiState.value.currentPlan == "Guest" || !_uiState.value.isLoggedIn) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "File attachments and uploads are reserved for registered users. Please sign in or create an account.",
                triggerLoginDialog = true
            )
            return false
        }
        val currentEmail = _uiState.value.userEmail
        if (!prefs.canUpload(_uiState.value.currentPlan, currentEmail)) {
            val limit = prefs.getDailyUploadLimit(_uiState.value.currentPlan)
            val plan = _uiState.value.currentPlan
            val msg = if (plan == "Free") {
                "Free upload limit reached ($limit/2 uploads used today). Upgrade to Plus or Pro for more uploads!"
            } else {
                "$plan upload limit of $limit reached today. Upgrade to Pro for maximum uploads!"
            }
            _uiState.value = _uiState.value.copy(
                errorMessage = msg,
                triggerUpgradeDialog = true
            )
            return false
        }
        return true
    }

    fun clearTriggerUpgradeDialog() {
        _uiState.value = _uiState.value.copy(triggerUpgradeDialog = false)
    }

    fun clearTriggerLoginDialog() {
        _uiState.value = _uiState.value.copy(triggerLoginDialog = false)
    }

    fun clearErrorMessage() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun checkQuota(): Boolean {
        val currentEmail = if (_uiState.value.isLoggedIn) _uiState.value.userEmail else ""
        val currentUsage = prefs.getDailyUsage(currentEmail)
        val limit = prefs.getDailyLimit(_uiState.value.currentPlan)
        if (currentUsage >= limit) {
            val isGuest = _uiState.value.currentPlan == "Guest" || !_uiState.value.isLoggedIn
            val msg = if (isGuest) {
                "Guest limit reached (4/4 messages). Please sign in or create a free account to continue chatting!"
            } else {
                "Daily limit of $limit messages reached. Upgrade your plan for more quota!"
            }
            _uiState.value = _uiState.value.copy(
                errorMessage = msg,
                triggerLoginDialog = isGuest,
                triggerUpgradeDialog = !isGuest
            )
            return false
        }
        return true
    }

    data class VisualIntent(val type: String, val prompt: String)

    private fun extractVisualIntent(rawText: String): VisualIntent? {
        val t = rawText.trim()
        if (t.isEmpty()) return null
        val lower = t.lowercase()

        // 1. Explicit Slash Commands
        val slashImageRegex = Regex("^/(image|imagine|draw|img)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val slashImageMatch = slashImageRegex.find(t)
        if (slashImageMatch != null) {
            return VisualIntent("image", slashImageMatch.groupValues[2].trim())
        }

        // 2. Natural Image Request Intents (Handles 'create an image of', 'draw me a', 'picture of', etc.)
        val imagePattern = Regex(
            "^(?:hey\\s+orki\\s*,?\\s*|can\\s+you\\s+(?:please\\s+)?|could\\s+you\\s+(?:please\\s+)?|please\\s+|i\\s+want\\s+(?:you\\s+to\\s+|a\\s+)?|make\\s+me\\s+a\\s+|give\\s+me\\s+a\\s+|show\\s+me\\s+a\\s+)?(?:generate|create|ceeate|make|render|produce|draw|paint|illustrate|show|give)?\\s*(?:an?\\s+)?(?:ai\\s+)?(?:image|inage|picture|piture|photo|artwork|drawing|illustration|painting|wallpaper)\\s*(?:of|about|showing|with|for|depicting)?\\s*(.+)$",
            RegexOption.IGNORE_CASE
        )
        val imageMatch = imagePattern.find(t)
        if (imageMatch != null && imageMatch.groupValues[1].isNotBlank()) {
            val candidate = imageMatch.groupValues[1].trim()
            if (candidate.length >= 2) {
                return VisualIntent("image", candidate)
            }
        }

        // 3. Drawing Commands (e.g. 'draw a cat', 'draw me a girl in dokhona')
        val drawPattern = Regex(
            "^(?:can\\s+you\\s+(?:please\\s+)?|could\\s+you\\s+(?:please\\s+)?|please\\s+)?(?:draw|paint|illustrate)\\s*(?:me\\s+)?(?:an?\\s+)?(.+)$",
            RegexOption.IGNORE_CASE
        )
        val drawMatch = drawPattern.find(t)
        if (drawMatch != null && drawMatch.groupValues[1].isNotBlank()) {
            val candidate = drawMatch.groupValues[1].trim()
            if (candidate.length >= 2) {
                return VisualIntent("image", candidate)
            }
        }

        // 4. Bodo Language Triggers (Devanagari & Romanized)
        // Devanagari: "छबि बानाय", "बानाय छबि", "फोटो बानाय", "नुथाइ दिन्थि", "सैमानि छबि बानाय"
        if (lower.contains("छबि") || lower.contains("फोटो") || lower.contains("फट'") || lower.contains("नुथाइ")) {
            val clean = t.replace(Regex("छबि|फोटो|फट'|नुथाइ|बानाय|दिन्थि|हर|हरो|मोनसे|मासे|सानसे|नि"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (clean.isNotEmpty()) return VisualIntent("image", clean)
        }

        // Romanized Bodo: e.g. "chobi banay", "photo banay", "swimani chobi banay", "mase swimani photo", "chobi banna hordo", "chobi dakhaw"
        val romanizedBodoPrefixRegex = Regex(
            "^(?:angno\\s+)?(?:chobi|photo|fotho|nuthai)\\s+(?:banay|bana|banna|banna\\s+hordo|dakhaw|dekhao|dinthi|hor|hordo)\\s*(.+)?$",
            RegexOption.IGNORE_CASE
        )
        val romMatch = romanizedBodoPrefixRegex.find(t)
        if (romMatch != null) {
            val trailing = romMatch.groupValues[1].trim()
            val clean = if (trailing.isNotEmpty()) trailing else t.replace(Regex("(?i)chobi|photo|fotho|nuthai|banay|bana|banna|hordo|dakhaw|dekhao|dinthi|hor|angno"), "").trim()
            if (clean.isNotEmpty()) return VisualIntent("image", clean)
        }

        val romanizedBodoSuffixRegex = Regex(
            "^(.+?)\\s*(?:ni\\s+)?(?:chobi|photo|fotho|nuthai)\\s*(?:banay|bana|banna|banna\\s+hordo|dakhaw|dekhao|dinthi|hor|hordo)?$",
            RegexOption.IGNORE_CASE
        )
        val suffixMatch = romanizedBodoSuffixRegex.find(t)
        if (suffixMatch != null && (lower.contains("chobi") || lower.contains("photo") || lower.contains("fotho") || lower.contains("nuthai"))) {
            val subjectCandidate = suffixMatch.groupValues[1].trim()
                .replace(Regex("(?i)^(?:angno\\s+|please\\s+|hey\\s+orki\\s*,?\\s*)"), "")
                .trim()
            if (subjectCandidate.isNotEmpty() && subjectCandidate.length >= 2) {
                return VisualIntent("image", subjectCandidate)
            }
        }

        return null
    }

    fun sendMessage(userText: String) {
        val attachment = _uiState.value.attachedFile
        val trimmed = userText.trim()
        if (trimmed.isEmpty() && attachment == null) return
        if (_uiState.value.isGenerating) return

        // Intelligent Visual Intent Detection (Natural language Image requests + slash commands)
        val visualIntent = extractVisualIntent(trimmed)
        if (visualIntent != null && attachment == null) {
            if (visualIntent.type == "image") {
                generateImage(visualIntent.prompt)
                return
            }
        }

        if (!checkQuota()) return

        if (attachment != null) {
            if (!checkUploadQuota()) return
        }

        val promptText = if (trimmed.isNotEmpty()) {
            trimmed
        } else when (attachment?.fileType) {
            "image" -> "Please analyze this image and explain what you see."
            "pdf" -> "Please summarize and explain the key details of this PDF document."
            "txt" -> "Please analyze and summarize this text file."
            else -> "Please analyze this attachment."
        }

        val userMessage = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = _uiState.value.currentConversationId ?: "",
            role = "user",
            text = promptText,
            attachmentName = attachment?.name,
            attachmentType = attachment?.fileType,
            attachmentSize = attachment?.sizeBytes,
            attachmentUri = attachment?.uri?.toString(),
            timestamp = System.currentTimeMillis()
        )

        val updatedMessages = _uiState.value.messages + userMessage
        _uiState.value = _uiState.value.copy(
            messages = updatedMessages,
            isGenerating = true,
            isThinking = true,
            currentStreamingResponse = "",
            attachedFile = null
        )

        executeStreamingChat(
            conversationSnapshot = updatedMessages,
            audioPayload = null,
            attachmentPayload = attachment,
            liveMode = false
        )
    }

    fun generateImage(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return
        if (_uiState.value.isGenerating) return

        if (!checkQuota()) return

        val userMessage = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = _uiState.value.currentConversationId ?: "",
            role = "user",
            text = "🎨 /image $trimmed",
            timestamp = System.currentTimeMillis()
        )

        val updatedMessages = _uiState.value.messages + userMessage
        _uiState.value = _uiState.value.copy(
            messages = updatedMessages,
            isGenerating = true,
            isThinking = false,
            isGeneratingImage = true,
            imageGenProgress = 6,
            imageGenStage = "Connecting to Orki AI Worker...",
            imageGenPrompt = trimmed,
            currentStreamingResponse = ""
        )

        activeStreamJob = viewModelScope.launch {
            // Step 1: Gemini AI Middleman - Enhance the prompt with authentic cultural & visual details
            _uiState.value = _uiState.value.copy(
                imageGenProgress = 12,
                imageGenStage = "✨ Gemini AI: Enhancing prompt with cultural & visual details..."
            )

            val enhancedPrompt = try {
                val candidate = apiService.enhanceVisualPrompt(trimmed, isVideo = false)
                if (com.example.data.network.BodoVisualVocabulary.validateEnhancedPrompt(trimmed, candidate)) {
                    candidate
                } else {
                    com.example.data.network.BodoVisualVocabulary.resolveBodoToEnglishVisual(trimmed, isVideo = false)
                }
            } catch (e: Exception) {
                com.example.data.network.BodoVisualVocabulary.resolveBodoToEnglishVisual(trimmed, isVideo = false)
            }

            val finalPrompt = if (com.example.data.network.BodoVisualVocabulary.validateEnhancedPrompt(trimmed, enhancedPrompt)) {
                enhancedPrompt
            } else {
                com.example.data.network.BodoVisualVocabulary.resolveBodoToEnglishVisual(trimmed, isVideo = false)
            }

            _uiState.value = _uiState.value.copy(
                imageGenProgress = 25,
                imageGenStage = "Connecting to Orki AI GPU Cluster..."
            )

            // Live progressive ticker simulating real-time diffusion stages up to 96%
            val tickerJob = launch {
                val milestones = listOf(
                    40 to "Encoding prompt into text latents...",
                    60 to "Sampling diffusion steps (GPU Pass 1)...",
                    78 to "Denoising & refining composition...",
                    88 to "Enhancing details & color grading...",
                    94 to "Finalizing PNG artwork bytes..."
                )
                var currentPercent = 25
                for ((targetPercent, stageText) in milestones) {
                    _uiState.value = _uiState.value.copy(imageGenStage = stageText)
                    while (currentPercent < targetPercent) {
                        currentPercent += (1..3).random()
                        if (currentPercent > targetPercent) currentPercent = targetPercent
                        _uiState.value = _uiState.value.copy(imageGenProgress = currentPercent)
                        delay((180L..340L).random())
                    }
                }
                while (currentPercent < 97) {
                    delay(450)
                    currentPercent += 1
                    _uiState.value = _uiState.value.copy(imageGenProgress = currentPercent)
                }
            }

            try {
                val result = imageService.generateImage(
                    prompt = finalPrompt,
                    workerUrl = prefs.imageWorkerUrl,
                    apiKey = prefs.imageApiKey,
                    pollinationsKey = prefs.pollinationsApiKey,
                    preferredEngine = prefs.imageEnginePreference,
                    azureEndpoint = prefs.azureOpenAiEndpoint,
                    azureKey = prefs.azureOpenAiKey,
                    azureDalleDeployment = prefs.azureDalleDeployment,
                    onStatusUpdate = { stageText, isFallback ->
                        _uiState.value = _uiState.value.copy(
                            imageGenStage = stageText,
                            isImageGenFallback = isFallback,
                            imageGenEngine = if (isFallback) "Orki Fallback Engine" else if (prefs.imageEnginePreference == "cloudflare") "Cloudflare Workers AI" else "Orki Image Studio (Flux)"
                        )
                    }
                )
                tickerJob.cancel()

                result.onSuccess { generated ->
                    val imageFile = generated.file
                    _uiState.value = _uiState.value.copy(
                        imageGenProgress = 100,
                        imageGenStage = "Artwork generated via ${generated.engineName}!",
                        imageGenEngine = generated.engineName
                    )
                    delay(250)

                    val responseText = if (finalPrompt.equals(trimmed, ignoreCase = true)) {
                        "Here is your generated image for: \"$trimmed\"\n\n🎨 *Rendered via ${generated.engineName}*"
                    } else {
                        "Here is your generated image for: \"$trimmed\"\n\n✨ **Enhanced prompt used:**\n$finalPrompt\n\n🎨 *Rendered via ${generated.engineName}*"
                    }

                    val assistantMessage = ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        conversationId = _uiState.value.currentConversationId ?: "",
                        role = "model",
                        text = responseText,
                        attachmentName = imageFile.name,
                        attachmentType = "generated_image",
                        attachmentSize = imageFile.length(),
                        attachmentUri = imageFile.absolutePath,
                        timestamp = System.currentTimeMillis()
                    )

                    val finalizedMessages = _uiState.value.messages + assistantMessage
                    val activeEmail = if (_uiState.value.isLoggedIn) _uiState.value.userEmail else ""
                    val newUsage = prefs.incrementDailyUsage(activeEmail)

                    _uiState.value = _uiState.value.copy(
                        messages = finalizedMessages,
                        isGenerating = false,
                        isThinking = false,
                        isGeneratingImage = false,
                        imageGenProgress = 0,
                        imageGenStage = "",
                        imageGenPrompt = "",
                        isImageGenFallback = false,
                        imageGenEngine = "Orki AI (Cloudflare)",
                        dailyUsage = newUsage
                    )

                    persistThread(finalizedMessages)
                }.onFailure { error ->
                    val assistantError = ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        conversationId = _uiState.value.currentConversationId ?: "",
                        role = "model",
                        text = "⚠️ Image generation failed: ${error.localizedMessage ?: "Unknown error"}. Both Cloudflare and Pollinations AI were attempted.",
                        timestamp = System.currentTimeMillis()
                    )
                    val finalizedMessages = _uiState.value.messages + assistantError
                    _uiState.value = _uiState.value.copy(
                        messages = finalizedMessages,
                        isGenerating = false,
                        isThinking = false,
                        isGeneratingImage = false,
                        imageGenProgress = 0,
                        imageGenStage = "",
                        imageGenPrompt = "",
                        isImageGenFallback = false,
                        imageGenEngine = "Orki AI (Cloudflare)",
                        errorMessage = "Image generation failed: ${error.localizedMessage}"
                    )
                    persistThread(finalizedMessages)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                tickerJob.cancel()
                throw e
            } catch (e: Exception) {
                tickerJob.cancel()
                _uiState.value = _uiState.value.copy(
                    isGenerating = false,
                    isThinking = false,
                    isGeneratingImage = false,
                    imageGenProgress = 0,
                    imageGenStage = "",
                    imageGenPrompt = "",
                    isImageGenFallback = false,
                    imageGenEngine = "Orki AI (Cloudflare)"
                )
            }
        }
    }

    fun generateVideo(prompt: String) {
        // Video generation disabled per user request
    }

    fun sendGeneratedImageToChat(prompt: String, imageFile: File) {
        val userMessage = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = _uiState.value.currentConversationId ?: "",
            role = "user",
            text = "🎨 /image $prompt",
            timestamp = System.currentTimeMillis()
        )
        val assistantMessage = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = _uiState.value.currentConversationId ?: "",
            role = "model",
            text = "Here is your generated image for: \"$prompt\"",
            attachmentName = imageFile.name,
            attachmentType = "generated_image",
            attachmentSize = imageFile.length(),
            attachmentUri = imageFile.absolutePath,
            timestamp = System.currentTimeMillis()
        )
        val updated = _uiState.value.messages + userMessage + assistantMessage
        val activeEmail = if (_uiState.value.isLoggedIn) _uiState.value.userEmail else ""
        val newUsage = prefs.incrementDailyUsage(activeEmail)
        _uiState.value = _uiState.value.copy(
            messages = updated,
            dailyUsage = newUsage
        )
        viewModelScope.launch {
            persistThread(updated)
        }
    }

    fun startVoiceRecording(liveMode: Boolean = false) {
        if (_uiState.value.isGenerating) return
        if (!checkQuota()) return

        audioManager.stopPlayback()
        val started = audioManager.startRecording(
            autoSilenceDetection = liveMode,
            onSilenceDetected = {
                if (_uiState.value.liveTalkStatus == LiveTalkStatus.LISTENING) {
                    stopVoiceRecording(liveMode = true)
                }
            }
        )

        if (started) {
            _uiState.value = _uiState.value.copy(
                isRecording = true,
                liveTalkStatus = if (liveMode) LiveTalkStatus.LISTENING else LiveTalkStatus.IDLE,
                liveTalkTranscript = if (liveMode) "Listening…" else ""
            )
        } else {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Could not start audio recorder. Check microphone permissions."
            )
            if (liveMode) stopLiveTalk()
        }
    }

    fun stopVoiceRecording(liveMode: Boolean = false) {
        val file = audioManager.stopRecording()
        _uiState.value = _uiState.value.copy(isRecording = false)

        if (file == null || file.length() < 500) {
            if (liveMode && _uiState.value.liveTalkStatus != LiveTalkStatus.IDLE) {
                // Keep listening in Live Mode if nothing recorded
                startVoiceRecording(liveMode = true)
            }
            return
        }

        val base64Audio = audioManager.fileToBase64(file)
        file.delete()

        if (base64Audio == null) {
            if (liveMode) stopLiveTalk()
            return
        }

        handleAudioSend(base64Audio, "audio/mp4", liveMode)
    }

    private fun handleAudioSend(base64Audio: String, mimeType: String, liveMode: Boolean) {
        val userVoiceMsg = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = _uiState.value.currentConversationId ?: "",
            role = "user",
            text = "🎙️ [Voice Note]",
            timestamp = System.currentTimeMillis()
        )

        val updatedMessages = _uiState.value.messages + userVoiceMsg
        _uiState.value = _uiState.value.copy(
            messages = updatedMessages,
            isGenerating = true,
            isThinking = true,
            currentStreamingResponse = "",
            liveTalkStatus = if (liveMode) LiveTalkStatus.THINKING else LiveTalkStatus.IDLE,
            liveTalkTranscript = if (liveMode) "Thinking…" else ""
        )

        executeStreamingChat(
            conversationSnapshot = updatedMessages,
            audioPayload = Pair(base64Audio, mimeType),
            attachmentPayload = null,
            liveMode = liveMode
        )
    }

    private fun executeStreamingChat(
        conversationSnapshot: List<ChatMessageEntity>,
        audioPayload: Pair<String, String>?,
        attachmentPayload: AttachedFile? = null,
        liveMode: Boolean = false
    ) {
        activeStreamJob?.cancel()
        activeStreamJob = viewModelScope.launch {
            val script = _uiState.value.script
            val scriptConstraint = if (script == "roman") {
                "\n[Constraint for Orki Response:\n" +
                "1. DISPLAY PART: Provide conversational reply in natural colloquial Roman Bodo (using 'w', 'dong', 'mwjang', etc.).\n" +
                "2. TTS SYNTHESIS PART: You MUST follow immediately on a brand new line with exactly '---TTS---'.\n" +
                "3. After '---TTS---', output the EXACT same reply converted into pure, phonetically accurate Bodo Devanagari script (बर' हांखो) specifically optimized for the speech synthesizer:\n" +
                "   - NO markdown formatting (no bold **, no headers #, no bullet points -).\n" +
                "   - NO emojis in the TTS section (emojis break pronunciation).\n" +
                "   - English & loan words MUST be transliterated phonetically into Devanagari so the Bodo voice model can pronounce them (e.g. 'Spanish' -> 'स्पेनिस', 'phone' -> 'फोन', 'AI' -> 'एआइ', 'YouTube' -> 'इउथुब', 'computer' -> 'कम्प्युटर', 'app' -> 'एप'). Never leave English letters in the TTS line!\n" +
                "   - Numbers MUST be spelled out in Bodo words (e.g. 1 -> से, 2 -> नै, 3 -> थाम, etc.).\n" +
                "   - Use commas (,) and danda (।) or periods (.) for natural breathing pauses.\n" +
                "Example format:\n" +
                "Oi jwmwi! Spanish raylainw nagirdwng nama? ma khobor nwngna?\n" +
                "---TTS---\n" +
                "ओइ जोमै! स्पेनिस रायलायनो नागिरदों नामा? मा खबर नोंना?]"
            } else {
                "\n[Constraint for Orki Response:\n" +
                "1. DISPLAY PART: Reply strictly in Bodo Devanagari script (बर' हांखो).\n" +
                "2. TTS SYNTHESIS PART: Always append '---TTS---' followed by a clean, pure speech-ready version:\n" +
                "   - Strip all markdown symbols (**, ##, bullets, lists).\n" +
                "   - Strip all emojis.\n" +
                "   - Spell out numbers in Bodo words (से, नै, थाम).\n" +
                "   - Phonetically write any English or loan terms in Devanagari (e.g. फोन, स्पेनिस).]"
            }
            val livePromptConstraint = if (liveMode) {
                "\n[LIVE VOICE MODE: You are conversing in a live audio call. Keep your answer strictly to 1 or 2 ultra-concise, natural spoken sentences in Bodo. No lists, no bullets, no markdown.]"
            } else ""

            val bodoKnowledgeBase = "\n[Bodo Language & Vocabulary Knowledge Base:\n" +
                "You are Orki, an authentic conversational AI fluent in Bodo (बर' राव). Accurately understand both colloquial Roman Bodo and standard Bodo Devanagari:\n" +
                "- 'swima' (also written 'suima', 'saima' / सैमा) = DOG.\n" +
                "- 'mousi' (also written 'maosi', 'maoji' / मावजि, मावसि) = CAT. (CRITICAL: Do NOT confuse with Hindi 'mousi/mausi' which means maternal aunt; in Bodo, 'mousi'/'maosi' refers to cat / मावजि).\n" +
                "- 'mwsou' / 'mosou' (मसौ) = COW / cattle; 'mwisw' / 'maisu' (मैस') = BUFFALO; 'oma' (अमा) = PIG; 'dao' (दाव) = BIRD / CHICKEN; 'na' (ना) = FISH.\n" +
                "- 'dwi' (दै) = water, 'onkham' / 'wngkham' (ओंखाम) = cooked rice / meal, 'nokhor' (नखर) = family / household, 'hagra' (हाग्रा) = jungle / forest, 'fwrwng' / 'solong' = teach / learn.\n" +
                "- Always interpret colloquial Roman Bodo naturally and correctly, respecting authentic Bodo definitions and culture.]"

            val fullConstraints = "$scriptConstraint$bodoKnowledgeBase$livePromptConstraint"

            val memoryDepth = when (_uiState.value.currentPlan) {
                "Guest" -> 4
                "Free" -> 6
                "Plus" -> 12
                "Pro" -> 24
                else -> 6
            }

            val windowed = conversationSnapshot.takeLast(memoryDepth)
            val contentsJson = JSONArray()

            windowed.forEachIndexed { index, msg ->
                val obj = JSONObject()
                obj.put("role", if (msg.role == "user") "user" else "model")
                val parts = JSONArray()

                val isLastMsg = (index == windowed.size - 1)
                if (isLastMsg && audioPayload != null) {
                    val audioObj = JSONObject().apply {
                        put("inlineData", JSONObject().apply {
                            put("mimeType", audioPayload.second)
                            put("data", audioPayload.first)
                        })
                    }
                    parts.put(audioObj)
                    val promptText = "1. Transcribe accurately.\n2. Formulate conversational reply as Orki.\nConstraint: $fullConstraints\n\nOutput MUST strictly follow:\nTRANSCRIPTION: <exact words>\nRESPONSE: <reply>"
                    parts.put(JSONObject().apply { put("text", promptText) })
                } else if (isLastMsg && attachmentPayload != null) {
                    if ((attachmentPayload.fileType == "image" || attachmentPayload.fileType == "pdf") && attachmentPayload.base64Data != null) {
                        val fileObj = JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", attachmentPayload.mimeType)
                                put("data", attachmentPayload.base64Data)
                            })
                        }
                        parts.put(fileObj)
                        val promptText = "Attached file: ${attachmentPayload.name} (${attachmentPayload.fileType.uppercase()})\n${msg.text}" + fullConstraints
                        parts.put(JSONObject().apply { put("text", promptText) })
                    } else if (attachmentPayload.fileType == "txt") {
                        val promptText = "[Document: ${attachmentPayload.name}]\n" +
                                (attachmentPayload.textContent ?: "") +
                                "\n[End of Document]\n\n" + msg.text + fullConstraints
                        parts.put(JSONObject().apply { put("text", promptText) })
                    } else {
                        parts.put(JSONObject().apply { put("text", msg.text + fullConstraints) })
                    }
                } else {
                    val textWithConstraint = if (isLastMsg && msg.role == "user") {
                        msg.text + fullConstraints
                    } else {
                        msg.text
                    }
                    parts.put(JSONObject().apply { put("text", textWithConstraint) })
                }

                obj.put("parts", parts)
                contentsJson.put(obj)
            }

            var fullText = ""
            var transcription = ""

            try {
                apiService.streamChat(
                    plan = _uiState.value.currentPlan,
                    model = _uiState.value.selectedModel,
                    script = script,
                    userPersona = buildPersona(),
                    contentsJson = contentsJson,
                    isLiveMode = liveMode
                ).collect { chunk ->
                    fullText += chunk
                    _uiState.value = _uiState.value.copy(
                        isThinking = false,
                        currentStreamingResponse = fullText
                    )

                    if (liveMode) {
                        if (fullText.contains("RESPONSE:")) {
                            val parts = fullText.split("RESPONSE:")
                            val reply = parts.drop(1).joinToString("RESPONSE:").trimStart()
                            val parsed = OrkiApiService.parseDualResponse(reply)
                            _uiState.value = _uiState.value.copy(
                                liveTalkTranscript = parsed.displayText
                            )
                        } else if (fullText.contains("TRANSCRIPTION:")) {
                            val transcriptPart = fullText.replace("TRANSCRIPTION:", "").trim()
                            _uiState.value = _uiState.value.copy(
                                liveTalkTranscript = if (transcriptPart.isNotEmpty()) "\"$transcriptPart\"" else ""
                            )
                        }
                    }
                }

                // Finalize response
                var finalText = fullText
                if (fullText.contains("RESPONSE:")) {
                    val parts = fullText.split("RESPONSE:")
                    transcription = parts[0].replace("TRANSCRIPTION:", "").trim()
                    finalText = parts.drop(1).joinToString("RESPONSE:").trim()
                }

                val dual = OrkiApiService.parseDualResponse(finalText)

                // Update user transcript if voice
                val finalizedMessages = conversationSnapshot.toMutableList()
                if (audioPayload != null && transcription.isNotEmpty()) {
                    val lastUserIdx = finalizedMessages.indexOfLast { it.role == "user" }
                    if (lastUserIdx != -1) {
                        finalizedMessages[lastUserIdx] = finalizedMessages[lastUserIdx].copy(
                            text = "🎙️ \"$transcription\""
                        )
                    }
                }

                val modelMessage = ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = _uiState.value.currentConversationId ?: "",
                    role = "model",
                    text = dual.displayText,
                    ttsText = dual.ttsText,
                    timestamp = System.currentTimeMillis()
                )
                finalizedMessages.add(modelMessage)

                val activeEmail = if (_uiState.value.isLoggedIn) _uiState.value.userEmail else ""
                val newUsage = prefs.incrementDailyUsage(activeEmail)
                val newUploadUsage = if (attachmentPayload != null) prefs.incrementDailyUploadUsage(activeEmail) else prefs.getDailyUploadUsage(activeEmail)
                _uiState.value = _uiState.value.copy(
                    messages = finalizedMessages,
                    isGenerating = false,
                    isThinking = false,
                    currentStreamingResponse = "",
                    dailyUsage = newUsage,
                    dailyUploadUsage = newUploadUsage
                )

                // Persist to Room if not incognito
                persistThread(finalizedMessages)

                if (liveMode && _uiState.value.liveTalkStatus != LiveTalkStatus.IDLE) {
                    speakAndResumeLiveTalk(dual.ttsText)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isGenerating = false,
                    isThinking = false,
                    currentStreamingResponse = "",
                    errorMessage = "Error: ${e.localizedMessage ?: "Failed to generate response"}"
                )
                if (liveMode) stopLiveTalk()
            }
        }
    }

    private suspend fun persistThread(messages: List<ChatMessageEntity>) {
        if (_uiState.value.isIncognito || messages.isEmpty()) return

        val activeEmail = if (_uiState.value.isLoggedIn) _uiState.value.userEmail.ifBlank { "" } else ""
        var convId = _uiState.value.currentConversationId
        val firstUserText = messages.firstOrNull { it.role == "user" }?.text ?: "New Conversation"
        val title = if (firstUserText.length > 28) firstUserText.take(28) + "…" else firstUserText

        if (convId == null) {
            convId = UUID.randomUUID().toString()
            val conv = ConversationEntity(
                id = convId,
                title = title,
                updatedAt = System.currentTimeMillis(),
                userEmail = activeEmail
            )
            dao.insertConversation(conv)
            _uiState.value = _uiState.value.copy(currentConversationId = convId)
        } else {
            val conv = ConversationEntity(
                id = convId,
                title = title,
                updatedAt = System.currentTimeMillis(),
                userEmail = activeEmail
            )
            dao.insertConversation(conv)
        }

        messages.forEach { msg ->
            dao.insertMessage(msg.copy(conversationId = convId))
        }
    }

    private fun getOrInitNeuralEngine(): com.example.data.audio.BodoNeuralTtsEngine? {
        if (!voicePackManager.isVoicePackInstalled()) return null
        if (neuralEngine == null) {
            val engine = com.example.data.audio.BodoNeuralTtsEngine(voicePackManager.modelFile, voicePackManager.tokensFile)
            if (engine.initialize()) {
                neuralEngine = engine
            }
        }
        return neuralEngine
    }

    fun setTtsEngineMode(mode: String) {
        prefs.ttsEngineMode = mode
        _uiState.value = _uiState.value.copy(ttsEngineMode = mode)
    }

    fun downloadVoicePack() {
        if (_uiState.value.isDownloadingVoicePack) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isDownloadingVoicePack = true,
                voicePackDownloadProgress = 0f,
                voicePackStatusMessage = "Starting download..."
            )
            val res = voicePackManager.downloadVoicePack { progress, status ->
                _uiState.value = _uiState.value.copy(
                    voicePackDownloadProgress = progress,
                    voicePackStatusMessage = status
                )
            }
            res.onSuccess {
                _uiState.value = _uiState.value.copy(
                    isDownloadingVoicePack = false,
                    isVoicePackInstalled = true,
                    voicePackDownloadProgress = 1f,
                    voicePackStatusMessage = "Verified voice pack installed!"
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isDownloadingVoicePack = false,
                    voicePackStatusMessage = "Download failed: ${err.message}"
                )
            }
        }
    }

    fun playTts(text: String, voice: String = _uiState.value.selectedVoice) {
        val clean = OrkiApiService.cleanTextForTTS(text)
        if (clean.isEmpty()) return

        val profile = VoiceOptions.get(voice)
        val speed = prefs.ttsSpeed
        val pitch = prefs.ttsPitch
        val cacheKey = "$voice:$speed:$clean"

        // 1. Check in-memory URL cache
        val cachedUrl = audioManager.ttsUrlCache[cacheKey]
        if (cachedUrl != null) {
            audioManager.playAudioUrl(
                url = cachedUrl,
                ttsText = text,
                pitch = pitch,
                speed = speed
            )
            return
        }

        // 2. Check persistent disk cache (instant 0-latency playback)
        val diskPath = audioManager.getCachedTtsPath(clean, voice)
        if (diskPath != null) {
            audioManager.ttsUrlCache[cacheKey] = diskPath
            audioManager.playAudioUrl(
                url = diskPath,
                ttsText = text,
                pitch = pitch,
                speed = speed
            )
            return
        }

        val mode = prefs.ttsEngineMode // "auto", "offline", "online"
        val useOffline = when (mode) {
            "offline" -> true
            "auto" -> voicePackManager.isVoicePackInstalled()
            else -> false
        }

        if (useOffline) {
            if (!voicePackManager.isVoicePackInstalled()) {
                if (mode == "offline") {
                    // Strictly offline mode: NEVER call AWS silently!
                    audioManager.stopPlayback()
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Offline voice pack is not installed. Download it in Settings to enable offline Bodo speech."
                    )
                    return
                }
                // In auto mode, fallback to online below
            } else {
                viewModelScope.launch {
                    audioManager.setPlayerLoading(text)
                    try {
                        val engine = getOrInitNeuralEngine()
                        if (engine != null) {
                            val outFile = java.io.File(getApplication<Application>().cacheDir, "tts_neural_${voice}_${clean.hashCode()}.wav")
                            val res = engine.synthesizeToWav(clean, voice, speed, outFile)
                            res.onSuccess { wavPath ->
                                audioManager.ttsUrlCache[cacheKey] = wavPath
                                audioManager.playAudioUrl(
                                    url = wavPath,
                                    ttsText = text,
                                    pitch = pitch,
                                    speed = speed
                                )
                            }.onFailure { err ->
                                audioManager.stopPlayback()
                                _uiState.value = _uiState.value.copy(
                                    errorMessage = "Offline TTS error: ${err.message}"
                                )
                            }
                            return@launch
                        }
                    } catch (e: Exception) {
                        audioManager.stopPlayback()
                        _uiState.value = _uiState.value.copy(
                            errorMessage = "Neural TTS error: ${e.message}"
                        )
                        return@launch
                    }
                }
                return
            }
        }

        // Online synthesis (AWS EC2 / swift-worker endpoint)
        viewModelScope.launch {
            audioManager.setPlayerLoading(text)
            val result = apiService.fetchTtsAudioUrl(clean, voice = voice)
            result.onSuccess { url ->
                val savedDiskPath = audioManager.saveTtsToDiskCache(clean, voice, url)
                val finalPath = savedDiskPath ?: url
                audioManager.ttsUrlCache[cacheKey] = finalPath

                audioManager.playAudioUrl(
                    url = finalPath,
                    ttsText = text,
                    pitch = pitch,
                    speed = speed
                )
            }.onFailure { err ->
                audioManager.stopPlayback()
                val friendlyError = when {
                    err.message?.contains("timeout", ignoreCase = true) == true ->
                        "Server-a som la-gasino... khebseni try khalamfin salte ⏳"
                    err.message?.contains("connect", ignoreCase = true) == true || err.message?.contains("network", ignoreCase = true) == true ->
                        "Internet signal gwiya khuma... wifi aba data check khalam 📡"
                    else ->
                        "TTS error: Voice generate khalamnw haya swi. Khebseni try khalamfin."
                }
                _uiState.value = _uiState.value.copy(errorMessage = friendlyError)
            }
        }
    }

    fun startLiveTalk() {
        _uiState.value = _uiState.value.copy(
            liveTalkStatus = LiveTalkStatus.LISTENING,
            liveTalkTranscript = ""
        )
        startVoiceRecording(liveMode = true)
    }

    fun stopLiveTalk() {
        _uiState.value = _uiState.value.copy(
            liveTalkStatus = LiveTalkStatus.IDLE,
            liveTalkTranscript = ""
        )
        audioManager.stopRecording()
        audioManager.stopPlayback()
    }

    fun onLiveTalkOrbTapped() {
        val currentStatus = _uiState.value.liveTalkStatus
        if (currentStatus == LiveTalkStatus.SPEAKING) {
            // Interrupt assistant speech and immediately start listening again
            audioManager.stopPlayback()
            startVoiceRecording(liveMode = true)
        } else if (currentStatus == LiveTalkStatus.LISTENING) {
            // Force stop recording early (don't wait for silence timer)
            stopVoiceRecording(liveMode = true)
        }
    }

    private fun speakAndResumeLiveTalk(ttsText: String) {
        val clean = OrkiApiService.cleanTextForTTS(ttsText)
        if (clean.isEmpty()) {
            if (_uiState.value.liveTalkStatus != LiveTalkStatus.IDLE) {
                startVoiceRecording(liveMode = true)
            }
            return
        }

        val voice = _uiState.value.selectedVoice
        val profile = VoiceOptions.get(voice)
        val speed = prefs.ttsSpeed
        val pitch = prefs.ttsPitch
        val cacheKey = "$voice:$speed:$clean"

        _uiState.value = _uiState.value.copy(liveTalkStatus = LiveTalkStatus.SPEAKING)

        val cachedUrl = audioManager.ttsUrlCache[cacheKey]
        if (cachedUrl != null) {
            audioManager.playAudioUrl(
                url = cachedUrl,
                ttsText = ttsText,
                pitch = pitch,
                speed = speed
            ) {
                if (_uiState.value.liveTalkStatus != LiveTalkStatus.IDLE) {
                    startVoiceRecording(liveMode = true)
                }
            }
            return
        }

        val mode = prefs.ttsEngineMode
        val useOffline = when (mode) {
            "offline" -> true
            "auto" -> voicePackManager.isVoicePackInstalled()
            else -> false
        }

        if (useOffline) {
            if (!voicePackManager.isVoicePackInstalled()) {
                if (mode == "offline") {
                    if (_uiState.value.liveTalkStatus != LiveTalkStatus.IDLE) {
                        startVoiceRecording(liveMode = true)
                    }
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Offline voice pack is not installed. Download it in Settings to enable offline Bodo speech."
                    )
                    return
                }
                // In auto mode, fallback to online below
            } else {
                viewModelScope.launch {
                    try {
                        val engine = getOrInitNeuralEngine()
                        if (engine != null) {
                            val outFile = java.io.File(getApplication<Application>().cacheDir, "tts_neural_live_${voice}_${clean.hashCode()}.wav")
                            val res = engine.synthesizeToWav(clean, voice, speed, outFile)
                            res.onSuccess { wavPath ->
                                audioManager.ttsUrlCache[cacheKey] = wavPath
                                if (_uiState.value.liveTalkStatus != LiveTalkStatus.IDLE) {
                                    audioManager.playAudioUrl(
                                        url = wavPath,
                                        ttsText = ttsText,
                                        pitch = pitch,
                                        speed = speed
                                    ) {
                                        if (_uiState.value.liveTalkStatus != LiveTalkStatus.IDLE) {
                                            startVoiceRecording(liveMode = true)
                                        }
                                    }
                                }
                            }.onFailure {
                                if (_uiState.value.liveTalkStatus != LiveTalkStatus.IDLE) {
                                    startVoiceRecording(liveMode = true)
                                }
                            }
                            return@launch
                        }
                    } catch (_: Exception) {
                        if (_uiState.value.liveTalkStatus != LiveTalkStatus.IDLE) {
                            startVoiceRecording(liveMode = true)
                        }
                        return@launch
                    }
                }
                return
            }
        }

        // Online LiveTalk synthesis (AWS EC2 / swift-worker)
        viewModelScope.launch {
            val res = apiService.fetchTtsAudioUrl(clean, voice = voice)
            res.onSuccess { url ->
                audioManager.ttsUrlCache[cacheKey] = url
                if (_uiState.value.liveTalkStatus != LiveTalkStatus.IDLE) {
                    audioManager.playAudioUrl(
                        url = url,
                        ttsText = ttsText,
                        pitch = pitch,
                        speed = speed
                    ) {
                        if (_uiState.value.liveTalkStatus != LiveTalkStatus.IDLE) {
                            startVoiceRecording(liveMode = true)
                        }
                    }
                }
            }.onFailure {
                if (_uiState.value.liveTalkStatus != LiveTalkStatus.IDLE) {
                    startVoiceRecording(liveMode = true)
                }
            }
        }
    }

    fun cancelGeneration() {
        activeStreamJob?.cancel()
        activeStreamJob = null
        _uiState.value = _uiState.value.copy(
            isGenerating = false,
            isThinking = false,
            isGeneratingImage = false,
            imageGenProgress = 0,
            imageGenStage = "",
            imageGenPrompt = "",
            isImageGenFallback = false,
            imageGenEngine = "Orki AI (Cloudflare)",
            isGeneratingVideo = false,
            videoGenProgress = 0,
            videoGenStage = "",
            videoGenPrompt = "",
            isVideoGenFallback = false,
            videoGenEngine = "Json2video AI Studio (Primary)"
        )
    }

    private fun buildPersona(): String {
        val namePart = if (_uiState.value.userName.isNotBlank()) {
            "The user's name is ${_uiState.value.userName}; address them by name naturally."
        } else ""
        return listOf(namePart, _uiState.value.userPersona).filter { it.isNotBlank() }.joinToString(" ")
    }

    override fun onCleared() {
        super.onCleared()
        audioManager.destroy()
        if (billingManagerDelegate.isInitialized()) {
            billingManager.destroy()
        }
        neuralEngine?.close()
        neuralEngine = null
    }
}
