package io.github.michalmela;

import org.apache.maven.wagon.authorization.AuthorizationException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sso.auth.ExpiredTokenException;

import java.io.ByteArrayOutputStream;

import static io.github.michalmela.S3WagonKeyTest.connected;
import static io.github.michalmela.S3WagonKeyTest.destination;
import static io.github.michalmela.S3WagonKeyTest.sourceFile;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Working `aws sso login` credentials are this project's headline feature, and an expired SSO token
 * is the failure its users will actually hit. It has to come back as an authorization problem, not
 * as a generic transfer failure - Maven tells the user to re-authenticate on the former.
 */
class S3WagonSsoTest {

    private static final String RESOURCE = "g/a/1.0/a-1.0.jar";

    private static ExpiredTokenException expiredToken() {
        return ExpiredTokenException.builder().message("The SSO session has expired").build();
    }

    @Test
    void reportsAnExpiredSsoTokenOnDownload() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(expiredToken());

        assertThrows(AuthorizationException.class,
                () -> connected(s3, "s3p://bucket/releases/").get(RESOURCE, destination()));
    }

    @Test
    void reportsAnExpiredSsoTokenOnUpload() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(expiredToken());
        java.io.File source = sourceFile("artifact");

        assertThrows(AuthorizationException.class,
                () -> connected(s3, "s3p://bucket/releases/").put(source, RESOURCE));
    }

    @Test
    void reportsAnExpiredSsoTokenOnLookup() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(expiredToken());

        assertThrows(AuthorizationException.class,
                () -> connected(s3, "s3p://bucket/releases/").resourceExists(RESOURCE));
    }

    @Test
    void reportsAnExpiredSsoTokenOnListing() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(expiredToken());

        assertThrows(AuthorizationException.class,
                () -> connected(s3, "s3p://bucket/releases/").getFileList("g/a/"));
    }

    @Test
    void reportsAnExpiredSsoTokenOnFreshnessCheck() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(expiredToken());

        assertThrows(AuthorizationException.class,
                () -> connected(s3, "s3p://bucket/releases/").getIfNewer(RESOURCE, destination(), 1));
    }

    @Test
    void reportsAnExpiredSsoTokenWhenStreaming() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(expiredToken());

        assertThrows(AuthorizationException.class,
                () -> connected(s3, "s3p://bucket/releases/").getToStream(RESOURCE, new ByteArrayOutputStream()));
    }

    @Test
    void keepsTheSsoFailureAsTheCause() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        ExpiredTokenException cause = expiredToken();
        s3.failWith(cause);

        AuthorizationException thrown = assertThrows(AuthorizationException.class,
                () -> connected(s3, "s3p://bucket/releases/").get(RESOURCE, destination()));
        assertSame(cause, thrown.getCause());
    }
}
