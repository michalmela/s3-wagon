package io.github.michalmela;

import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.repository.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.util.HashMap;
import java.util.Map;

import static io.github.michalmela.S3WagonPutTest.fileContaining;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Maven injects these from a {@code <server><configuration>} block; Leiningen cannot pass wagon
 * configuration at all, so each setting also falls back to a system property and an environment
 * variable.
 */
class S3WagonSettingsTest {

    private static final String RESOURCE = "g/a/1.0/a-1.0.jar";

    private final Map<String, String> environment = new HashMap<>();

    @AfterEach
    void clearSystemProperties() {
        System.clearProperty("s3wagon.region");
        System.clearProperty("s3wagon.endpoint");
        System.clearProperty("s3wagon.pathStyleAccess");
        System.clearProperty("s3wagon.serverSideEncryption");
        System.clearProperty("s3wagon.sseKmsKeyId");
        System.clearProperty("s3wagon.cannedAcl");
    }

    @Test
    void appliesServerSideEncryption() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        S3Wagon wagon = wagon(s3);
        wagon.setServerSideEncryption("AES256");
        connect(wagon);

        wagon.put(fileContaining(new byte[8]), RESOURCE);

        assertEquals(ServerSideEncryption.AES256, s3.lastPutRequest().serverSideEncryption());
    }

    @Test
    void appliesKmsEncryptionWithItsKey() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        S3Wagon wagon = wagon(s3);
        wagon.setServerSideEncryption("aws:kms");
        wagon.setSseKmsKeyId("arn:aws:kms:eu-central-1:1234:key/abcd");
        connect(wagon);

        wagon.put(fileContaining(new byte[8]), RESOURCE);

        PutObjectRequest request = s3.lastPutRequest();
        assertEquals(ServerSideEncryption.AWS_KMS, request.serverSideEncryption());
        assertEquals("arn:aws:kms:eu-central-1:1234:key/abcd", request.ssekmsKeyId());
    }

    @Test
    void appliesCannedAcl() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        S3Wagon wagon = wagon(s3);
        wagon.setCannedAcl("bucket-owner-full-control");
        connect(wagon);

        wagon.put(fileContaining(new byte[8]), RESOURCE);

        assertEquals(ObjectCannedACL.BUCKET_OWNER_FULL_CONTROL, s3.lastPutRequest().acl());
    }

    @Test
    void leavesEncryptionAndAclUnsetByDefault() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        S3Wagon wagon = wagon(s3);
        connect(wagon);

        wagon.put(fileContaining(new byte[8]), RESOURCE);

        assertNull(s3.lastPutRequest().serverSideEncryption());
        assertNull(s3.lastPutRequest().acl());
    }

    @Test
    void rejectsAnUnknownEncryptionOnConnect() {
        S3Wagon wagon = wagon(new FakeS3Client());
        wagon.setServerSideEncryption("AES512");

        ConnectionException thrown = assertThrows(ConnectionException.class, () -> connect(wagon));
        assertTrue(thrown.getMessage().contains("AES512"), thrown.getMessage());
    }

    @Test
    void rejectsAnUnknownAclOnConnect() {
        S3Wagon wagon = wagon(new FakeS3Client());
        wagon.setCannedAcl("everyone-please");

        ConnectionException thrown = assertThrows(ConnectionException.class, () -> connect(wagon));
        assertTrue(thrown.getMessage().contains("everyone-please"), thrown.getMessage());
    }

    @Test
    void rejectsAnEndpointThatIsNotAnAbsoluteUrl() {
        S3Wagon wagon = new S3Wagon();
        wagon.setEndpoint("minio.example.com:9000");

        ConnectionException thrown = assertThrows(ConnectionException.class, () -> connect(wagon));
        assertTrue(thrown.getMessage().contains("absolute URL"), thrown.getMessage());
    }

    @Test
    void readsSettingsFromSystemProperties() {
        System.setProperty("s3wagon.endpoint", "https://minio.example.com");
        System.setProperty("s3wagon.pathStyleAccess", "true");
        System.setProperty("s3wagon.cannedAcl", "private");

        S3Wagon wagon = wagon(new FakeS3Client());

        assertEquals("https://minio.example.com", wagon.endpoint());
        assertEquals(Boolean.TRUE, wagon.pathStyleAccess());
        assertEquals("private", wagon.cannedAcl());
    }

    @Test
    void readsSettingsFromTheEnvironment() {
        environment.put("S3WAGON_ENDPOINT", "https://minio.example.com");
        environment.put("S3WAGON_PATH_STYLE_ACCESS", "true");
        environment.put("S3WAGON_SERVER_SIDE_ENCRYPTION", "AES256");
        environment.put("S3WAGON_REGION", "eu-central-1");

        S3Wagon wagon = wagon(new FakeS3Client());

        assertEquals("https://minio.example.com", wagon.endpoint());
        assertEquals(Boolean.TRUE, wagon.pathStyleAccess());
        assertEquals("AES256", wagon.serverSideEncryption());
        assertEquals("eu-central-1", wagon.region());
    }

    @Test
    void prefersExplicitConfigurationOverSystemPropertyAndEnvironment() {
        System.setProperty("s3wagon.endpoint", "https://from-property.example.com");
        environment.put("S3WAGON_ENDPOINT", "https://from-environment.example.com");

        S3Wagon wagon = wagon(new FakeS3Client());
        wagon.setEndpoint("https://explicit.example.com");

        assertEquals("https://explicit.example.com", wagon.endpoint());
    }

    @Test
    void prefersSystemPropertyOverEnvironment() {
        System.setProperty("s3wagon.region", "eu-west-1");
        environment.put("S3WAGON_REGION", "eu-central-1");

        assertEquals("eu-west-1", wagon(new FakeS3Client()).region());
    }

    @Test
    void hasNoSettingsByDefault() {
        S3Wagon wagon = wagon(new FakeS3Client());

        assertNull(wagon.endpoint());
        assertNull(wagon.region());
        assertNull(wagon.cannedAcl());
        assertNull(wagon.serverSideEncryption());
        assertNull(wagon.sseKmsKeyId());
        assertNull(wagon.pathStyleAccess());
    }

    /**
     * Builds a real S3 client rather than the in-memory one, which is what proves the endpoint and
     * path-style settings actually reach the SDK - and that excluding the default HTTP clients from
     * the dependency tree left a usable one behind. Building a client performs no network calls.
     */
    @Test
    void buildsARealClientAgainstACustomEndpoint() throws Exception {
        S3Wagon wagon = new S3Wagon();
        wagon.setEndpoint("https://minio.example.com:9000");
        wagon.setPathStyleAccess(true);
        wagon.setRegion("eu-central-1");

        connect(wagon);

        wagon.disconnect();
    }

    private S3Wagon wagon(FakeS3Client s3) {
        S3Wagon wagon = new S3Wagon(s3);
        wagon.setEnvironment(environment::get);
        return wagon;
    }

    private static void connect(S3Wagon wagon) throws Exception {
        wagon.connect(new Repository("test", "s3p://bucket/releases/"));
    }
}
