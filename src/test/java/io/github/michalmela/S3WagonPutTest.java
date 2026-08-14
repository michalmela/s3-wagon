package io.github.michalmela;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static io.github.michalmela.S3WagonKeyTest.connected;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class S3WagonPutTest {

    /**
     * The upload used to be read into a {@code byte[file.length()]} whose {@code read()} result was
     * discarded, so a short read produced a zero-padded artifact with no error anywhere. Uploading
     * a payload large enough to span many reads pins down the property that actually matters: what
     * lands in S3 is byte-for-byte what was on disk.
     */
    @Test
    void uploadsTheFileContentExactly() throws Exception {
        byte[] payload = new byte[3 * 1024 * 1024 + 17];
        new Random(42).nextBytes(payload);
        File source = fileContaining(payload);

        FakeS3Client s3 = new FakeS3Client();
        connected(s3, "s3p://bucket/releases/").put(source, "g/a/1.0/a-1.0.jar");

        assertArrayEquals(payload, s3.lastPutBody());
    }

    @Test
    void reportsTheContentLength() throws Exception {
        File source = fileContaining(new byte[1234]);

        FakeS3Client s3 = new FakeS3Client();
        connected(s3, "s3p://bucket/releases/").put(source, "g/a/1.0/a-1.0.jar");

        assertEquals(1234L, s3.lastPutRequest().contentLength());
    }

    @Test
    void uploadsEmptyFiles() throws Exception {
        File source = fileContaining(new byte[0]);

        FakeS3Client s3 = new FakeS3Client();
        connected(s3, "s3p://bucket/releases/").put(source, "g/a/1.0/a-1.0.pom");

        assertEquals(0L, s3.lastPutRequest().contentLength());
        assertArrayEquals(new byte[0], s3.lastPutBody());
    }

    static File fileContaining(byte[] content) throws Exception {
        Path file = Files.createTempFile("s3-wagon-upload", ".bin");
        Files.write(file, content);
        file.toFile().deleteOnExit();
        return file.toFile();
    }
}
