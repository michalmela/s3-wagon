package io.github.michalmela;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import org.apache.maven.wagon.proxy.ProxyInfo;

import java.util.Set;

/** Wagon separates non-proxy hosts with '|'; the SDK wants them one by one and cleanly. */
public final class NonProxyHostsFuzzer {

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        ProxyInfo proxy = new ProxyInfo();
        proxy.setNonProxyHosts(data.consumeRemainingAsString());

        Set<String> hosts = S3Wagon.nonProxyHosts(proxy);

        if (hosts == null) {
            throw new AssertionError("nonProxyHosts returned null");
        }
        for (String host : hosts) {
            if (host.isEmpty()) {
                throw new AssertionError("empty entry in " + hosts);
            }
            if (!host.equals(host.trim())) {
                throw new AssertionError("untrimmed entry: \"" + host + "\"");
            }
        }
    }

    private NonProxyHostsFuzzer() {
    }
}
