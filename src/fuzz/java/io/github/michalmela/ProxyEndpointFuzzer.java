package io.github.michalmela;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.proxy.ProxyInfo;

import java.net.URI;

/**
 * A proxy host arrives from settings.xml or the environment, so it is user input in every practical
 * sense. Hand-testing eight values here already found an IllegalArgumentException escaping connect;
 * this looks for the rest.
 */
public final class ProxyEndpointFuzzer {

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        String host = data.consumeString(200);
        String type = data.consumeBoolean() ? "https" : data.consumeString(20);
        int port = data.consumeInt(0, 65535);

        // awsHttpClient only calls this for a non-blank host, so neither does the fuzzer.
        if (host == null || host.trim().isEmpty()) {
            return;
        }

        ProxyInfo proxy = new ProxyInfo();
        proxy.setHost(host);
        proxy.setType(type);
        proxy.setPort(port);

        URI endpoint;
        try {
            endpoint = S3Wagon.proxyEndpoint(proxy);
        } catch (ConnectionException expected) {
            // A host that cannot form a URL must be reported, not thrown blindly. That is the
            // contract; anything else reaching this point is the bug.
            return;
        }

        String scheme = endpoint.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new AssertionError("unexpected scheme " + scheme + " for host \"" + host + "\"");
        }
        // A URI that parses but has no host is the shape that silently disabled the proxy.
        if (endpoint.getHost() == null) {
            throw new AssertionError("endpoint has no host: " + endpoint + " from \"" + host + "\"");
        }
    }

    private ProxyEndpointFuzzer() {
    }
}
