package eu.kanade.tachiyomi.extension.all.kavita

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Unit tests for [OpdsUrlParser].
 *
 * Pure JVM (no Android, no network). Covers the GetOpdsUrl edge cases:
 * double slashes, trailing slashes, case, base paths, scheme-less HostName
 * values, redaction of API keys in log output, and redirect resolution.
 */

@RunWith(Parameterized::class)
class OpdsUrlParserSuccessTest(
    private val input: String,
    private val expectedServerBase: String,
    private val expectedApiKey: String,
) {
    @Test
    fun `parses and normalizes`() {
        val p = OpdsUrlParser.parse(input)
        assertEquals(expectedServerBase, p.serverBase)
        assertEquals(expectedApiKey, p.apiKey)
        assertEquals("$expectedServerBase/api", p.apiUrl)
        assertEquals("$expectedServerBase/api/opds/$expectedApiKey", p.normalizedAddress)

        // No duplicate slashes after the scheme
        assertFalse(
            "normalizedAddress must not contain // after the scheme: ${p.normalizedAddress}",
            p.normalizedAddress.substringAfter("://").contains("//"),
        )
        // Idempotent: re-parsing the normalized form yields the same result
        assertEquals(p.normalizedAddress, OpdsUrlParser.parse(p.normalizedAddress).normalizedAddress)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0}")
        fun data(): Collection<Array<Any>> = listOf(
            // basic https
            arrayOf(
                "https://kavita.example.com/api/opds/my-api-key",
                "https://kavita.example.com",
                "my-api-key",
            ),
            // http with port
            arrayOf(
                "http://192.168.1.5:5000/api/opds/abc",
                "http://192.168.1.5:5000",
                "abc",
            ),
            // base path under reverse proxy
            arrayOf(
                "https://example.com/kavita/api/opds/key1",
                "https://example.com/kavita",
                "key1",
            ),
            // double slash from HostName trailing slash
            arrayOf(
                "https://kavita.example.com//api/opds/key",
                "https://kavita.example.com",
                "key",
            ),
            // double slash before base path (origin ends with /, baseUrl = /kavita/)
            arrayOf(
                "https://host//kavita/api/opds/key",
                "https://host/kavita",
                "key",
            ),
            // trailing slash on OPDS url
            arrayOf(
                "https://host/api/opds/key/",
                "https://host",
                "key",
            ),
            // case-insensitive api/opds segment (key case preserved)
            arrayOf(
                "https://host/API/OPDS/KeyCase",
                "https://host",
                "KeyCase",
            ),
            // whitespace trimmed
            arrayOf(
                "  https://host/api/opds/key  ",
                "https://host",
                "key",
            ),
            // query suffix after key is ignored
            arrayOf(
                "https://host/api/opds/key?x=1",
                "https://host",
                "key",
            ),
            // multi-segment base path
            arrayOf(
                "https://host/a/b/api/opds/key",
                "https://host/a/b",
                "key",
            ),
            // base path that itself contains "api"
            arrayOf(
                "https://host/api/kavita/api/opds/key",
                "https://host/api/kavita",
                "key",
            ),
        )
    }
}

class OpdsUrlParserRedactTest {

    @Test
    fun `redact hides api key on happy path`() {
        val redacted = OpdsUrlParser.redact("https://host/api/opds/secret-key-value")
        assertEquals("https://host/api/opds/***", redacted)
        assertFalse(redacted.contains("secret-key-value"))
    }

    @Test
    fun `redact handles trailing slash`() {
        val redacted = OpdsUrlParser.redact("https://host/api/opds/secret-key/")
        assertEquals("https://host/api/opds/***/", redacted)
        assertFalse(redacted.contains("secret-key"))
    }

    @Test
    fun `redact handles query suffix`() {
        val redacted = OpdsUrlParser.redact("https://host/api/opds/secret-key?x=1")
        assertEquals("https://host/api/opds/***?x=1", redacted)
        assertFalse(redacted.contains("secret-key"))
    }

    @Test
    fun `redact handles upper-case API OPDS`() {
        val redacted = OpdsUrlParser.redact("https://host/API/OPDS/secret-key")
        assertEquals("https://host/API/OPDS/***", redacted)
        assertFalse(redacted.contains("secret-key"))
    }

