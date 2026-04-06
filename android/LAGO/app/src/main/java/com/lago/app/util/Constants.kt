package com.lago.app.util

object Constants {

    // API Constants
    const val BASE_URL = "http://222.98.96.98:8081/"
    const val WS_STOCK_URL = "ws://222.98.96.98:8081/ws-stock/websocket"
    const val API_VERSION = "v1"
    const val TIMEOUT_SECONDS = 30L

    // Shared Preferences Keys
    const val PREF_NAME = "lago_preferences"
    const val KEY_ACCESS_TOKEN = "access_token"
    const val KEY_REFRESH_TOKEN = "refresh_token"
    const val KEY_USER_ID = "user_id"
    const val KEY_IS_LOGGED_IN = "is_logged_in"

    // Database Constants
    const val DATABASE_NAME = "lago_database"
    const val DATABASE_VERSION = 1

    // Navigation Constants
    const val DEEP_LINK_SCHEME = "lago"
    const val DEEP_LINK_HOST = "app"

    // UI Constants
    const val ANIMATION_DURATION = 300
    const val DEBOUNCE_TIME = 500L

    // Business Logic Constants
    const val MIN_PASSWORD_LENGTH = 8
    const val MAX_RETRY_ATTEMPTS = 3
    const val CACHE_EXPIRY_HOURS = 24
}
