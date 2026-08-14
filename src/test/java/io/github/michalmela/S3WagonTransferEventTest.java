package io.github.michalmela;

import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.repository.Repository;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.io.File;

import static io.github.michalmela.S3WagonKeyTest.destination;
import static io.github.michalmela.S3WagonPutTest.fileContaining;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Maven reports transfer progress from the events a wagon fires. put() used to fire none at all, so
 * uploads were invisible and a failed upload never reached the listeners.
 */
class S3WagonTransferEventTest {

    private static final String RESOURCE = "g/a/1.0/a-1.0.jar";

    @Test
    void reportsTheUploadLifecycle() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        RecordingTransferListener listener = new RecordingTransferListener();
        S3Wagon wagon = connected(s3, listener);

        wagon.put(fileContaining(new byte[64 * 1024]), RESOURCE);

        assertEquals("initiated,started,progress,completed", listener.sequence());
    }

    @Test
    void reportsEveryUploadedByte() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        RecordingTransferListener listener = new RecordingTransferListener();
        S3Wagon wagon = connected(s3, listener);

        wagon.put(fileContaining(new byte[64 * 1024 + 7]), RESOURCE);

        assertEquals(64 * 1024 + 7, listener.bytesReported());
    }

    @Test
    void reportsUploadFailures() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        SdkClientException cause = SdkClientException.create("connection reset");
        s3.failWith(cause);
        RecordingTransferListener listener = new RecordingTransferListener();
        S3Wagon wagon = connected(s3, listener);
        File source = fileContaining(new byte[16]);

        assertThrows(TransferFailedException.class, () -> wagon.put(source, RESOURCE));

        assertEquals("initiated,started,progress,error", listener.sequence());
        assertSame(cause, listener.error());
    }

    /**
     * get() used to fire started and completed around getTransfer, which fires both itself, so
     * every download was reported to Maven twice.
     */
    @Test
    void reportsTheDownloadLifecycleExactlyOnce() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.seed("releases/" + RESOURCE, "artifact");
        RecordingTransferListener listener = new RecordingTransferListener();
        S3Wagon wagon = connected(s3, listener);

        wagon.get(RESOURCE, destination());

        assertEquals("initiated,started,progress,completed", listener.sequence());
    }

    @Test
    void reportsDownloadFailures() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        SdkClientException cause = SdkClientException.create("connection reset");
        s3.failWith(cause);
        RecordingTransferListener listener = new RecordingTransferListener();
        S3Wagon wagon = connected(s3, listener);
        File destination = destination();

        assertThrows(TransferFailedException.class, () -> wagon.get(RESOURCE, destination));

        // The request fails before any byte is streamed, so the transfer never starts.
        assertEquals("initiated,error", listener.sequence());
        assertSame(cause, listener.error());
    }

    private static S3Wagon connected(FakeS3Client s3, RecordingTransferListener listener) throws Exception {
        S3Wagon wagon = new S3Wagon(s3);
        wagon.addTransferListener(listener);
        wagon.connect(new Repository("test", "s3p://bucket/releases/"));
        return wagon;
    }
}
