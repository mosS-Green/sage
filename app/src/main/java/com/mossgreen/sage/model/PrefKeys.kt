package com.mossgreen.sage.model

/**
 * Single source of truth for the SharedPreferences keys used by the
 * provider/model configuration flow. Centralizing these prevents silent
 * breakage from mistyped string literals scattered across UI, service, and
 * client code.
 *
 * Values are unchanged from the literals previously used inline, so existing
 * stored preferences continue to resolve identically.
 */
object PrefKeys {
    /** Active provider ("gemini" | "groq" | "custom") — see [ProviderType]. */
    const val PROVIDER_TYPE = "provider_type"

    /** Selected Gemini model id. */
    const val GEMINI_MODEL = "model"

    /** Selected Groq model id. */
    const val GROQ_MODEL = "groq_model"

    /** Custom (OpenAI-compatible) model id. */
    const val CUSTOM_MODEL = "custom_model"

    /** Custom (OpenAI-compatible) endpoint base URL. */
    const val CUSTOM_ENDPOINT = "custom_endpoint"

    /** Sampling temperature (Float). */
    const val TEMPERATURE = "temperature"

    /** Epoch millis when structured output was last disabled (0 = never). */
    const val STRUCTURED_OUTPUT_DISABLED_AT = "structured_output_disabled_at"

    /** Master toggle for WhatsApp voice note auto-transcription (Boolean). */
    const val AUTO_TRANSCRIBE_ENABLED = "auto_transcribe_enabled"

    /** JSON string array of WhatsApp chat names to monitor (empty = monitor all). */
    const val MONITORED_CHATS = "monitored_chats"

    /** Last.fm account username. */
    const val LASTFM_USERNAME = "lastfm_username"

    /** Last.fm API Key. */
    const val LASTFM_API_KEY = "lastfm_api_key"

    /** Last.fm display name (shown-name in ?rn output). */
    const val LASTFM_SHOWN_NAME = "lastfm_shown_name"

    /** Last.fm verb in ?rn output (e.g. "listening", "vibing"). */
    const val LASTFM_VERB = "lastfm_verb"
}
