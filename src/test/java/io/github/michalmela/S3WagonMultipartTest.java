package io.github.michalmela;

import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.repository.Repository;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static io.github.michalmela.S3WagonPutTest.fileContaining;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A single PutObject is capped at 5GB by S3, so anything larger has to go up in parts. The
 * thresholds are lowered here so the behaviour can be tested without moving gigabytes.
 */
class S3WagonMultipartTest {

    private static final String RESOURCE = "g/a/1.0/a-1.0.jar";
    private static final String KEY = "releases/" + RESOURCE;

    @Test
    void usesASinglePutBelowTheThreshold() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        S3Wagon wagon = wagon(s3, "1000000", null);

        wagon.put(fileContaining(new byte[1024]), RESOURCE);

        assertEquals(1, s3.putCount());
        assertTrue(s3.uploadPartRequests().isEmpty(), "should not have started a multipart upload");
    }

    @Test
    void uploadsInPartsAboveTheThreshold() throws Exception {
        byte[] payload = new byte[26 * 1024 * 1024];
        new Random(11).nextBytes(payload);
        FakeS3Client s3 = new FakeS3Client();
        S3Wagon wagon = wagon(s3, "1048576", "5242880");

        wagon.put(fileContaining(payload), RESOURCE);

        assertEquals(0, s3.putCount(), "should not have used a single PutObject");
        assertTrue(s3.isMultipartCompleted());
        // 26MB in 5MB parts.
        assertEquals(6, s3.uploadPartRequests().size());
        assertArrayEquals(payload, s3.objectContent(KEY));
    }

    @Test
    void numbersPartsFromOneAndSizesThemCorrectly() throws Exception {
        byte[] payload = new byte[12 * 1024 * 1024];
        FakeS3Client s3 = new FakeS3Client();
        S3Wagon wagon = wagon(s3, "1048576", "5242880");

        wagon.put(fileContaining(payload), RESOURCE);

        // Parts are uploaded concurrently, so they are recorded in whatever order they finish.
        // Sort by part number before asserting: the numbering and the sizes are what matter here,
        // not which thread got there first.
        List<UploadPartRequest> parts = s3.uploadPartRequests().stream()
                .sorted(Comparator.comparingInt(UploadPartRequest::partNumber))
                .collect(Collectors.toList());

        assertEquals(3, parts.size());
        assertEquals(List.of(1, 2, 3),
                parts.stream().map(UploadPartRequest::partNumber).collect(Collectors.toList()));
        assertEquals(5L * 1024 * 1024, parts.get(0).contentLength());
        assertEquals(5L * 1024 * 1024, parts.get(1).contentLength());
        // The last part carries the remainder and may be smaller than the minimum.
        assertEquals(2L * 1024 * 1024, parts.get(2).contentLength());
    }

    @Test
    void reportsProgressForEveryPart() throws Exception {
        byte[] payload = new byte[12 * 1024 * 1024];
        FakeS3Client s3 = new FakeS3Client();
        RecordingTransferListener listener = new RecordingTransferListener();
        S3Wagon wagon = wagon(s3, "1048576", "5242880");
        wagon.addTransferListener(listener);

        wagon.put(fileContaining(payload), RESOURCE);

        assertEquals(payload.length, listener.bytesReported());
        assertEquals("initiated,started,progress,completed", listener.sequence());
    }

    /** The part stream has to stop exactly at the part boundary however the SDK reads it. */
    @Test
    void sendsCorrectPartsWhenReadOneByteAtATime() throws Exception {
        byte[] payload = new byte[12 * 1024 * 1024];
        new Random(3).nextBytes(payload);
        FakeS3Client s3 = new FakeS3Client();
        s3.readUploadsAs(FakeS3Client.ReadStyle.SINGLE_BYTE);
        S3Wagon wagon = wagon(s3, "1048576", "5242880");

        wagon.put(fileContaining(payload), RESOURCE);

        assertEquals(3, s3.uploadPartRequests().size());
        assertArrayEquals(payload, s3.objectContent(KEY));
    }

    @Test
    void sendsCorrectPartsWhenReadIntoAnOffsetBuffer() throws Exception {
        byte[] payload = new byte[12 * 1024 * 1024];
        new Random(4).nextBytes(payload);
        FakeS3Client s3 = new FakeS3Client();
        s3.readUploadsAs(FakeS3Client.ReadStyle.OFFSET);
        S3Wagon wagon = wagon(s3, "1048576", "5242880");

        wagon.put(fileContaining(payload), RESOURCE);

        assertArrayEquals(payload, s3.objectContent(KEY));
    }

    @Test
    void abortsTheUploadWhenAPartFails() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failUploadPart(2);
        S3Wagon wagon = wagon(s3, "1048576", "5242880");
        File source = fileContaining(new byte[12 * 1024 * 1024]);

        assertThrows(TransferFailedException.class, () -> wagon.put(source, RESOURCE));

        assertTrue(s3.isAborted(), "a failed multipart upload must be aborted");
        assertFalse(s3.isMultipartCompleted());
    }

    @Test
    void carriesEncryptionAndAclOntoTheMultipartUpload() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        S3Wagon wagon = new S3Wagon(s3);
        wagon.setMultipartThreshold("1048576");
        wagon.setServerSideEncryption("AES256");
        wagon.setCannedAcl("bucket-owner-full-control");
        wagon.connect(new Repository("test", "s3p://bucket/releases/"));

        wagon.put(jarNamed(new byte[6 * 1024 * 1024]), RESOURCE);

        assertEquals(ServerSideEncryption.AES256, s3.lastCreateMultipartRequest().serverSideEncryption());
        assertEquals(ObjectCannedACL.BUCKET_OWNER_FULL_CONTROL, s3.lastCreateMultipartRequest().acl());
        assertEquals("application/java-archive", s3.lastCreateMultipartRequest().contentType());
    }

    @Test
    void raisesThePartSizeWhenAFileWouldNeedTooManyParts() throws Exception {
        S3Wagon wagon = new S3Wagon(new FakeS3Client());

        // 10001 parts at the 5MB minimum would exceed S3's 10000 part limit.
        long huge = 10_001L * 5 * 1024 * 1024;
        assertTrue(wagon.partSizeFor(huge) > 5L * 1024 * 1024,
                "part size must grow so the upload stays within 10000 parts");
        assertTrue(huge / wagon.partSizeFor(huge) <= 10_000);
    }

    @Test
    void defaultsToFourConcurrentParts() {
        assertEquals(4, new S3Wagon(new FakeS3Client()).multipartConcurrency());
    }

    @Test
    void clampsConcurrencyToSaneBounds() {
        S3Wagon wagon = new S3Wagon(new FakeS3Client());
        wagon.setMultipartConcurrency("0");
        assertEquals(1, wagon.multipartConcurrency());
        wagon.setMultipartConcurrency("1000");
        assertEquals(16, wagon.multipartConcurrency());
    }

    /** Parts go out on several threads, so the assembled object must still be byte-identical. */
    @Test
    void assemblesPartsCorrectlyWhenUploadedInParallel() throws Exception {
        byte[] payload = new byte[26 * 1024 * 1024];
        new Random(21).nextBytes(payload);
        FakeS3Client s3 = new FakeS3Client();
        S3Wagon wagon = new S3Wagon(s3);
        wagon.setMultipartThreshold("1048576");
        wagon.setMultipartPartSize("5242880");
        wagon.setMultipartConcurrency("4");
        wagon.connect(new Repository("test", "s3p://bucket/releases/"));

        wagon.put(fileContaining(payload), RESOURCE);

        assertEquals(6, s3.uploadPartRequests().size());
        assertArrayEquals(payload, s3.objectContent(KEY));
    }

    @Test
    void reportsEveryByteWhenUploadingInParallel() throws Exception {
        byte[] payload = new byte[16 * 1024 * 1024];
        FakeS3Client s3 = new FakeS3Client();
        RecordingTransferListener listener = new RecordingTransferListener();
        S3Wagon wagon = new S3Wagon(s3);
        wagon.setMultipartThreshold("1048576");
        wagon.setMultipartPartSize("5242880");
        wagon.setMultipartConcurrency("4");
        wagon.connect(new Repository("test", "s3p://bucket/releases/"));
        wagon.addTransferListener(listener);

        wagon.put(fileContaining(payload), RESOURCE);

        assertEquals(payload.length, listener.bytesReported());
    }

    @Test
    void abortsWhenAParallelPartFails() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        s3.failUploadPart(3);
        S3Wagon wagon = new S3Wagon(s3);
        wagon.setMultipartThreshold("1048576");
        wagon.setMultipartPartSize("5242880");
        wagon.setMultipartConcurrency("4");
        wagon.connect(new Repository("test", "s3p://bucket/releases/"));
        File source = fileContaining(new byte[26 * 1024 * 1024]);

        assertThrows(TransferFailedException.class, () -> wagon.put(source, RESOURCE));

        assertTrue(s3.isAborted());
        assertFalse(s3.isMultipartCompleted());
    }

    @Test
    void rejectsMalformedSizesOnConnect() {
        S3Wagon wagon = new S3Wagon(new FakeS3Client());
        wagon.setMultipartThreshold("plenty");

        assertThrows(ConnectionException.class,
                () -> wagon.connect(new Repository("test", "s3p://bucket/releases/")));
    }

    @Test
    void defaultsToSixteenMegabyteParts() {
        assertEquals(16L * 1024 * 1024, new S3Wagon(new FakeS3Client()).multipartPartSize());
    }

    @Test
    void neverUsesPartsBelowTheS3Minimum() {
        S3Wagon wagon = new S3Wagon(new FakeS3Client());
        wagon.setMultipartPartSize("1024");

        assertEquals(5L * 1024 * 1024, wagon.multipartPartSize());
    }

    /** RequestBody derives the content type from the file name, so the name has to be realistic. */
    private static File jarNamed(byte[] content) throws Exception {
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("s3-wagon-multipart");
        java.nio.file.Path file = directory.resolve("a-1.0.jar");
        java.nio.file.Files.write(file, content);
        file.toFile().deleteOnExit();
        directory.toFile().deleteOnExit();
        return file.toFile();
    }

    private static S3Wagon wagon(FakeS3Client s3, String threshold, String partSize) throws Exception {
        S3Wagon wagon = new S3Wagon(s3);
        wagon.setMultipartThreshold(threshold);
        if (partSize != null) {
            wagon.setMultipartPartSize(partSize);
        }
        wagon.connect(new Repository("test", "s3p://bucket/releases/"));
        return wagon;
    }
}
