package io.github.michalmela;

import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;

import static io.github.michalmela.S3WagonKeyTest.connected;
import static io.github.michalmela.S3WagonKeyTest.destination;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The timestamp handed to getIfNewer is in milliseconds - it comes from File.lastModified(), and
 * the wagon implementations that ship with Maven compare it against a millisecond
 * Resource.setLastModified(). Comparing it against epoch *seconds* made the left-hand side roughly
 * a thousand times too small, so isNewer was false for any real timestamp and a cached artifact was
 * never refreshed.
 */
class S3WagonGetIfNewerTest {

    private static final String RESOURCE = "g/a/1.0/a-1.0.jar";
    private static final String KEY = "releases/" + RESOURCE;

    private static final Instant REMOTE_MODIFIED = Instant.parse("2026-01-15T12:00:00Z");

    @Test
    void downloadsWhenTheRemoteObjectIsNewer() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.seed(KEY, "artifact".getBytes(), REMOTE_MODIFIED);
        File destination = destination();

        long localMillis = REMOTE_MODIFIED.minusSeconds(3600).toEpochMilli();
        boolean downloaded = connected(s3, "s3p://bucket/releases/").getIfNewer(RESOURCE, destination, localMillis);

        assertTrue(downloaded, "a newer remote object should be downloaded");
        assertEquals(KEY, s3.lastGetRequest().key());
    }

    @Test
    void skipsDownloadWhenTheLocalCopyIsUpToDate() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.seed(KEY, "artifact".getBytes(), REMOTE_MODIFIED);

        long localMillis = REMOTE_MODIFIED.plusSeconds(3600).toEpochMilli();
        boolean downloaded = connected(s3, "s3p://bucket/releases/").getIfNewer(RESOURCE, destination(), localMillis);

        assertFalse(downloaded, "an older remote object should not be downloaded");
    }

    @Test
    void skipsDownloadWhenTimestampsMatchExactly() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.seed(KEY, "artifact".getBytes(), REMOTE_MODIFIED);

        boolean downloaded = connected(s3, "s3p://bucket/releases/")
                .getIfNewer(RESOURCE, destination(), REMOTE_MODIFIED.toEpochMilli());

        assertFalse(downloaded, "an unchanged object should not be downloaded");
    }

    /**
     * A one-second difference is invisible if the comparison is done in seconds after the remote
     * value has been truncated, which is exactly how the bug hid.
     */
    @Test
    void detectsSubSecondFreshness() throws Exception {
        Instant remote = Instant.parse("2026-01-15T12:00:00.750Z");
        FakeS3Client s3 = new FakeS3Client();
        s3.seed(KEY, "artifact".getBytes(), remote);

        boolean downloaded = connected(s3, "s3p://bucket/releases/")
                .getIfNewer(RESOURCE, destination(), Instant.parse("2026-01-15T12:00:00.250Z").toEpochMilli());

        assertTrue(downloaded, "a remote object newer by 500ms should still be downloaded");
    }

    @Test
    void alwaysDownloadsWhenThereIsNoLocalCopy() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.seed(KEY, "artifact".getBytes(), REMOTE_MODIFIED);

        assertTrue(connected(s3, "s3p://bucket/releases/").getIfNewer(RESOURCE, destination(), 0));
    }

    @Test
    void reportsMissingResource() throws Exception {
        FakeS3Client s3 = new FakeS3Client();

        assertThrows(ResourceDoesNotExistException.class,
                () -> connected(s3, "s3p://bucket/releases/").getIfNewer(RESOURCE, destination(), 1));
    }
}
