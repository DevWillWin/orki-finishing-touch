package com.example.data.audio

/**
 * Pure routing rules for speech requests, shared by the chat "speak" button and Live Talk so the rule
 * "explicit offline mode never silently uses the online service" lives in one tested place.
 *
 * Modes (preference `ttsEngineMode`): "offline", "auto" (default) and "online". Any other value behaves
 * like "online", as before.
 */
object TtsRouting {

    enum class Plan {
        /** Synthesize on-device. */
        OFFLINE,

        /** Use the online voice. */
        ONLINE,

        /** Offline was requested explicitly but the voice pack is missing: report it, never go online. */
        OFFLINE_PACK_MISSING
    }

    enum class OfflineFailureAction {
        /** Explicit offline mode: show the error and stop. */
        REPORT_ONLY,

        /** Auto mode: show the error and visibly continue with the online voice. */
        REPORT_AND_USE_ONLINE
    }

    fun plan(mode: String, packInstalled: Boolean): Plan = when (mode) {
        "offline" -> if (packInstalled) Plan.OFFLINE else Plan.OFFLINE_PACK_MISSING
        "auto" -> if (packInstalled) Plan.OFFLINE else Plan.ONLINE
        else -> Plan.ONLINE
    }

    fun onOfflineFailure(mode: String): OfflineFailureAction =
        if (mode == "offline") OfflineFailureAction.REPORT_ONLY else OfflineFailureAction.REPORT_AND_USE_ONLINE

    /** Cached results of the online service (memory or disk) must not be replayed in explicit offline mode. */
    fun mayUseOnlineCache(mode: String): Boolean = mode != "offline"
}
