package io.github.michalmela;

import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.apache.maven.wagon.repository.Repository;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.io.ByteArrayInputStream;

import static io.github.michalmela.S3WagonErrorMappingTest.serviceException;
import static io.github.michalmela.S3WagonKeyTest.connected;
import static io.github.michalmela.S3WagonPutTest.fileContaining;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S3WagonObjectOptionsTest {

    private static final String RESOURCE = "g/a/1.0/a-1.0.jar";

    @Test
    void appliesStorageClassAndTagsToASinglePut() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        S3Wagon wagon = new S3Wagon(s3);
        wagon.setStorageClass("STANDARD_IA");
        wagon.setObjectTags("team=platform&tier=build");
        wagon.connect(new Repository("test", "s3p://bucket/releases/"));

        wagon.put(fileContaining(new byte[16]), RESOURCE);

        assertEquals("STANDARD_IA", s3.lastPutRequest().storageClassAsString());
        assertEquals("team=platform&tier=build", s3.lastPutRequest().tagging());
    }

    @Test
    void appliesStorageClassTagsAndKmsToAMultipartUpload() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        S3Wagon wagon = new S3Wagon(s3);
        wagon.setMultipartThreshold("1048576");
        wagon.setMultipartPartSize("5242880");
        wagon.setStorageClass("GLACIER_IR");
        wagon.setObjectTags("tier=archive");
        wagon.setServerSideEncryption("aws:kms");
        wagon.setSseKmsKeyId("arn:aws:kms:eu-central-1:1234:key/abcd");
        wagon.connect(new Repository("test", "s3p://bucket/releases/"));

        wagon.put(fileContaining(new byte[6 * 1024 * 1024]), RESOURCE);

        assertEquals("GLACIER_IR", s3.lastCreateMultipartRequest().storageClassAsString());
        assertEquals("tier=archive", s3.lastCreateMultipartRequest().tagging());
        assertEquals(ServerSideEncryption.AWS_KMS, s3.lastCreateMultipartRequest().serverSideEncryption());
        assertEquals("arn:aws:kms:eu-central-1:1234:key/abcd", s3.lastCreateMultipartRequest().ssekmsKeyId());
    }

    /** Concurrency of 1 takes a different code path from the pooled one. */
    @Test
    void uploadsPartsSequentiallyWhenConcurrencyIsOne() throws Exception {
        byte[] payload = new byte[12 * 1024 * 1024];
        FakeS3Client s3 = new FakeS3Client();
        S3Wagon wagon = new S3Wagon(s3);
        wagon.setMultipartThreshold("1048576");
        wagon.setMultipartPartSize("5242880");
        wagon.setMultipartConcurrency("1");
        wagon.connect(new Repository("test", "s3p://bucket/releases/"));

        wagon.put(fileContaining(payload), RESOURCE);

        assertEquals(3, s3.uploadPartRequests().size());
        assertArrayEquals(payload, s3.objectContent("releases/" + RESOURCE));
    }

    @Test
    void buildsAClientWithARetryCount() {
        S3Wagon wagon = new S3Wagon();
        wagon.setRegion("eu-central-1");
        wagon.setRetries("5");

        assertEquals(5, wagon.retries());
        assertDoesNotThrow(() -> {
            wagon.connect(new Repository("test", "s3p://bucket/releases/"));
            wagon.disconnect();
        });
    }

    @Test
    void mapsAMissingBucketWhenUploadingFromAStream() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(NoSuchBucketException.builder().message("no bucket").build());
        S3Wagon wagon = connected(s3, "s3p://bucket/releases/");

        assertThrows(ResourceDoesNotExistException.class,
                () -> wagon.putFromStream(new ByteArrayInputStream(new byte[8]), RESOURCE, 8, -1));
    }

    @Test
    void mapsForbiddenWhenUploadingFromAStream() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(serviceException(403, "AccessDenied"));
        S3Wagon wagon = connected(s3, "s3p://bucket/releases/");

        assertThrows(AuthorizationException.class,
                () -> wagon.putFromStream(new ByteArrayInputStream(new byte[8]), RESOURCE, 8, -1));
    }

    @Test
    void reportsAMissingDirectoryWhenTheBucketIsGone() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failWith(NoSuchBucketException.builder().message("no bucket").build());

        assertThrows(ResourceDoesNotExistException.class,
                () -> connected(s3, "s3p://bucket/releases/").getFileList("g/a/"));
    }

    @Test
    void namesTheProfileAndSessionTokenInDiagnostics() throws Exception {
        RecordingTransferListener listener = new RecordingTransferListener();
        S3Wagon wagon = new S3Wagon(new FakeS3Client());
        wagon.addTransferListener(listener);
        wagon.setProfile("build");
        wagon.connect(new Repository("test", "s3p://bucket/releases/"));

        assertEquals(true, String.join("\n", listener.debugMessages()).contains("credentials=profile build"));

        RecordingTransferListener second = new RecordingTransferListener();
        S3Wagon withToken = new S3Wagon(new FakeS3Client());
        withToken.addTransferListener(second);
        withToken.setSessionToken("token");
        org.apache.maven.wagon.authentication.AuthenticationInfo authentication =
                new org.apache.maven.wagon.authentication.AuthenticationInfo();
        authentication.setUserName("AKIAEXAMPLE");
        authentication.setPassword("secret");
        withToken.connect(new Repository("test", "s3p://bucket/releases/"), authentication);

        assertEquals(true, String.join("\n", second.debugMessages()).contains("with session token"));
    }
}
