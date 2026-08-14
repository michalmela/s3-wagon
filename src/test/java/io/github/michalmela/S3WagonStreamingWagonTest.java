package io.github.michalmela;

import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.StreamingWagon;
import org.apache.maven.wagon.TransferFailedException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Random;

import static io.github.michalmela.S3WagonKeyTest.connected;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The resolver checks for StreamingWagon and uses it whenever it wants bytes in memory rather than
 * in a file - checksums, mostly. Without it every one of those goes through a temporary file.
 */
class S3WagonStreamingWagonTest {

    private static final String RESOURCE = "g/a/1.0/a-1.0.jar.sha1";
    private static final String KEY = "releases/" + RESOURCE;

    @Test
    void isAStreamingWagon() throws Exception {
        assertInstanceOf(StreamingWagon.class, connected(new FakeS3Client(), "s3p://bucket/releases/"));
    }

    @Test
    void downloadsIntoAStream() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.seed(KEY, "d41d8cd98f00b204e9800998ecf8427e");
        ByteArrayOutputStream destination = new ByteArrayOutputStream();

        connected(s3, "s3p://bucket/releases/").getToStream(RESOURCE, destination);

        assertEquals("d41d8cd98f00b204e9800998ecf8427e", destination.toString("UTF-8"));
        assertEquals(KEY, s3.lastGetRequest().key());
    }

    @Test
    void reportsAMissingResourceWhenStreaming() throws Exception {
        FakeS3Client s3 = new FakeS3Client();

        assertThrows(ResourceDoesNotExistException.class,
                () -> connected(s3, "s3p://bucket/releases/").getToStream(RESOURCE, new ByteArrayOutputStream()));
    }

    @Test
    void mapsFailuresWhenStreaming() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(SdkClientException.create("connection reset"));

        assertThrows(TransferFailedException.class,
                () -> connected(s3, "s3p://bucket/releases/").getToStream(RESOURCE, new ByteArrayOutputStream()));
    }

    @Test
    void honoursFreshnessWhenStreaming() throws Exception {
        Instant modified = Instant.parse("2026-03-01T10:00:00Z");
        FakeS3Client s3 = new FakeS3Client();
        s3.seed(KEY, "checksum".getBytes(StandardCharsets.UTF_8), modified);
        S3Wagon wagon = connected(s3, "s3p://bucket/releases/");

        assertFalse(wagon.getIfNewerToStream(RESOURCE, new ByteArrayOutputStream(),
                modified.plusSeconds(60).toEpochMilli()));
        assertTrue(wagon.getIfNewerToStream(RESOURCE, new ByteArrayOutputStream(),
                modified.minusSeconds(60).toEpochMilli()));
    }

    @Test
    void uploadsFromAStream() throws Exception {
        byte[] payload = "d41d8cd98f00b204e9800998ecf8427e".getBytes(StandardCharsets.UTF_8);
        FakeS3Client s3 = new FakeS3Client();

        connected(s3, "s3p://bucket/releases/")
                .putFromStream(new ByteArrayInputStream(payload), RESOURCE, payload.length, -1);

        assertEquals(KEY, s3.lastPutRequest().key());
        assertArrayEquals(payload, s3.lastPutBody());
        assertEquals((long) payload.length, s3.lastPutRequest().contentLength());
    }

    @Test
    void reportsProgressWhenUploadingFromAStream() throws Exception {
        byte[] payload = new byte[8192];
        FakeS3Client s3 = new FakeS3Client();
        RecordingTransferListener listener = new RecordingTransferListener();
        S3Wagon wagon = connected(s3, "s3p://bucket/releases/");
        wagon.addTransferListener(listener);

        wagon.putFromStream(new ByteArrayInputStream(payload), RESOURCE, payload.length, -1);

        assertEquals("initiated,started,progress,completed", listener.sequence());
        assertEquals(payload.length, listener.bytesReported());
    }

    @Test
    void mapsUploadFailuresWhenStreaming() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(SdkClientException.create("connection reset"));
        S3Wagon wagon = connected(s3, "s3p://bucket/releases/");

        assertThrows(TransferFailedException.class,
                () -> wagon.putFromStream(new ByteArrayInputStream(new byte[16]), RESOURCE, 16, -1));
    }

    /** A stream cannot be re-read, so an unknown length has to be buffered before it can be sent. */
    @Test
    void uploadsAStreamOfUnknownLength() throws Exception {
        byte[] payload = new byte[64 * 1024];
        new Random(5).nextBytes(payload);
        FakeS3Client s3 = new FakeS3Client();

        connected(s3, "s3p://bucket/releases/")
                .putFromStream(new ByteArrayInputStream(payload), RESOURCE);

        assertArrayEquals(payload, s3.lastPutBody());
    }

    @Test
    void spoolsAStreamBigEnoughToNeedMultipart() throws Exception {
        byte[] payload = new byte[7 * 1024 * 1024];
        new Random(6).nextBytes(payload);
        FakeS3Client s3 = new FakeS3Client();
        S3Wagon wagon = new S3Wagon(s3);
        wagon.setMultipartThreshold("1048576");
        wagon.setMultipartPartSize("5242880");
        wagon.connect(new org.apache.maven.wagon.repository.Repository("test", "s3p://bucket/releases/"));

        wagon.putFromStream(new ByteArrayInputStream(payload), RESOURCE, payload.length, -1);

        assertTrue(s3.isMultipartCompleted(), "should have gone through the multipart path");
        assertArrayEquals(payload, s3.objectContent(KEY));
    }
}
