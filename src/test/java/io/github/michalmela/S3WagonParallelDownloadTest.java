package io.github.michalmela;

import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.repository.Repository;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Random;

import static io.github.michalmela.S3WagonKeyTest.destination;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Large downloads can be split into ranged GETs. Chunks land at their own offsets in any order, so
 * the assembled file is the thing worth asserting on.
 */
class S3WagonParallelDownloadTest {

    private static final String RESOURCE = "g/a/1.0/a-1.0.jar";
    private static final String KEY = "releases/" + RESOURCE;

    @Test
    void isOffByDefault() {
        assertEquals(1, new S3Wagon(new FakeS3Client()).downloadConcurrency());
    }

    @Test
    void usesASingleGetWhenDisabled() throws Exception {
        FakeS3Client s3 = seeded(8 * 1024 * 1024);
        S3Wagon wagon = wagon(s3, null, null, null);

        wagon.get(RESOURCE, destination());

        assertEquals(0, s3.rangeRequestCount());
    }

    @Test
    void usesASingleGetBelowTheThreshold() throws Exception {
        FakeS3Client s3 = seeded(1024);
        S3Wagon wagon = wagon(s3, "4", "1048576", "4194304");

        wagon.get(RESOURCE, destination());

        assertEquals(0, s3.rangeRequestCount());
    }

    @Test
    void reassemblesTheFileFromRanges() throws Exception {
        byte[] payload = new byte[9 * 1024 * 1024 + 137];
        new Random(31).nextBytes(payload);
        FakeS3Client s3 = new FakeS3Client();
        s3.seed(KEY, payload, java.time.Instant.EPOCH);
        S3Wagon wagon = wagon(s3, "4", "1048576", "1048576");

        File file = destination();
        wagon.get(RESOURCE, file);

        assertEquals(10, s3.rangeRequestCount());
        assertArrayEquals(payload, Files.readAllBytes(file.toPath()));
    }

    @Test
    void reportsEveryByteExactlyOnce() throws Exception {
        byte[] payload = new byte[9 * 1024 * 1024];
        FakeS3Client s3 = new FakeS3Client();
        s3.seed(KEY, payload, java.time.Instant.EPOCH);
        RecordingTransferListener listener = new RecordingTransferListener();
        S3Wagon wagon = wagon(s3, "4", "1048576", "1048576");
        wagon.addTransferListener(listener);

        wagon.get(RESOURCE, destination());

        assertEquals(payload.length, listener.bytesReported());
        assertEquals("initiated,started,progress,completed", listener.sequence());
    }

    @Test
    void worksWhenTheFileIsSmallerThanOneChunk() throws Exception {
        byte[] payload = new byte[2 * 1024 * 1024];
        new Random(32).nextBytes(payload);
        FakeS3Client s3 = new FakeS3Client();
        s3.seed(KEY, payload, java.time.Instant.EPOCH);
        S3Wagon wagon = wagon(s3, "4", "16777216", "1048576");

        File file = destination();
        wagon.get(RESOURCE, file);

        assertEquals(1, s3.rangeRequestCount());
        assertArrayEquals(payload, Files.readAllBytes(file.toPath()));
    }

    @Test
    void reportsAMissingResourceBeforeSplitting() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        S3Wagon wagon = wagon(s3, "4", "1048576", "1048576");

        assertThrows(ResourceDoesNotExistException.class, () -> wagon.get(RESOURCE, destination()));
    }

    @Test
    void leavesNoPartialFileWhenARangeFails() throws Exception {
        byte[] payload = new byte[9 * 1024 * 1024];
        FakeS3Client s3 = new FakeS3Client() {
            @Override
            public synchronized software.amazon.awssdk.core.ResponseInputStream<
                    software.amazon.awssdk.services.s3.model.GetObjectResponse> getObject(
                    software.amazon.awssdk.services.s3.model.GetObjectRequest request) {
                if (request.range() != null && request.range().contains("=2")) {
                    throw software.amazon.awssdk.core.exception.SdkClientException.create("range failed");
                }
                return super.getObject(request);
            }
        };
        s3.seed(KEY, payload, java.time.Instant.EPOCH);
        S3Wagon wagon = wagon(s3, "4", "1048576", "1048576");
        File file = destination();

        assertThrows(Exception.class, () -> wagon.get(RESOURCE, file));
        assertFalse(file.exists(), "a failed ranged download must not leave a partial file behind");
    }

    @Test
    void clampsConcurrency() {
        S3Wagon wagon = new S3Wagon(new FakeS3Client());
        wagon.setDownloadConcurrency("999");
        assertEquals(16, wagon.downloadConcurrency());
        wagon.setDownloadConcurrency("0");
        assertEquals(1, wagon.downloadConcurrency());
    }

    @Test
    void neverUsesTinyChunks() {
        S3Wagon wagon = new S3Wagon(new FakeS3Client());
        wagon.setDownloadChunkSize("1024");
        assertTrue(wagon.downloadChunkSize() >= 1024L * 1024);
    }

    private static FakeS3Client seeded(int size) {
        FakeS3Client s3 = new FakeS3Client();
        s3.seed(KEY, new byte[size], java.time.Instant.EPOCH);
        return s3;
    }

    private static S3Wagon wagon(FakeS3Client s3, String concurrency, String chunk, String threshold)
            throws Exception {
        S3Wagon wagon = new S3Wagon(s3);
        if (concurrency != null) {
            wagon.setDownloadConcurrency(concurrency);
        }
        if (chunk != null) {
            wagon.setDownloadChunkSize(chunk);
        }
        if (threshold != null) {
            wagon.setDownloadThreshold(threshold);
        }
        wagon.connect(new Repository("test", "s3p://bucket/releases/"));
        return wagon;
    }
}
