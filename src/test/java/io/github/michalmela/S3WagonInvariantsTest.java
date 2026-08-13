package io.github.michalmela;

import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.proxy.ProxyInfo;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The properties the ClusterFuzzLite targets assert, checked here against randomised input as well.
 *
 * <p>Fuzzing only runs in CI and only on a schedule; these run on every build. The generator leans
 * on the characters that have actually caused trouble - slashes, pipes, spaces, percent signs -
 * rather than uniform noise, which would almost never produce an interesting path.
 */
class S3WagonInvariantsTest {

    private static final String INTERESTING = "//..|,%: \t\n\\abcé中";
    private static final int ITERATIONS = 20_000;

    private static String randomInput(Random random, int maxLength) {
        int length = random.nextInt(maxLength);
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(INTERESTING.charAt(random.nextInt(INTERESTING.length())));
        }
        return builder.toString();
    }

    @Test
    void baseDirectoryAlwaysProducesAUsableKeyPrefix() {
        Random random = new Random(20260813);
        for (int i = 0; i < ITERATIONS; i++) {
            String input = randomInput(random, 24);

            String prefix = S3Wagon.baseDirectory(input);

            assertNotNull(prefix, () -> "null for " + quoted(input));
            assertFalse(prefix.startsWith("/"), () -> "leading slash from " + quoted(input));
            assertTrue(prefix.isEmpty() || prefix.endsWith("/"), () -> "no trailing slash from " + quoted(input));
            assertTrue(prefix.equals(S3Wagon.baseDirectory(prefix)), () -> "not idempotent: " + quoted(prefix));
        }
    }

    @Test
    void proxyEndpointEitherParsesOrReportsWhyNot() {
        Random random = new Random(4711);
        for (int i = 0; i < ITERATIONS; i++) {
            String host = randomInput(random, 20);
            if (host.trim().isEmpty()) {
                continue;
            }
            ProxyInfo proxy = new ProxyInfo();
            proxy.setHost(host);
            proxy.setPort(random.nextInt(65536));
            proxy.setType(random.nextBoolean() ? "https" : "http");

            try {
                URI endpoint = S3Wagon.proxyEndpoint(proxy);
                // The failure mode that silently disabled the proxy was a URI with no host.
                assertNotNull(endpoint.getHost(), () -> "no host in " + endpoint + " from " + quoted(host));
                assertTrue("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()));
            } catch (ConnectionException expected) {
                // Reporting a malformed host is the contract; anything else is not.
            }
        }
    }

    @Test
    void nonProxyHostsNeverYieldsBlankOrPaddedEntries() {
        Random random = new Random(1234);
        for (int i = 0; i < ITERATIONS; i++) {
            ProxyInfo proxy = new ProxyInfo();
            proxy.setNonProxyHosts(randomInput(random, 30));

            for (String host : S3Wagon.nonProxyHosts(proxy)) {
                assertFalse(host.isEmpty(), "empty entry");
                assertTrue(host.equals(host.trim()), () -> "untrimmed: " + quoted(host));
            }
        }
    }

    private static String quoted(String value) {
        return "\"" + value + "\"";
    }
}
