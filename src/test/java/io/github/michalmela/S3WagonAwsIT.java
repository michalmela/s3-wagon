package io.github.michalmela;

import org.apache.maven.wagon.repository.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same round trip as {@link S3WagonMinioIT}, but against real AWS S3.
 *
 * <p>MinIO is not S3. It needed a KMS key configured before it would honour server-side encryption
 * at all, and the checksum trailer the SDK now sends is exactly the kind of thing the two can
 * disagree about. Only a real bucket settles it.
 *
 * <p>Skipped unless {@code S3_WAGON_TEST_BUCKET} is set. Credentials come from the default provider
 * chain, so an SSO session or an instance role is enough; {@code S3_WAGON_TEST_REGION} overrides the
 * region. Objects are written under a unique prefix and deleted afterwards.
 */
@EnabledIfEnvironmentVariable(named = "S3_WAGON_TEST_BUCKET", matches = ".+")
class S3WagonAwsIT {

    private static final String RESOURCE = "g/a/1.0/a-1.0.jar";

    private String prefix;
    private S3Wagon wagon;

    @BeforeEach
    void connect() throws Exception {
        prefix = "s3-wagon-it/" + UUID.randomUUID();
        wagon = new S3Wagon();
        String region = System.getenv("S3_WAGON_TEST_REGION");
        if (region != null && !region.isEmpty()) {
            wagon.setRegion(region);
        }
        wagon.connect(new Repository("aws", "s3p://" + System.getenv("S3_WAGON_TEST_BUCKET") + "/" + prefix));
    }

    @AfterEach
    void deleteEverythingWritten() throws Exception {
        wagon.disconnect();
        try (S3Client s3 = client()) {
            List<ObjectIdentifier> keys = new ArrayList<>();
            for (S3Object object : s3.listObjectsV2Paginator(ListObjectsV2Request.builder()
                    .bucket(bucket()).prefix(prefix + "/").build()).contents()) {
                keys.add(ObjectIdentifier.builder().key(object.key()).build());
            }
            if (!keys.isEmpty()) {
                s3.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(bucket()).delete(Delete.builder().objects(keys).build()).build());
            }
        }
    }

    private static String bucket() {
        return System.getenv("S3_WAGON_TEST_BUCKET");
    }

    private static S3Client client() {
        software.amazon.awssdk.services.s3.S3ClientBuilder builder = S3Client.builder();
        String region = System.getenv("S3_WAGON_TEST_REGION");
        if (region != null && !region.isEmpty()) {
            builder.region(Region.of(region));
        }
        return builder.build();
    }

    @Test
    void roundTripsAnArtifactThroughRealS3() throws Exception {
        byte[] payload = new byte[128 * 1024];
        new Random(1).nextBytes(payload);

        wagon.put(fileWith(payload), RESOURCE);
        assertTrue(wagon.resourceExists(RESOURCE));

        File downloaded = File.createTempFile("aws-it", ".jar");
        assertTrue(downloaded.delete());
        wagon.get(RESOURCE, downloaded);

        assertArrayEquals(payload, Files.readAllBytes(downloaded.toPath()));

        List<String> listing = wagon.getFileList("g/a/1.0/");
        assertEquals(List.of("a-1.0.jar"), listing);

        assertFalse(wagon.resourceExists("g/a/1.0/absent.jar"));
    }

    /**
     * The multipart path is where a real endpoint is most likely to disagree with MinIO, because
     * the checksum trailer is negotiated per part.
     */
    @Test
    void roundTripsAMultipartUploadThroughRealS3() throws Exception {
        S3Wagon multipart = new S3Wagon();
        String region = System.getenv("S3_WAGON_TEST_REGION");
        if (region != null && !region.isEmpty()) {
            multipart.setRegion(region);
        }
        multipart.setMultipartThreshold("5242880");
        multipart.setMultipartPartSize("5242880");
        multipart.connect(new Repository("aws", "s3p://" + System.getenv("S3_WAGON_TEST_BUCKET") + "/" + prefix));

        byte[] payload = new byte[12 * 1024 * 1024];
        new Random(2).nextBytes(payload);
        multipart.put(fileWith(payload), "g/big/1.0/big-1.0.jar");

        File downloaded = File.createTempFile("aws-it-big", ".jar");
        assertTrue(downloaded.delete());
        multipart.get("g/big/1.0/big-1.0.jar", downloaded);

        assertArrayEquals(payload, Files.readAllBytes(downloaded.toPath()));
        multipart.disconnect();
    }

    private static File fileWith(byte[] content) throws Exception {
        Path directory = Files.createTempDirectory("s3-wagon-aws-it");
        Path file = directory.resolve("a-1.0.jar");
        Files.write(file, content);
        file.toFile().deleteOnExit();
        directory.toFile().deleteOnExit();
        return file.toFile();
    }
}
