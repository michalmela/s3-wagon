package io.github.michalmela;

import org.apache.maven.wagon.repository.Repository;
import org.apache.maven.wagon.resource.Resource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static io.github.michalmela.S3WagonKeyTest.destination;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Transfer listeners read the size and age of a transfer off the Resource. Leaving them unset means
 * Maven reports an unknown content length and cannot show meaningful progress.
 */
class S3WagonResourceMetadataTest {

    private static final String RESOURCE = "g/a/1.0/a-1.0.jar";

    @Test
    void publishesTheObjectSizeAndAgeOnDownload() throws Exception {
        byte[] payload = "an artifact".getBytes(StandardCharsets.UTF_8);
        Instant modified = Instant.parse("2026-02-03T04:05:06Z");
        FakeS3Client s3 = new FakeS3Client();
        s3.seed("releases/" + RESOURCE, payload, modified);

        RecordingTransferListener listener = new RecordingTransferListener();
        S3Wagon wagon = new S3Wagon(s3);
        wagon.addTransferListener(listener);
        wagon.connect(new Repository("test", "s3p://bucket/releases/"));

        wagon.get(RESOURCE, destination());

        Resource transferred = listener.completedResource();
        assertEquals(payload.length, transferred.getContentLength());
        assertEquals(modified.toEpochMilli(), transferred.getLastModified());
    }

    @Test
    void publishesTheFileSizeOnUpload() throws Exception {
        byte[] payload = new byte[4096];
        RecordingTransferListener listener = new RecordingTransferListener();
        S3Wagon wagon = new S3Wagon(new FakeS3Client());
        wagon.addTransferListener(listener);
        wagon.connect(new Repository("test", "s3p://bucket/releases/"));

        wagon.put(S3WagonPutTest.fileContaining(payload), RESOURCE);

        assertEquals(payload.length, listener.completedResource().getContentLength());
    }
}
