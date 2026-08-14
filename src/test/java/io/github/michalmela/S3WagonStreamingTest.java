package io.github.michalmela;

import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.io.File;
import java.nio.file.Files;
import java.util.Random;

import static io.github.michalmela.S3WagonErrorMappingTest.serviceException;
import static io.github.michalmela.S3WagonKeyTest.connected;
import static io.github.michalmela.S3WagonKeyTest.destination;
import static io.github.michalmela.S3WagonPutTest.fileContaining;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The upload stream reports progress as the SDK reads it, so it has to stay faithful whichever read
 * method the SDK reaches for.
 */
class S3WagonStreamingTest {

    private static final String RESOURCE = "g/a/1.0/a-1.0.jar";

    @Test
    void uploadsCorrectlyWhenReadOneByteAtATime() throws Exception {
        byte[] payload = new byte[512];
        new Random(7).nextBytes(payload);
        FakeS3Client s3 = new FakeS3Client();
        s3.readUploadsAs(FakeS3Client.ReadStyle.SINGLE_BYTE);
        RecordingTransferListener listener = new RecordingTransferListener();

        S3Wagon wagon = connected(s3, "s3p://bucket/releases/");
        wagon.addTransferListener(listener);
        wagon.put(fileContaining(payload), RESOURCE);

        assertArrayEquals(payload, s3.lastPutBody());
        assertEquals(payload.length, listener.bytesReported());
    }

    @Test
    void uploadsCorrectlyWhenReadIntoAnOffsetBuffer() throws Exception {
        byte[] payload = new byte[9000];
        new Random(9).nextBytes(payload);
        FakeS3Client s3 = new FakeS3Client();
        s3.readUploadsAs(FakeS3Client.ReadStyle.OFFSET);
        RecordingTransferListener listener = new RecordingTransferListener();

        S3Wagon wagon = connected(s3, "s3p://bucket/releases/");
        wagon.addTransferListener(listener);
        wagon.put(fileContaining(payload), RESOURCE);

        assertArrayEquals(payload, s3.lastPutBody());
        assertEquals(payload.length, listener.bytesReported());
    }

    /**
     * A file can disappear between the existence check and the read. Opening it is the SDK's job by
     * then, so the failure has to come back as a transfer failure rather than an unchecked one.
     */
    @Test
    void reportsAFileThatCannotBeOpened() throws Exception {
        File directory = Files.createTempDirectory("s3-wagon-not-a-file").toFile();
        directory.deleteOnExit();
        FakeS3Client s3 = new FakeS3Client();

        assertThrows(TransferFailedException.class,
                () -> connected(s3, "s3p://bucket/releases/").put(directory, RESOURCE));
    }

    /** A failure while closing the response must not escape as an unchecked IOException. */
    @Test
    void reportsAFailureWhileClosingTheDownloadStream() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.seed("releases/" + RESOURCE, "artifact");
        s3.failOnStreamClose();

        assertThrows(TransferFailedException.class,
                () -> connected(s3, "s3p://bucket/releases/").get(RESOURCE, destination()));
    }

    @Test
    void mapsForbiddenFreshnessCheckToAuthorizationException() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(serviceException(403, "AccessDenied"));

        assertThrows(AuthorizationException.class,
                () -> connected(s3, "s3p://bucket/releases/").getIfNewer(RESOURCE, destination(), 1));
    }

    @Test
    void mapsNetworkFailureDuringFreshnessCheckToTransferFailed() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(SdkClientException.create("connection reset"));

        assertThrows(TransferFailedException.class,
                () -> connected(s3, "s3p://bucket/releases/").getIfNewer(RESOURCE, destination(), 1));
    }
}
