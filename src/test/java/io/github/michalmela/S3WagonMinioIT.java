package io.github.michalmela;

import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.repository.Repository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end against a real S3 implementation.
 *
 * <p>The unit tests assert what the wagon <em>sends</em>; these assert what a server actually
 * accepts and stores. That difference is what catches wire-level problems - content encodings,
 * checksum trailers, signing - which an in-memory client can never see.
 */
@Testcontainers(disabledWithoutDocker = true)
class S3WagonMinioIT {

    private static final String BUCKET = "artifacts";
    private static final String RESOURCE = "g/a/1.0/a-1.0.jar";

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(
            System.getProperty("minio.image", "minio/minio:RELEASE.2025-09-07T16-13-09Z"))
            // MinIO refuses SSE-S3 unless a KMS key is configured.
            .withEnv("MINIO_KMS_SECRET_KEY", "s3-wagon-test:bWluaW8tdGVzdC1rZXktMzItYnl0ZXMtZXhhY3RseSE=");

    @BeforeAll
    static void createBucket() {
        try (S3Client s3 = client()) {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }

    private S3Wagon wagon;

    @BeforeEach
    void connectWagon() throws Exception {
        wagon = newWagon();
        connect(wagon);
    }

    @Test
    void roundTripsAnArtifact() throws Exception {
        byte[] payload = randomBytes(64 * 1024);
        wagon.put(fileWith(payload, "a-1.0.jar"), RESOURCE);

        File downloaded = File.createTempFile("downloaded", ".jar");
        downloaded.delete();
        wagon.get(RESOURCE, downloaded);

        assertArrayEquals(payload, Files.readAllBytes(downloaded.toPath()));
    }

    /** The prefix must land where the wagon says it does, not one character off. */
    @Test
    void storesTheArtifactUnderTheExpectedKey() throws Exception {
        wagon.put(fileWith(randomBytes(128), "a-1.0.jar"), RESOURCE);

        try (S3Client s3 = client()) {
            byte[] stored = s3.getObject(GetObjectRequest.builder()
                    .bucket(BUCKET).key("releases/" + RESOURCE).build()).readAllBytes();
            assertEquals(128, stored.length);
        }
    }

    @Test
    void reportsWhetherAResourceExists() throws Exception {
        assertFalse(wagon.resourceExists("g/a/9.9/absent.jar"));

        wagon.put(fileWith(randomBytes(64), "a-1.0.jar"), RESOURCE);

        assertTrue(wagon.resourceExists(RESOURCE));
    }

    @Test
    void reportsAMissingArtifact() throws Exception {
        File destination = File.createTempFile("missing", ".jar");
        destination.delete();

        assertThrows(ResourceDoesNotExistException.class, () -> wagon.get("g/a/9.9/absent.jar", destination));
    }

    @Test
    void listsARepositoryDirectory() throws Exception {
        wagon.put(fileWith(randomBytes(64), "a-1.0.jar"), "listing/a/1.0/a-1.0.jar");
        wagon.put(fileWith(randomBytes(64), "a-1.0.pom"), "listing/a/1.0/a-1.0.pom");
        wagon.put(fileWith(randomBytes(64), "maven-metadata.xml"), "listing/a/maven-metadata.xml");

        List<String> names = wagon.getFileList("listing/a/");

        assertEquals(List.of("1.0/", "maven-metadata.xml"), names);
    }

    @Test
    void skipsADownloadWhenTheLocalCopyIsNewer() throws Exception {
        wagon.put(fileWith(randomBytes(64), "a-1.0.jar"), RESOURCE);
        File destination = File.createTempFile("fresh", ".jar");
        destination.delete();

        long future = System.currentTimeMillis() + 3_600_000L;
        assertFalse(wagon.getIfNewer(RESOURCE, destination, future));
        assertTrue(wagon.getIfNewer(RESOURCE, destination, 1));
    }

    /**
     * Exercises the multipart path against a real server: part sizes, ordering and the completion
     * call all have to be right or the assembled object comes back wrong.
     */
    @Test
    void roundTripsALargeArtifactAsMultipart() throws Exception {
        S3Wagon multipartWagon = newWagon();
        multipartWagon.setMultipartThreshold("1048576");
        multipartWagon.setMultipartPartSize("5242880");
        connect(multipartWagon);

        byte[] payload = randomBytes(12 * 1024 * 1024);
        multipartWagon.put(fileWith(payload, "big-1.0.jar"), "g/big/1.0/big-1.0.jar");

        File downloaded = File.createTempFile("downloaded-big", ".jar");
        downloaded.delete();
        multipartWagon.get("g/big/1.0/big-1.0.jar", downloaded);

        assertArrayEquals(payload, Files.readAllBytes(downloaded.toPath()));

        // A multipart object's ETag ends with "-<part count>", which is how S3 reports that the
        // object really was assembled from parts rather than sent as one PUT.
        try (S3Client s3 = client()) {
            String eTag = s3.headObject(b -> b.bucket(BUCKET).key("releases/g/big/1.0/big-1.0.jar")).eTag();
            assertTrue(eTag.replace("\"", "").endsWith("-3"), "expected a 3-part multipart ETag, got " + eTag);
        }
        multipartWagon.disconnect();
    }

    @Test
    void appliesServerSideEncryption() throws Exception {
        S3Wagon encrypting = newWagon();
        encrypting.setServerSideEncryption("AES256");
        connect(encrypting);

        encrypting.put(fileWith(randomBytes(256), "a-1.0.jar"), "g/sse/1.0/sse-1.0.jar");

        try (S3Client s3 = client()) {
            assertEquals("AES256", s3.headObject(b -> b.bucket(BUCKET).key("releases/g/sse/1.0/sse-1.0.jar"))
                    .serverSideEncryptionAsString());
        }
        encrypting.disconnect();
    }

    /** Built but not connected: settings are only read when the connection opens. */
    private static S3Wagon newWagon() {
        S3Wagon wagon = new S3Wagon();
        wagon.setEndpoint(MINIO.getS3URL());
        wagon.setPathStyleAccess(true);
        wagon.setRegion("us-east-1");
        return wagon;
    }

    private static void connect(S3Wagon wagon) throws Exception {
        wagon.connect(new Repository("minio", repositoryUrl()), authentication());
    }

    private static org.apache.maven.wagon.authentication.AuthenticationInfo authentication() {
        org.apache.maven.wagon.authentication.AuthenticationInfo authentication =
                new org.apache.maven.wagon.authentication.AuthenticationInfo();
        authentication.setUserName(MINIO.getUserName());
        authentication.setPassword(MINIO.getPassword());
        return authentication;
    }

    private static String repositoryUrl() {
        return "s3p://" + BUCKET + "/releases/";
    }

    private static S3Client client() {
        return S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .forcePathStyle(true)
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
                .build();
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        return bytes;
    }

    private static File fileWith(byte[] content, String name) throws Exception {
        Path directory = Files.createTempDirectory("s3-wagon-it");
        Path file = directory.resolve(name);
        Files.write(file, content);
        file.toFile().deleteOnExit();
        directory.toFile().deleteOnExit();
        return file.toFile();
    }
}
