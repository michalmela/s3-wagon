package io.github.michalmela;

import org.apache.maven.wagon.repository.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Repository URLs are handed to the wagon exactly as the user wrote them, so every shape of base
 * directory has to produce a usable S3 key.
 */
class S3WagonKeyTest {

    private static final String RESOURCE = "g/a/1.0/a-1.0.jar";

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            // The trailing slash is the shape the Leiningen examples use.
            "s3p://bucket/releases/, releases/g/a/1.0/a-1.0.jar",
            // No trailing slash: the shape the Maven examples use. Before the fix this produced
            // "releaseg/a/1.0/a-1.0.jar", quietly writing every artifact to a mangled prefix.
            "s3p://bucket/releases,  releases/g/a/1.0/a-1.0.jar",
            "s3p://bucket/,          g/a/1.0/a-1.0.jar",
            "s3p://bucket,           g/a/1.0/a-1.0.jar",
            "s3p://bucket/a/b/,      a/b/g/a/1.0/a-1.0.jar",
            "s3p://bucket/a/b,       a/b/g/a/1.0/a-1.0.jar",
    })
    void buildsKeyFromRepositoryUrl(String url, String expectedKey) throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.seed(expectedKey, "artifact");
        S3Wagon wagon = connected(s3, url);

        wagon.get(RESOURCE, destination());

        assertEquals(expectedKey, s3.lastGetRequest().key());
        assertEquals("bucket", s3.lastGetRequest().bucket());
    }

    @Test
    void usesTheSameKeyForUploads() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        S3Wagon wagon = connected(s3, "s3p://bucket/releases");

        wagon.put(sourceFile("artifact"), RESOURCE);

        assertEquals("releases/" + RESOURCE, s3.lastPutRequest().key());
    }

    @Test
    void toleratesAbsoluteResourceNames() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        S3Wagon wagon = connected(s3, "s3p://bucket/releases");

        wagon.put(sourceFile("artifact"), "/" + RESOURCE);

        assertEquals("releases/" + RESOURCE, s3.lastPutRequest().key());
    }

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource({
            "'/releases/', 'releases/'",
            "'/releases',  'releases/'",
            "'releases',   'releases/'",
            "'/',          ''",
            "'',           ''",
    })
    void normalisesBaseDirectory(String basedir, String expected) {
        assertEquals(expected, S3Wagon.baseDirectory(basedir));
    }

    @Test
    void treatsMissingBaseDirectoryAsBucketRoot() {
        assertEquals("", S3Wagon.baseDirectory(null));
    }

    static S3Wagon connected(FakeS3Client s3, String url) throws Exception {
        S3Wagon wagon = new S3Wagon(s3);
        wagon.connect(new Repository("test", url));
        return wagon;
    }

    static File destination() throws Exception {
        Path file = Files.createTempFile("s3-wagon", ".jar");
        Files.delete(file);
        file.toFile().deleteOnExit();
        return file.toFile();
    }

    static File sourceFile(String content) throws Exception {
        Path file = Files.createTempFile("s3-wagon-source", ".jar");
        Files.write(file, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        file.toFile().deleteOnExit();
        return file.toFile();
    }
}
