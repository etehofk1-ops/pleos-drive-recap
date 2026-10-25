package lab.pleos.observe.logic.recap

import java.net.URI
import java.util.Locale

const val DEFAULT_AI_PROXY_URL = "http://10.0.2.2:8787"

/** A public relay origin, never a key-bearing URL. Mirrors the Android cleartext domain rule. */
fun normalizedProxyEndpoint(input: String): String? = runCatching {
    val value = input.trim()
    require(value.length in 1..250 && value.none { it.isWhitespace() || it.isISOControl() } && '\\' !in value)
    val uri = URI(value)
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val host = uri.host?.lowercase(Locale.ROOT)
    require(!host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
    require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
    require(uri.port == -1 || uri.port in 1..65535)
    require(scheme == "https" || (scheme == "http" && host == "10.0.2.2"))
    URI(scheme, null, host, uri.port, null, null, null).toASCIIString()
}.getOrNull()
