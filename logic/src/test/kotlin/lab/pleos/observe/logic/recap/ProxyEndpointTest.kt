package lab.pleos.observe.logic.recap

import org.junit.Assert.*
import org.junit.Test

class ProxyEndpointTest {
    @Test fun defaultIsTheEmulatorRelayAndIsValidInRelease() {
        assertEquals("http://10.0.2.2:8787", DEFAULT_AI_PROXY_URL)
        assertEquals(DEFAULT_AI_PROXY_URL, normalizedProxyEndpoint(DEFAULT_AI_PROXY_URL))
    }
    @Test fun trimsAndNormalizesSchemeHostAndTrailingSlash() {
        assertEquals("http://10.0.2.2:8787", normalizedProxyEndpoint(" HTTP://10.0.2.2:8787/ "))
        assertEquals("https://relay.invalid:9443", normalizedProxyEndpoint("HTTPS://RELAY.INVALID:9443/"))
    }
    @Test fun onlyTheExactEmulatorHostMayUseCleartext() {
        for (host in listOf("localhost", "127.0.0.1", "10.0.2.2.invalid", "10.0.2.20", "192.168.0.1", "[::1]"))
            assertNull(normalizedProxyEndpoint("http://$host:8787"))
        assertEquals("https://relay.invalid", normalizedProxyEndpoint("https://relay.invalid"))
    }
    @Test fun credentialsQueriesFragmentsAndPathsAreNeverSavedAsServerAddresses() {
        for (value in listOf("https://user" + "@relay.invalid", "https://user:password" + "@relay.invalid",
            "https://relay.invalid?key=value", "https://relay.invalid#fragment", "https://relay.invalid/proxy",
            "https://relay.invalid/../", "https://relay.invalid/%2f", "https://relay.invalid/\\other"))
            assertNull(normalizedProxyEndpoint(value))
    }
    @Test fun rejectsInvalidSchemesPortsAndMalformedOrigins() {
        for (value in listOf("", "10.0.2.2:8787", "file:///tmp", "ftp://relay.invalid", "https://",
            "https://relay.invalid:0", "https://relay.invalid:65536", "https://relay.invalid:abc",
            "https://relay.invalid\n/", "https://relay.invalid/a b", "https://" + "a".repeat(251)))
            assertNull(value, normalizedProxyEndpoint(value))
    }
}
