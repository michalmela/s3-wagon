package io.github.michalmela;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;

/**
 * Every repository URL a user can write ends up here, and the shape of the result decides where
 * artifacts land. Getting it wrong is not a crash - it is a silent write to the wrong prefix, which
 * is exactly the bug this project started with.
 */
public final class BaseDirectoryFuzzer {

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        String basedir = data.consumeRemainingAsString();

        String prefix = S3Wagon.baseDirectory(basedir);

        if (prefix == null) {
            throw new AssertionError("baseDirectory returned null for: " + quoted(basedir));
        }
        // An S3 key has no leading slash, and the prefix has to end with one so it cannot run
        // into the resource name that gets appended to it.
        if (prefix.startsWith("/")) {
            throw new AssertionError("prefix starts with a slash: " + quoted(prefix));
        }
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            throw new AssertionError("prefix does not end with a slash: " + quoted(prefix));
        }
        // Normalising an already normalised prefix must change nothing, or the shape depends on
        // how many times the value has been through here.
        String again = S3Wagon.baseDirectory(prefix);
        if (!prefix.equals(again)) {
            throw new AssertionError("not idempotent: " + quoted(prefix) + " became " + quoted(again));
        }
    }

    private static String quoted(String value) {
        return "\"" + value + "\"";
    }

    private BaseDirectoryFuzzer() {
    }
}