    @Test
    fun `redact handles double slash`() {
        val redacted = OpdsUrlParser.redact("https://host//api/opds/secret-key")
        assertEquals("https://host//api/opds/***", redacted)
        assertFalse(redacted.contains("secret-key"))
    }

    @Test
    fun `redact hides apiKey query parameter`() {
        val redacted = OpdsUrlParser.redact(
            "https://host/api/Plugin/authenticate?apiKey=secret-key-value&pluginName=Tachiyomi-Kavita",
        )
        assertEquals(
            "https://host/api/Plugin/authenticate?apiKey=***&pluginName=Tachiyomi-Kavita",
            redacted,
        )
        assertFalse(redacted.contains("secret-key-value"))
    }

    @Test
    fun `redact hides both path and query key forms`() {
        val redacted = OpdsUrlParser.redact(
            "https://host/api/opds/path-secret?apiKey=query-secret",
        )
        assertFalse(redacted.contains("path-secret"))
        assertFalse(redacted.contains("query-secret"))
        assertEquals("https://host/api/opds/***?apiKey=***", redacted)
    }

    @Test
    fun `redact on garbage does not throw and does not invent a key`() {
        assertEquals("", OpdsUrlParser.redact(""))
        assertEquals("not-a-url", OpdsUrlParser.redact("not-a-url"))
        assertEquals("https://host/opds/nope", OpdsUrlParser.redact("https://host/opds/nope"))
    }

    @Test
    fun `redact strips userinfo`() {
        assertEquals(
            "https://host/api/opds/***",
            OpdsUrlParser.redact("https://user:pass@host/api/opds/secret-key"),
        )
    }
}

@RunWith(Parameterized::class)
class OpdsUrlParserFailureTest(
    private val input: String,
    private val expected: Class<out OpdsUrlParser.ParseException>,
) {
    @Test
    fun `rejects invalid input without leaking key material`() {
        val e = assertThrows(expected) {
            OpdsUrlParser.parse(input)
        }
        // Most messages are short codes ("bad_form", "missing_scheme"); InvalidUrl may
        // embed a redacted URL. Guard against embedding the raw input / API key.
        val message = e.message.orEmpty()
        assertFalse(
            "ParseException message must not contain the raw input: $message",
            input.isNotEmpty() && message.contains(input),
        )
        // If the input looked like it had a key segment, the key itself must not appear.
        val possibleKey = input.substringAfterLast("/api/opds/", missingDelimiterValue = "")
            .substringBefore('?')
            .substringBefore('#')
            .trim('/')
        if (possibleKey.isNotEmpty() && possibleKey != input) {
            assertFalse(
                "ParseException message must not contain API key material: $message",
                message.contains(possibleKey),
            )
        }
        val queryKey = Regex("""(?i)[?&]apiKey=([^&\s#]+)""").find(input)?.groupValues?.getOrNull(1)
        if (!queryKey.isNullOrEmpty()) {
            assertFalse(
                "ParseException message must not contain query apiKey material: $message",
                message.contains(queryKey),
            )
        }
        if (input.contains("@")) {
            assertFalse(
                "ParseException message must not contain userinfo: $message",
                message.contains("user:pass"),
            )
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0}")
        fun data(): Collection<Array<Any>> = listOf(
            arrayOf("", OpdsUrlParser.ParseException.BadForm::class.java),
            arrayOf("not-a-url", OpdsUrlParser.ParseException.MissingScheme::class.java),
            arrayOf("192.168.1.5:5000/api/opds/key", OpdsUrlParser.ParseException.MissingScheme::class.java),
            arrayOf("https://host/opds/key", OpdsUrlParser.ParseException.BadForm::class.java),
            arrayOf("https://host/api/opds/", OpdsUrlParser.ParseException.BadForm::class.java),
            arrayOf("https://host/api/opds", OpdsUrlParser.ParseException.BadForm::class.java),
            arrayOf("https://host/api/opds/key/extra", OpdsUrlParser.ParseException.BadForm::class.java),
            // InvalidUrl: host that okhttp rejects (message must not leak key or userinfo)
            arrayOf("https://[bad/api/opds/key", OpdsUrlParser.ParseException.InvalidUrl::class.java),
            arrayOf("https://user:pass@[bad/api/opds/key", OpdsUrlParser.ParseException.InvalidUrl::class.java),
            // Query form is not accepted — BadForm, not a successful parse
            arrayOf("https://host/opds?apiKey=SECRET", OpdsUrlParser.ParseException.BadForm::class.java),
            arrayOf("https://host/api/opds?apiKey=SECRET", OpdsUrlParser.ParseException.BadForm::class.java),
        )
    }
}

