package io.github.michalmela;

import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Returning no provider is meaningful: it leaves the SDK's default chain in charge, which is what
 * makes `aws sso login` work without exporting anything.
 */
class S3WagonCredentialsTest {

    private final Map<String, String> environment = new HashMap<>();

    @AfterEach
    void clearSystemProperties() {
        System.clearProperty("s3wagon.sessionToken");
        System.clearProperty("s3wagon.profile");
    }

    @Test
    void fallsBackToTheDefaultChainWithoutConfiguration() {
        assertNull(wagon().credentialsProvider(null));
    }

    @Test
    void fallsBackToTheDefaultChainWhenCredentialsAreBlank() {
        AuthenticationInfo authentication = new AuthenticationInfo();
        authentication.setUserName("");
        authentication.setPassword("");

        assertNull(wagon().credentialsProvider(authentication));
    }

    @Test
    void usesStaticCredentialsFromSettings() {
        AwsCredentials credentials = wagon().credentialsProvider(authentication()).resolveCredentials();

        assertEquals("AKIAEXAMPLE", credentials.accessKeyId());
        assertEquals("secret", credentials.secretAccessKey());
    }

    @Test
    void addsASessionTokenWhenConfigured() {
        S3Wagon wagon = wagon();
        wagon.setSessionToken("session-token");

        AwsCredentials credentials = wagon.credentialsProvider(authentication()).resolveCredentials();

        AwsSessionCredentials session = assertInstanceOf(AwsSessionCredentials.class, credentials);
        assertEquals("session-token", session.sessionToken());
    }

    @Test
    void readsTheSessionTokenFromTheEnvironment() {
        environment.put("S3WAGON_SESSION_TOKEN", "from-environment");

        AwsCredentials credentials = wagon().credentialsProvider(authentication()).resolveCredentials();

        assertEquals("from-environment", assertInstanceOf(AwsSessionCredentials.class, credentials).sessionToken());
    }

    @Test
    void usesANamedProfileWhenThereAreNoStaticCredentials() {
        S3Wagon wagon = wagon();
        wagon.setProfile("build");

        assertInstanceOf(ProfileCredentialsProvider.class, wagon.credentialsProvider(null));
    }

    /** Explicit credentials in settings.xml are more specific than a profile, so they win. */
    @Test
    void prefersStaticCredentialsOverAProfile() {
        S3Wagon wagon = wagon();
        wagon.setProfile("build");

        assertEquals("AKIAEXAMPLE",
                wagon.credentialsProvider(authentication()).resolveCredentials().accessKeyId());
    }

    @Test
    void readsTheProfileFromASystemProperty() {
        System.setProperty("s3wagon.profile", "build");

        assertInstanceOf(ProfileCredentialsProvider.class, wagon().credentialsProvider(null));
    }

    private static AuthenticationInfo authentication() {
        AuthenticationInfo authentication = new AuthenticationInfo();
        authentication.setUserName("AKIAEXAMPLE");
        authentication.setPassword("secret");
        return authentication;
    }

    private S3Wagon wagon() {
        S3Wagon wagon = new S3Wagon(new FakeS3Client());
        wagon.setEnvironment(environment::get);
        return wagon;
    }
}
