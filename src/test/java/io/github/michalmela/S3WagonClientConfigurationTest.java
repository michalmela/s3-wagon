package io.github.michalmela;

import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.proxy.ProxyInfo;
import org.apache.maven.wagon.repository.Repository;
import org.apache.maven.wagon.ConnectionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These build a real S3 client rather than the in-memory one. Building a client makes no network
 * calls, so this stays hermetic while still proving the wagon hands the SDK something usable.
 */
class S3WagonClientConfigurationTest {

    /**
     * The endpoint was built as host + ":" + port with no scheme, which URI reads as a scheme of
     * "proxy.example.com" with a null host - so the SDK was handed a proxy it could not use and
     * every request quietly bypassed it.
     */
    @Test
    void buildsAUsableProxyEndpoint() throws Exception {
        ProxyInfo proxy = new ProxyInfo();
        proxy.setHost("proxy.example.com");
        proxy.setPort(8080);

        URI endpoint = S3Wagon.proxyEndpoint(proxy);

        assertEquals("http", endpoint.getScheme());
        assertEquals("proxy.example.com", endpoint.getHost());
        assertEquals(8080, endpoint.getPort());
    }

    @Test
    void honoursAnHttpsProxy() throws Exception {
        ProxyInfo proxy = new ProxyInfo();
        proxy.setHost("proxy.example.com");
        proxy.setPort(8443);
        proxy.setType("https");

        assertEquals("https", S3Wagon.proxyEndpoint(proxy).getScheme());
    }

    /** Apache's proxy support is HTTP-only, so a SOCKS type cannot be passed through as a scheme. */
    @Test
    void fallsBackToHttpForProxyTypesApacheCannotUse() throws Exception {
        ProxyInfo proxy = new ProxyInfo();
        proxy.setHost("proxy.example.com");
        proxy.setPort(1080);
        proxy.setType(ProxyInfo.PROXY_SOCKS5);

        assertEquals("http", S3Wagon.proxyEndpoint(proxy).getScheme());
    }

    /**
     * Found by throwing adversarial hosts at proxyEndpoint: a space, a pipe or a stray percent
     * made URI.create throw IllegalArgumentException, which escaped connect unchecked and blamed
     * nothing in particular. A malformed proxy host should say so.
     */
    @ParameterizedTest
    @ValueSource(strings = {"bad host", "prox|y", "%%", "host\nwithnewline"})
    void rejectsAProxyHostThatCannotBeAUrl(String host) {
        ProxyInfo proxy = new ProxyInfo();
        proxy.setHost(host);
        proxy.setPort(8080);

        ConnectionException thrown = assertThrows(ConnectionException.class, () -> S3Wagon.proxyEndpoint(proxy));
        assertTrue(thrown.getMessage().contains("not a usable host name"), thrown.getMessage());
    }

    /** A host read from an environment variable often arrives with a newline attached. */
    @Test
    void toleratesWhitespaceAroundTheProxyHost() throws Exception {
        ProxyInfo proxy = new ProxyInfo();
        proxy.setHost("  proxy.example.com\n");
        proxy.setPort(8080);

        assertEquals("proxy.example.com", S3Wagon.proxyEndpoint(proxy).getHost());
    }

    @Test
    void splitsNonProxyHosts() {
        ProxyInfo proxy = new ProxyInfo();
        proxy.setNonProxyHosts("localhost|*.internal, 10.0.0.1");

        assertEquals(new HashSet<>(Arrays.asList("localhost", "*.internal", "10.0.0.1")),
                S3Wagon.nonProxyHosts(proxy));
    }

    /**
     * Found by Jazzer. isNotBlank goes by Character.isWhitespace, which does not treat control
     * characters as blank, but trim strips everything up to U+0020 - so this entry survived the
     * blank check and then trimmed away to an empty string in the result.
     */
    @Test
    void dropsEntriesThatTrimAwayToNothing() {
        ProxyInfo proxy = new ProxyInfo();
        proxy.setNonProxyHosts("localhost|\n\u001a|,10.0.0.1");

        assertEquals(new HashSet<>(Arrays.asList("localhost", "10.0.0.1")), S3Wagon.nonProxyHosts(proxy));
    }

    @Test
    void toleratesMissingNonProxyHosts() {
        assertEquals(Collections.emptySet(), S3Wagon.nonProxyHosts(new ProxyInfo()));
    }

    @Test
    void buildsAClientThroughAProxy() {
        ProxyInfo proxy = new ProxyInfo();
        proxy.setHost("proxy.example.com");
        proxy.setPort(8080);
        proxy.setUserName("proxy-user");
        proxy.setPassword("proxy-password");
        proxy.setNonProxyHosts("localhost");

        S3Wagon wagon = new S3Wagon();
        wagon.setRegion("eu-central-1");

        assertDoesNotThrow(() -> {
            wagon.connect(new Repository("test", "s3p://bucket/releases/"), proxy);
            wagon.disconnect();
        });
    }

    /** A ProxyInfo with nothing in it must not turn into a proxy at "http://null:0". */
    @Test
    void ignoresAnEmptyProxy() {
        S3Wagon wagon = new S3Wagon();
        wagon.setRegion("eu-central-1");

        assertDoesNotThrow(() -> {
            wagon.connect(new Repository("test", "s3p://bucket/releases/"), new ProxyInfo());
            wagon.disconnect();
        });
    }

    @Test
    void buildsAClientWithStaticCredentials() {
        AuthenticationInfo authentication = new AuthenticationInfo();
        authentication.setUserName("AKIAEXAMPLE");
        authentication.setPassword("secret");

        S3Wagon wagon = new S3Wagon();
        wagon.setRegion("eu-central-1");

        assertDoesNotThrow(() -> {
            wagon.connect(new Repository("test", "s3p://bucket/releases/"), authentication);
            wagon.disconnect();
        });
    }

    /** Blank credentials must fall through to the default provider chain, not be sent as empty. */
    @Test
    void ignoresBlankCredentials() {
        AuthenticationInfo authentication = new AuthenticationInfo();
        authentication.setUserName("");
        authentication.setPassword("");

        S3Wagon wagon = new S3Wagon();
        wagon.setRegion("eu-central-1");

        assertDoesNotThrow(() -> {
            wagon.connect(new Repository("test", "s3p://bucket/releases/"), authentication);
            wagon.disconnect();
        });
    }
}
