package io.github.michalmela;

import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.util.List;

import static io.github.michalmela.S3WagonErrorMappingTest.serviceException;
import static io.github.michalmela.S3WagonKeyTest.connected;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AbstractWagon's getFileList throws UnsupportedOperationException - an unchecked exception
 * escaping the Wagon contract - so anything browsing an S3 repository used to get a stack trace.
 */
class S3WagonFileListTest {

    @Test
    void listsObjectsAndDirectoriesBelowAPrefix() throws Exception {
        FakeS3Client s3 = seeded();

        List<String> names = connected(s3, "s3p://bucket/releases/").getFileList("g/a/");

        assertEquals(List.of("1.0/", "1.1/", "maven-metadata.xml"), names);
    }

    @Test
    void listsTheContentsOfAVersionDirectory() throws Exception {
        FakeS3Client s3 = seeded();

        List<String> names = connected(s3, "s3p://bucket/releases/").getFileList("g/a/1.0/");

        assertEquals(List.of("a-1.0.jar", "a-1.0.pom"), names);
    }

    /** Wagon callers pass directories with or without the trailing slash. */
    @Test
    void toleratesAMissingTrailingSlash() throws Exception {
        FakeS3Client s3 = seeded();

        assertEquals(connected(s3, "s3p://bucket/releases/").getFileList("g/a/1.0/"),
                connected(s3, "s3p://bucket/releases/").getFileList("g/a/1.0"));
    }

    @Test
    void listsTheRepositoryRoot() throws Exception {
        FakeS3Client s3 = seeded();

        assertEquals(List.of("g/"), connected(s3, "s3p://bucket/releases/").getFileList(""));
    }

    @Test
    void followsPaginationToTheEnd() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        for (int i = 0; i < 25; i++) {
            s3.seed(String.format("releases/g/a/1.0/part-%02d.jar", i), "artifact");
        }
        s3.listPageSize(10);

        List<String> names = connected(s3, "s3p://bucket/releases/").getFileList("g/a/1.0/");

        assertEquals(25, names.size());
        assertEquals("part-00.jar", names.get(0));
        assertEquals("part-24.jar", names.get(24));
        assertTrue(s3.listRequestCount() >= 3, "should have paged through the listing");
    }

    @Test
    void reportsAnEmptyDirectoryAsMissing() throws Exception {
        FakeS3Client s3 = seeded();

        assertThrows(ResourceDoesNotExistException.class,
                () -> connected(s3, "s3p://bucket/releases/").getFileList("g/nope/"));
    }

    @Test
    void mapsForbiddenListingToAuthorizationException() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(serviceException(403, "AccessDenied"));

        assertThrows(AuthorizationException.class,
                () -> connected(s3, "s3p://bucket/releases/").getFileList("g/a/"));
    }

    @Test
    void mapsNetworkFailureToTransferFailed() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(SdkClientException.create("connection reset"));

        assertThrows(TransferFailedException.class,
                () -> connected(s3, "s3p://bucket/releases/").getFileList("g/a/"));
    }

    private static FakeS3Client seeded() {
        FakeS3Client s3 = new FakeS3Client();
        s3.seed("releases/g/a/1.0/a-1.0.jar", "artifact");
        s3.seed("releases/g/a/1.0/a-1.0.pom", "pom");
        s3.seed("releases/g/a/1.1/a-1.1.jar", "artifact");
        s3.seed("releases/g/a/maven-metadata.xml", "metadata");
        return s3;
    }
}
