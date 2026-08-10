package io.github.michalmela;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.michalmela.S3WagonKeyTest.connected;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Artifacts are often served straight out of the bucket, so the stored content type matters.
 * Wrapping the upload stream to report progress must not downgrade everything to octet-stream.
 */
class S3WagonContentTypeTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "a-1.0.jar, application/java-archive",
            "maven-metadata.xml, application/xml",
            // The SDK's mime table has no entry for .pom, so this is what fromFile would give too.
            "a-1.0.pom, application/octet-stream",
    })
    void keepsTheContentTypeDerivedFromTheFileName(String name, String expected) throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        connected(s3, "s3p://bucket/releases/").put(fileNamed(name), "g/a/1.0/" + name);

        assertEquals(expected, s3.lastPutBodyContentType());
    }

    @Test
    void fallsBackForUnknownExtensions() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        connected(s3, "s3p://bucket/releases/").put(fileNamed("a-1.0.unknownext"), "g/a/1.0/a-1.0.unknownext");

        assertEquals("application/octet-stream", s3.lastPutBodyContentType());
    }

    private static File fileNamed(String name) throws Exception {
        Path directory = Files.createTempDirectory("s3-wagon-content-type");
        Path file = directory.resolve(name);
        Files.write(file, "content".getBytes());
        file.toFile().deleteOnExit();
        directory.toFile().deleteOnExit();
        return file.toFile();
    }
}
