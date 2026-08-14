package io.github.michalmela;

import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;

import static io.github.michalmela.S3WagonKeyTest.connected;
import static io.github.michalmela.S3WagonKeyTest.destination;
import static io.github.michalmela.S3WagonKeyTest.sourceFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every failure has to leave the wagon as one of the three WagonException types. An SDK exception
 * escaping unchecked bypasses the resolver's error classification entirely, so a network blip or an
 * expired token surfaces as an internal error rather than as a diagnosable transfer failure.
 */
class S3WagonErrorMappingTest {

    private static final String RESOURCE = "g/a/1.0/a-1.0.jar";

    @Test
    void mapsNetworkFailureOnDownloadToTransferFailed() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(SdkClientException.create("connection reset", new UncheckedIOException(new IOException("boom"))));

        TransferFailedException thrown = assertThrows(TransferFailedException.class,
                () -> connected(s3, "s3p://bucket/releases/").get(RESOURCE, destination()));
        assertTrue(thrown.getMessage().contains(RESOURCE), thrown.getMessage());
    }

    @Test
    void mapsNetworkFailureOnUploadToTransferFailed() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(SdkClientException.create("connection reset"));
        File source = sourceFile("artifact");

        assertThrows(TransferFailedException.class,
                () -> connected(s3, "s3p://bucket/releases/").put(source, RESOURCE));
    }

    @Test
    void mapsForbiddenUploadToAuthorizationException() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(serviceException(403, "AccessDenied"));
        File source = sourceFile("artifact");

        assertThrows(AuthorizationException.class,
                () -> connected(s3, "s3p://bucket/releases/").put(source, RESOURCE));
    }

    @Test
    void mapsForbiddenDownloadToAuthorizationException() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(serviceException(403, "AccessDenied"));

        assertThrows(AuthorizationException.class,
                () -> connected(s3, "s3p://bucket/releases/").get(RESOURCE, destination()));
    }

    @Test
    void mapsMissingBucketOnUploadToResourceDoesNotExist() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(NoSuchBucketException.builder().message("no bucket").build());
        File source = sourceFile("artifact");

        assertThrows(ResourceDoesNotExistException.class,
                () -> connected(s3, "s3p://bucket/releases/").put(source, RESOURCE));
    }

    @Test
    void reportsMissingLocalFileRatherThanFailingInsideTheSdk() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        File missing = new File(destination(), "definitely-absent.jar");

        assertThrows(ResourceDoesNotExistException.class,
                () -> connected(s3, "s3p://bucket/releases/").put(missing, RESOURCE));
        assertEquals(0, s3.putCount());
    }

    @Test
    void reportsMissingResourceOnDownload() throws Exception {
        FakeS3Client s3 = new FakeS3Client();

        assertThrows(ResourceDoesNotExistException.class,
                () -> connected(s3, "s3p://bucket/releases/").get("absent.jar", destination()));
    }

    /**
     * A HEAD response has no body, so a missing object can arrive as a bare 404 rather than as a
     * NoSuchKeyException. Either shape has to mean "not there", not "the build failed".
     */
    @Test
    void treatsBare404OnLookupAsAbsent() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(serviceException(404, null));

        assertFalse(connected(s3, "s3p://bucket/releases/").resourceExists(RESOURCE));
    }

    @Test
    void reportsExistingResource() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.seed("releases/" + RESOURCE, "artifact");

        assertTrue(connected(s3, "s3p://bucket/releases/").resourceExists(RESOURCE));
    }

    @Test
    void mapsForbiddenLookupToAuthorizationException() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(serviceException(403, "AccessDenied"));

        assertThrows(AuthorizationException.class,
                () -> connected(s3, "s3p://bucket/releases/").resourceExists(RESOURCE));
    }

    @Test
    void mapsNetworkFailureOnLookupToTransferFailed() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(SdkClientException.create("connection reset"));

        assertThrows(TransferFailedException.class,
                () -> connected(s3, "s3p://bucket/releases/").resourceExists(RESOURCE));
    }

    @Test
    void keepsTheOriginalFailureAsTheCause() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        SdkClientException cause = SdkClientException.create("connection reset");
        s3.failWith(cause);

        TransferFailedException thrown = assertThrows(TransferFailedException.class,
                () -> connected(s3, "s3p://bucket/releases/").get(RESOURCE, destination()));
        assertSame(cause, thrown.getCause());
    }

    static S3Exception serviceException(int statusCode, String errorCode) {
        return (S3Exception) S3Exception.builder()
                .statusCode(statusCode)
                .message("status " + statusCode)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode(errorCode)
                        .sdkHttpResponse(SdkHttpResponse.builder().statusCode(statusCode).build())
                        .build())
                .build();
    }
}
