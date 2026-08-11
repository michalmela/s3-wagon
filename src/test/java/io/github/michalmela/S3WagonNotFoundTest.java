package io.github.michalmela;

import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;

import static io.github.michalmela.S3WagonErrorMappingTest.serviceException;
import static io.github.michalmela.S3WagonKeyTest.connected;
import static io.github.michalmela.S3WagonKeyTest.destination;
import static io.github.michalmela.S3WagonKeyTest.sourceFile;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A HEAD or GET for an object that is not there can come back as a bare HTTP 404, with no error
 * code in the body for the SDK to turn into a NoSuchKeyException. Treating that as a retryable
 * transfer failure makes Maven retry something that will never succeed - the bug fixed in 1.0.2.
 *
 * <p>That fix is preserved here, and extended to every operation the wagon has gained since. Each
 * of these asserts on a bare 404 specifically; the NoSuchKeyException cases are covered separately.
 */
class S3WagonNotFoundTest {

    private static final String RESOURCE = "g/a/1.0/a-1.0.jar";

    private static FakeS3Client failingWith404() {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(serviceException(404, null));
        return s3;
    }

    @Test
    void treatsBare404OnDownloadAsMissing() throws Exception {
        assertThrows(ResourceDoesNotExistException.class,
                () -> connected(failingWith404(), "s3p://bucket/releases/").get(RESOURCE, destination()));
    }

    @Test
    void treatsBare404OnStreamingDownloadAsMissing() throws Exception {
        assertThrows(ResourceDoesNotExistException.class,
                () -> connected(failingWith404(), "s3p://bucket/releases/")
                        .getToStream(RESOURCE, new ByteArrayOutputStream()));
    }

    @Test
    void treatsBare404OnFreshnessCheckAsMissing() throws Exception {
        assertThrows(ResourceDoesNotExistException.class,
                () -> connected(failingWith404(), "s3p://bucket/releases/")
                        .getIfNewer(RESOURCE, destination(), 1));
    }

    @Test
    void treatsBare404OnLookupAsAbsent() throws Exception {
        assertFalse(connected(failingWith404(), "s3p://bucket/releases/").resourceExists(RESOURCE));
    }

    @Test
    void treatsBare404OnListingAsMissing() throws Exception {
        assertThrows(ResourceDoesNotExistException.class,
                () -> connected(failingWith404(), "s3p://bucket/releases/").getFileList("g/a/"));
    }

    @Test
    void treatsBare404OnUploadAsMissing() throws Exception {
        File source = sourceFile("artifact");
        assertThrows(ResourceDoesNotExistException.class,
                () -> connected(failingWith404(), "s3p://bucket/releases/").put(source, RESOURCE));
    }
}