@RunWith(Parameterized::class)
class ResolveRedirectedApiUrlTest(
    private val requestUrl: String,
    private val location: String,
    private val expected: String?,
) {
    @Test
    fun `resolves or rejects redirect`() {
        assertEquals(expected, OpdsUrlParser.resolveRedirectedApiUrl(requestUrl, location))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {1}")
        fun data(): Collection<Array<Any?>> = listOf(
            // trailing-slash / scheme canonicalization on same host
            arrayOf(
                "https://host/api/Plugin/authenticate?apiKey=k",
                "https://host/api/Plugin/authenticate",
                "https://host/api",
            ),
            // base path preserved
            arrayOf(
                "https://host/kavita/api/Plugin/authenticate?apiKey=k",
                "https://host/kavita/api/Plugin/authenticate",
                "https://host/kavita/api",
            ),
            // relative Location
            arrayOf(
                "https://host/api/Plugin/authenticate?apiKey=k",
                "/api/Plugin/authenticate",
                "https://host/api",
            ),
            // port preserved when non-default
            arrayOf(
                "https://host:8443/api/Plugin/authenticate?apiKey=k",
                "https://host:8443/api/Plugin/authenticate",
                "https://host:8443/api",
            ),
            // IPv6 literal must keep brackets in the rebuilt API base
            arrayOf(
                "https://[::1]:8443/api/Plugin/authenticate?apiKey=k",
                "https://[::1]:8443/api/Plugin/authenticate",
                "https://[::1]:8443/api",
            ),
            // userinfo on Location must not be persisted
            arrayOf(
                "https://host/api/Plugin/authenticate?apiKey=k",
                "https://user:pass@host/api/Plugin/authenticate",
                "https://host/api",
            ),
            arrayOf(
                "https://[2001:db8::1]/kavita/api/Plugin/authenticate?apiKey=k",
                "https://[2001:db8::1]/kavita/api/Plugin/authenticate",
                "https://[2001:db8::1]/kavita/api",
            ),
            // http allowed when request was already http
            arrayOf(
                "http://host/api/Plugin/authenticate?apiKey=k",
                "http://host/api/Plugin/authenticate",
                "http://host/api",
            ),
            // scheme upgrade http → https is OK
            arrayOf(
                "http://host/api/Plugin/authenticate?apiKey=k",
                "https://host/api/Plugin/authenticate",
                "https://host/api",
            ),
            // scheme DOWNgrade https → http rejected
            arrayOf(
                "https://host/api/Plugin/authenticate?apiKey=k",
                "http://host/api/Plugin/authenticate",
                null,
            ),
            // different host rejected
            arrayOf(
                "https://host/api/Plugin/authenticate?apiKey=k",
                "https://evil.example/api/Plugin/authenticate",
                null,
            ),
            // wrong path rejected
            arrayOf(
                "https://host/api/Plugin/authenticate?apiKey=k",
                "https://host/api/other",
                null,
            ),
            // empty / garbage
            arrayOf(
                "https://host/api/Plugin/authenticate?apiKey=k",
                "",
                null,
            ),
        )
    }
}

class BuildAuthenticateUrlTest {
    @Test
    fun `builds query with HttpUrl builder`() {
        val url = OpdsUrlParser.buildAuthenticateUrl("https://host/api", "secret key")
        assertEquals(
            "https://host/api/Plugin/authenticate?apiKey=secret%20key&pluginName=Tachiyomi-Kavita",
            url,
        )
    }

    @Test
    fun `trims trailing slash on api base`() {
        val url = OpdsUrlParser.buildAuthenticateUrl("https://host/api/", "k")
        assertEquals(
            "https://host/api/Plugin/authenticate?apiKey=k&pluginName=Tachiyomi-Kavita",
            url,
        )
    }
}
