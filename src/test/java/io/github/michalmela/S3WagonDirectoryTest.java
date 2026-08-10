package io.github.michalmela;

import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.github.michalmela.S3WagonKeyTest.connected;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3WagonDirectoryTest {

    @Test
    void advertisesDirectoryCopy() throws Exception {
        assertTrue(connected(new FakeS3Client(), "s3p://bucket/releases/").supportsDirectoryCopy());
    }

    @Test
    void uploadsAWholeTree() throws Exception {
        Path root = Files.createTempDirectory("s3-wagon-tree");
        Files.write(root.resolve("a-1.0.jar"), "jar".getBytes());
        Files.write(root.resolve("a-1.0.pom"), "pom".getBytes());
        Files.createDirectory(root.resolve("nested"));
        Files.write(root.resolve("nested").resolve("extra.txt"), "extra".getBytes());

        FakeS3Client s3 = new FakeS3Client();
        connected(s3, "s3p://bucket/releases/").putDirectory(root.toFile(), "g/a/1.0");

        List<String> keys = new ArrayList<>();
        s3.putRequests().forEach(r -> keys.add(r.key()));
        assertEquals(List.of(
                "releases/g/a/1.0/a-1.0.jar",
                "releases/g/a/1.0/a-1.0.pom",
                "releases/g/a/1.0/nested/extra.txt"), keys);
    }

    @Test
    void uploadsIntoTheRootWhenNoDirectoryIsGiven() throws Exception {
        Path root = Files.createTempDirectory("s3-wagon-tree-root");
        Files.write(root.resolve("a-1.0.jar"), "jar".getBytes());

        FakeS3Client s3 = new FakeS3Client();
        connected(s3, "s3p://bucket/releases/").putDirectory(root.toFile(), ".");

        assertEquals("releases/a-1.0.jar", s3.lastPutRequest().key());
    }

    @Test
    void rejectsSomethingThatIsNotADirectory() throws Exception {
        File file = S3WagonKeyTest.sourceFile("not a directory");

        assertThrows(ResourceDoesNotExistException.class,
                () -> connected(new FakeS3Client(), "s3p://bucket/releases/").putDirectory(file, "g/a"));
    }
}
