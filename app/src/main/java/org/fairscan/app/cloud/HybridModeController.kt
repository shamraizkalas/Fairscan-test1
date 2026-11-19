package org.fairscan.app.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class HybridModePreference(val label: String, val description: String) {
    AUTO_HYBRID(
        label = "Free Hybrid (ML Kit + Drive)",
        description = "Runs 100% free Google ML Kit & OpenCV on-device, plus automatic Google Drive backup & language pack downloads when online."
    ),
    ONLINE_PREFERRED(
        label = "Online Sync + Packs",
        description = "Keeps Google Drive sync, translation model downloads, and your optional personal API key active whenever connected."
    ),
    OFFLINE_ONLY(
        label = "Offline Only",
        description = "Zero network requests: runs OpenCV, Tesseract, PDFBox, OOXML, and already-downloaded ML Kit packs strictly on-device."
    ),
}

data class HybridConnectionState(
    val preference: HybridModePreference = HybridModePreference.AUTO_HYBRID,
    val hasNetwork: Boolean = false,
    val aiStructuredConversion: Boolean = true,
    val useMlKitSegmentation: Boolean = true,
    val personalGeminiApiKey: String = "",
) {
    val isOnlineActive: Boolean
        get() = preference != HybridModePreference.OFFLINE_ONLY && hasNetwork

    val isOnlineRequestedButDisconnected: Boolean
        get() = preference == HybridModePreference.ONLINE_PREFERRED && !hasNetwork

    val hasPersonalByokKey: Boolean
        get() = personalGeminiApiKey.isNotBlank()

    val isByokOnlineReady: Boolean
        get() = isOnlineActive && hasPersonalByokKey

    val shortBadgeLabel: String
        get() = when {
            preference == HybridModePreference.OFFLINE_ONLY -> "Offline"
            isByokOnlineReady -> "ML Kit + BYOK"
            hasNetwork -> "ML Kit • Free"
            preference == HybridModePreference.ONLINE_PREFERRED -> "No Network"
            else -> "Free • Offline"
        }
}

class HybridModeController(
    context: Context,
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
    enableSystemNetworkCallback: Boolean = true,
) {
    private val appContext = context.applicationContext
    private val KEY_MODE = stringPreferencesKey("hybrid_mode_preference")
    private val KEY_AI_DOC_STRUCTURE = booleanPreferencesKey("hybrid_ai_doc_structure")
    private val KEY_MLKIT_SEGMENTATION = booleanPreferencesKey("free_mlkit_segmentation")
    private val KEY_CLOUD_AUTO_SIGNIN_OPT_IN = booleanPreferencesKey("cloud_sync_opted_in")
    private val KEY_PERSONAL_GEMINI_API_KEY = stringPreferencesKey("user_personal_gemini_api_key")

    private val networkAvailable = MutableStateFlow(checkCurrentConnectivity())

    val state: StateFlow<HybridConnectionState> = combine(
        dataStore.data,
        networkAvailable,
    ) { prefs, hasNet ->
        val rawMode = prefs[KEY_MODE]
        val pref = enumValues<HybridModePreference>().firstOrNull { it.name == rawMode }
            ?: HybridModePreference.AUTO_HYBRID
        val aiDocStructure = prefs[KEY_AI_DOC_STRUCTURE] ?: true
        val useSegmentation = prefs[KEY_MLKIT_SEGMENTATION] ?: true
        val personalKey = prefs[KEY_PERSONAL_GEMINI_API_KEY].orEmpty().trim()
        HybridConnectionState(
            preference = pref,
            hasNetwork = hasNet,
            aiStructuredConversion = aiDocStructure,
            useMlKitSegmentation = useSegmentation,
            personalGeminiApiKey = personalKey,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = HybridConnectionState(
            preference = HybridModePreference.AUTO_HYBRID,
            hasNetwork = networkAvailable.value,
            aiStructuredConversion = true,
            useMlKitSegmentation = true,
            personalGeminiApiKey = "",
        )
    )

    private var registeredCallback: ConnectivityManager.NetworkCallback? = null

    init {
        if (enableSystemNetworkCallback) {
            registerNetworkObserver()
        }
    }

    fun setPreference(mode: HybridModePreference) {
        scope.launch {
            dataStore.edit { it[KEY_MODE] = mode.name }
        }
    }

    fun setAiStructuredConversion(enabled: Boolean) {
        scope.launch {
            dataStore.edit { it[KEY_AI_DOC_STRUCTURE] = enabled }
        }
    }

    fun setUseMlKitSegmentation(enabled: Boolean) {
        scope.launch {
            dataStore.edit { it[KEY_MLKIT_SEGMENTATION] = enabled }
        }
    }

    fun setPersonalGeminiApiKey(apiKey: String) {
        val sanitized = apiKey.trim()
        scope.launch {
            dataStore.edit { prefs ->
                if (sanitized.isEmpty()) {
                    prefs.remove(KEY_PERSONAL_GEMINI_API_KEY)
                } else {
                    prefs[KEY_PERSONAL_GEMINI_API_KEY] = sanitized
                }
            }
        }
    }

    fun setCloudSyncOptedIn(optedIn: Boolean) {
        scope.launch {
            dataStore.edit { it[KEY_CLOUD_AUTO_SIGNIN_OPT_IN] = optedIn }
        }
    }

    suspend fun isCloudSyncOptedIn(): Boolean {
        var result = false
        dataStore.edit { prefs ->
            result = prefs[KEY_CLOUD_AUTO_SIGNIN_OPT_IN] ?: false
        }
        return result
    }

    fun refreshConnectivity() {
        networkAvailable.value = checkCurrentConnectivity()
    }

    internal fun setNetworkAvailableForTest(available: Boolean) {
        networkAvailable.value = available
    }

    private fun checkCurrentConnectivity(): Boolean {
        return runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching false
            val network = cm.activeNetwork ?: return@runCatching false
            val caps = cm.getNetworkCapabilities(network) ?: return@runCatching false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }.getOrDefault(false)
    }

    private fun registerNetworkObserver() {
        runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    networkAvailable.value = true
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    networkAvailable.value = hasInternet
                }

                override fun onLost(network: Network) {
                    networkAvailable.value = checkCurrentConnectivity()
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            registeredCallback = callback
        }
    }
}
