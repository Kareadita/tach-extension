package eu.kanade.tachiyomi.extension.all.kavita

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Pure OPDS URL parsing for Kavita.
 *
 * Kavita's GetOpdsUrl builds: origin + "/" + baseUrl + "api/opds/" + apiKey
 * where origin may be serverSettings.HostName verbatim. That can produce:
 * - a scheme-less host (rejected here — caller must supply http:// or https://)
 * - duplicated slashes when HostName/BaseUrl already end with "/"
 *
 * Accepted form:
 *   http(s)://host[:port][/base]/api/opds/<api-key>
 *
 * Result normalizes to the path form in [Parsed.normalizedAddress].
 */
object OpdsUrlParser {

    private val PATH_DUPLICATE_SLASHES = Regex("/{2,}")
    private val API_OPDS_PATH = Regex("""(?i)/api/opds/""")
    private val REDACT_PATH_KEY = Regex("""(?i)(/api/opds/)[^/\s?#]+""")
    private val REDACT_QUERY_KEY = Regex("""(?i)([?&]apiKey=)[^&\s#]+""")

    /** Strip userinfo (user:pass@) for logcat / toast safety. */
    private val REDACT_USERINFO = Regex("""(?i)(://)[^/@\s]+@""")

    data class Parsed(
        /** Origin + optional base path, no trailing slash (e.g. https://host or https://host/kavita). */
        val serverBase: String,
        val apiKey: String,
    ) {
        val apiUrl: String get() = "$serverBase/api"
        val normalizedAddress: String get() = "$serverBase/api/opds/$apiKey"
    }

    sealed class ParseException(message: String) : Exception(message) {
        class BadForm : ParseException("bad_form")
        class MissingScheme : ParseException("missing_scheme")
        class InvalidUrl(val value: String) : ParseException("invalid_url:$value")
    }

    fun parse(raw: String): Parsed {
        var s = raw.trim()
        if (s.isEmpty()) throw ParseException.BadForm()

        if (!s.contains("://")) {
            // Do not guess http vs https — LAN IP HostNames need an explicit scheme.
            throw ParseException.MissingScheme()
        }

        // Collapse path duplicate slashes; keep the scheme's "://"
        val schemeIdx = s.indexOf("://")
        val head = s.substring(0, schemeIdx + 3)
        val tail = s.substring(schemeIdx + 3).replace(PATH_DUPLICATE_SLASHES, "/")
        s = (head + tail).trimEnd('/')

        // Canonical path form: .../api/opds/<key>
        val pathMatch = API_OPDS_PATH.find(s)
            ?: throw ParseException.BadForm()

        val serverBase = s.substring(0, pathMatch.range.first).trimEnd('/')
        val key = s.substring(pathMatch.range.last + 1)
            .substringBefore('?')
            .substringBefore('#')
            .trim()
        return finish(serverBase, key)
    }

    private fun finish(serverBase: String, key: String): Parsed {
        if (serverBase.isEmpty() || key.isEmpty() || key.contains('/')) {
            throw ParseException.BadForm()
        }
        // Normalize: drop userinfo so credentials never land in prefs or exception messages.
        val normalizedBase = serverBase.toHttpUrlOrNull()?.newBuilder()
            ?.username("")
            ?.password("")
            ?.build()
            ?.toString()
            ?.trimEnd('/')
            ?: throw ParseException.InvalidUrl(redact(serverBase))
        return Parsed(serverBase = normalizedBase, apiKey = key)
    }

    /**
     * Redact sensitive material for logcat and toasts:
     * path-form API keys, apiKey= query params, and URL userinfo.
     */
    fun redact(url: String): String {
        if (url.isEmpty()) return url
        return url
            .replace(REDACT_USERINFO, "$1")
            .replace(REDACT_PATH_KEY, "$1***")
            .replace(REDACT_QUERY_KEY, "$1***")
    }

    /**
     * If the redirect is a same-host scheme/port/trailing-slash canonicalization of a
     * Plugin/authenticate request, return the corrected API base (…/api). Otherwise null.
     *
     * Rejects scheme downgrades (https → http) so the API key is never sent in cleartext
     * after a redirect. Strips any userinfo from Location so credentials are not persisted.
     */
    fun resolveRedirectedApiUrl(requestUrl: String, location: String): String? {
        val request = requestUrl.toHttpUrlOrNull() ?: return null
        val loc = when {
            location.startsWith("http://", ignoreCase = true) ||
                location.startsWith("https://", ignoreCase = true) -> location.toHttpUrlOrNull()
            location.startsWith("/") -> request.resolve(location)
            else -> request.resolve("/$location")
        } ?: return null

        // Only auto-follow same host; path must still be Plugin/authenticate (POST-only).
        if (!loc.host.equals(request.host, ignoreCase = true)) return null
        if (!loc.encodedPath.contains("/Plugin/authenticate", ignoreCase = true)) return null

        // Never follow https → http (would send apiKey in cleartext).
        if (request.isHttps && !loc.isHttps) return null

        // API base is everything before /Plugin/authenticate.
        // Rebuild via OkHttp so IPv6 literals keep their brackets (loc.host is unbracketed).
        // Clear userinfo so a Location with user:pass@ is not persisted into prefs.
        val path = loc.encodedPath
        val idx = path.indexOf("/Plugin/authenticate", ignoreCase = true)
        if (idx <= 0) return null
        val apiPath = path.substring(0, idx).trimEnd('/')
        return loc.newBuilder()
            .username("")
            .password("")
            .encodedPath(if (apiPath.isEmpty()) "/" else apiPath)
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')
    }

    /** Build Plugin/authenticate URL without string-encoding pitfalls. */
    fun buildAuthenticateUrl(apiBase: String, apiKey: String): String {
        val base = apiBase.trimEnd('/').toHttpUrlOrNull()
            ?: return "${apiBase.trimEnd('/')}/Plugin/authenticate?apiKey=$apiKey&pluginName=Tachiyomi-Kavita"
        return base.newBuilder()
            .addPathSegment("Plugin")
            .addPathSegment("authenticate")
            .addQueryParameter("apiKey", apiKey)
            .addQueryParameter("pluginName", "Tachiyomi-Kavita")
            .build()
            .toString()
    }
}
