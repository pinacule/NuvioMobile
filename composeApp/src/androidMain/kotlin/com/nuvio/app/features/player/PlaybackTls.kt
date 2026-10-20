package com.nuvio.app.features.player

import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.internal.tls.OkHostnameVerifier

/**
 * TLS for playback streams. Public hosts get normal certificate and hostname verification — the
 * system trust store as the app's network_security_config extends it — because debrid and addon
 * streams carry Authorization headers and tokens in their URLs. Only hosts *named* as local
 * (see [isLocalNetworkHost]) skip verification, for self-hosted servers and addons with
 * self-signed certificates. The decision uses the host as written, never what it resolves to:
 * a spoofed DNS answer pointing a public name at a LAN box must still fail verification.
 */
internal object PlaybackTls {
    val trustManager: X509TrustManager by lazy { LocalHostRelaxedTrustManager(systemTrustManager()) }

    val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    }

    val hostnameVerifier: HostnameVerifier = HostnameVerifier { host, session ->
        isLocalNetworkHost(host) || OkHostnameVerifier.verify(host, session)
    }

    private fun systemTrustManager(): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
}

/**
 * Delegates every check to [delegate] except server checks for a peer named as a local host.
 * Extended (socket/engine-aware) so the peer's host name is known during the handshake, and so
 * the platform's per-domain network security config still applies through the delegate.
 */
internal class LocalHostRelaxedTrustManager(private val delegate: X509TrustManager) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) {
        val peerHost = (socket as? SSLSocket)?.handshakeSession?.peerHost
        if (peerHost != null && isLocalNetworkHost(peerHost)) return
        val extended = delegate as? X509ExtendedTrustManager
        if (extended != null) extended.checkServerTrusted(chain, authType, socket)
        else delegate.checkServerTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) {
        val peerHost = engine?.peerHost
        if (peerHost != null && isLocalNetworkHost(peerHost)) return
        val extended = delegate as? X509ExtendedTrustManager
        if (extended != null) extended.checkServerTrusted(chain, authType, engine)
        else delegate.checkServerTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) =
        delegate.checkServerTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) =
        delegate.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) =
        delegate.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        delegate.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}

private val LOCAL_NAME_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal", ".localdomain")
private val IPV4_LITERAL = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")

/** Dotless labels that inet_aton-style parsers read as a whole address: `134744072`, `0x8080808`. */
private val NUMERIC_LABEL = Regex("""\d+|0x\p{XDigit}*""")

/**
 * True when [host] *names* a machine on the user's own network: loopback, a private, link-local
 * or CGNAT/overlay (e.g. Tailscale) IP literal, `localhost`, a single-label name, or a
 * conventional local suffix (.local, .lan, .home.arpa, .internal, .localdomain). Never resolves.
 *
 * Anything a resolver could read as a different address than this function does is not local:
 * an octet with a leading zero (`010` is octal 8 to inet_aton, so `010.0.0.1` reaches 8.0.0.1),
 * shortened dotted forms (`127.1`) and dotless numeric or hex labels all get full verification.
 */
internal fun isLocalNetworkHost(host: String): Boolean {
    val name = host.trim().trimEnd('.').removePrefix("[").removeSuffix("]").lowercase()
    if (name.isEmpty()) return false
    IPV4_LITERAL.matchEntire(name)?.let { match ->
        val groups = match.groupValues.drop(1)
        if (groups.any { it.length > 1 && it.startsWith('0') }) return false
        val octets = groups.map { it.toInt() }
        if (octets.any { it > 255 }) return false
        val (a, b) = octets
        return a == 127 || a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            (a == 100 && b in 64..127)
    }
    if (':' in name) {
        // IPv6 literal: loopback, link-local fe80::/10, unique-local fc00::/7.
        val firstGroup = name.substringBefore(':').toIntOrNull(16)
        return name == "::1" ||
            (firstGroup != null && (firstGroup and 0xffc0 == 0xfe80 || firstGroup and 0xfe00 == 0xfc00))
    }
    if (name == "localhost" || name.endsWith(".localhost")) return true
    if ('.' !in name) return !NUMERIC_LABEL.matches(name)
    return LOCAL_NAME_SUFFIXES.any { name.endsWith(it) }
}
