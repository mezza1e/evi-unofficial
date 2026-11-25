package wtf.evi.mobile.data.network

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionCookieJar(context: Context) : CookieJar {
    private val prefs = context.getSharedPreferences("evi_session_prefs", Context.MODE_PRIVATE)
    private val lock = Any()
    private val cookieStore = mutableMapOf<String, Cookie>()

    private val _hasSession = MutableStateFlow(false)
    val hasSession: StateFlow<Boolean> = _hasSession.asStateFlow()

    init {
        loadFromPrefs()
    }

    private fun loadFromPrefs() {
        synchronized(lock) {
            val savedToken = prefs.getString(KEY_AURAL_SESSION, null)?.trim()
            if (!savedToken.isNullOrEmpty()) {
                val cookie = Cookie.Builder()
                    .domain("evi.wtf")
                    .path("/")
                    .name("aural_session")
                    .value(savedToken)
                    .httpOnly()
                    .secure()
                    .build()
                cookieStore["aural_session"] = cookie
            }
            val rawExtra = prefs.getStringSet(KEY_EXTRA_COOKIES, emptySet()).orEmpty()
            for (line in rawExtra) {
                val idx = line.indexOf('=')
                if (idx > 0) {
                    val name = line.substring(0, idx)
                    val value = line.substring(idx + 1)
                    if (name.isNotBlank() && value.isNotBlank()) {
                        cookieStore[name] = Cookie.Builder()
                            .domain("evi.wtf")
                            .path("/")
                            .name(name)
                            .value(value)
                            .secure()
                            .build()
                    }
                }
            }
            _hasSession.value = cookieStore.containsKey("aural_session")
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!url.host.endsWith("evi.wtf")) return
        synchronized(lock) {
            var changed = false
            val now = System.currentTimeMillis()
            for (cookie in cookies) {
                if (cookie.expiresAt < now || cookie.value.isEmpty()) {
                    if (cookieStore.remove(cookie.name) != null) changed = true
                } else {
                    cookieStore[cookie.name] = cookie
                    changed = true
                }
            }
            if (changed) persistLocked()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!url.host.endsWith("evi.wtf")) return emptyList()
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val expired = cookieStore.entries.filter { it.value.persistent && it.value.expiresAt < now }.map { it.key }
            if (expired.isNotEmpty()) {
                expired.forEach { cookieStore.remove(it) }
                persistLocked()
            }
            return cookieStore.values.toList()
        }
    }

    fun setSessionToken(rawInput: String) {
        val cleaned = rawInput.trim()
            .removePrefix("Cookie:")
            .trim()
            .let { str ->
                if (str.contains("aural_session=")) {
                    str.substringAfter("aural_session=").substringBefore(";").trim()
                } else {
                    str
                }
            }
        if (cleaned.isBlank()) return
        synchronized(lock) {
            cookieStore["aural_session"] = Cookie.Builder()
                .domain("evi.wtf")
                .path("/")
                .name("aural_session")
                .value(cleaned)
                .httpOnly()
                .secure()
                .build()
            persistLocked()
        }
    }

    fun importCookieHeader(cookieHeader: String) {
        if (cookieHeader.isBlank()) return
        synchronized(lock) {
            val parts = cookieHeader.split(';')
            var changed = false
            for (part in parts) {
                val eq = part.indexOf('=')
                if (eq > 0) {
                    val name = part.substring(0, eq).trim()
                    val value = part.substring(eq + 1).trim()
                    if (name.isNotBlank() && value.isNotBlank()) {
                        cookieStore[name] = Cookie.Builder()
                            .domain("evi.wtf")
                            .path("/")
                            .name(name)
                            .value(value)
                            .secure()
                            .build()
                        changed = true
                    }
                }
            }
            if (changed) persistLocked()
        }
    }

    fun getSessionToken(): String? {
        synchronized(lock) {
            return cookieStore["aural_session"]?.value
        }
    }

    fun clear() {
        synchronized(lock) {
            cookieStore.clear()
            prefs.edit().clear().apply()
            _hasSession.value = false
        }
    }

    private fun persistLocked() {
        val token = cookieStore["aural_session"]?.value
        val extras = cookieStore.entries
            .filter { it.key != "aural_session" }
            .map { "${it.key}=${it.value.value}" }
            .toSet()
        prefs.edit()
            .putString(KEY_AURAL_SESSION, token)
            .putStringSet(KEY_EXTRA_COOKIES, extras)
            .apply()
        _hasSession.value = !token.isNullOrBlank()
    }

    companion object {
        private const val KEY_AURAL_SESSION = "aural_session"
        private const val KEY_EXTRA_COOKIES = "extra_cookies"
    }
}
