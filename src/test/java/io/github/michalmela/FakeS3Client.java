package io.github.michalmela;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory {@link S3Client} that records the requests it receives.
 *
 * <p>Only the operations the wagon actually uses are implemented; every other method inherits the
 * interface default, which throws {@link UnsupportedOperationException}. That is deliberate - if
 * the wagon starts calling something new, the tests should notice.
 */
class FakeS3Client implements S3Client {

    private final Map<String, byte[]> content = new HashMap<>();
    private final Map<String, Instant> lastModified = new HashMap<>();

    private final List<GetObjectRequest> getRequests = new ArrayList<>();
    private final List<PutObjectRequest> putRequests = new ArrayList<>();
    private final List<HeadObjectRequest> headRequests = new ArrayList<>();
    private final List<ListObjectsV2Request> listRequests = new ArrayList<>();
    private final List<byte[]> putBodies = new ArrayList<>();
    private final List<String> putBodyContentTypes = new ArrayList<>();

    private RuntimeException failure;
    private boolean closed;
    private boolean failOnStreamClose;
    private ReadStyle readStyle = ReadStyle.BULK;

    /** How the fake drains an upload body, to exercise each read path of the progress stream. */
    enum ReadStyle {
        /** read(buffer, 0, length) - what the SDK normally does. */
        BULK,
        /** read() - one byte at a time. */
        SINGLE_BYTE,
        /** read(buffer, offset, length) with a non-zero offset. */
        OFFSET
    }

    void readUploadsAs(ReadStyle readStyle) {
        this.readStyle = readStyle;
    }

    /** Makes the download stream throw when it is closed, after the bytes have been read. */
    void failOnStreamClose() {
        this.failOnStreamClose = true;
    }

    /** Seeds an object so that get/head can find it. */
    void seed(String key, byte[] bytes, Instant modified) {
        content.put(key, bytes);
        lastModified.put(key, modified);
    }

    void seed(String key, String text) {
        seed(key, text.getBytes(StandardCharsets.UTF_8), Instant.EPOCH);
    }

    /** Makes every subsequent operation throw, to exercise the error paths. */
    void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    GetObjectRequest lastGetRequest() {
        return last(getRequests);
    }

    PutObjectRequest lastPutRequest() {
        return last(putRequests);
    }

    HeadObjectRequest lastHeadRequest() {
        return last(headRequests);
    }

    byte[] lastPutBody() {
        return last(putBodies);
    }

    /** The content type the wagon put on the upload body. */
    String lastPutBodyContentType() {
        return last(putBodyContentTypes);
    }

    byte[] objectContent(String key) {
        return content.get(key);
    }

    int listRequestCount() {
        return listRequests.size();
    }

    List<PutObjectRequest> putRequests() {
        return putRequests;
    }

    int putCount() {
        return putRequests.size();
    }

    boolean isClosed() {
        return closed;
    }

    private static <T> T last(List<T> requests) {
        if (requests.isEmpty()) {
            throw new AssertionError("no request was recorded");
        }
        return requests.get(requests.size() - 1);
    }

    private final java.util.concurrent.atomic.AtomicInteger rangeRequests =
            new java.util.concurrent.atomic.AtomicInteger();

    int rangeRequestCount() {
        return rangeRequests.get();
    }

    @Override
    public synchronized ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest request) {
        getRequests.add(request);
        throwIfFailing();
        byte[] bytes = content.get(request.key());
        if (bytes == null) {
            throw NoSuchKeyException.builder().message("no such key: " + request.key()).build();
        }
        if (request.range() != null) {
            rangeRequests.incrementAndGet();
            // "bytes=start-end", inclusive at both ends, as S3 defines it.
            String[] bounds = request.range().substring("bytes=".length()).split("-");
            int start = Integer.parseInt(bounds[0]);
            int end = Math.min(Integer.parseInt(bounds[1]), bytes.length - 1);
            byte[] slice = new byte[end - start + 1];
            System.arraycopy(bytes, start, slice, 0, slice.length);
            return new ResponseInputStream<>(
                    GetObjectResponse.builder().contentLength((long) slice.length).build(),
                    new ByteArrayInputStream(slice));
        }
        GetObjectResponse response = GetObjectResponse.builder()
                .contentLength((long) bytes.length)
                .lastModified(lastModified.get(request.key()))
                .build();
        InputStream body = new ByteArrayInputStream(bytes);
        if (failOnStreamClose) {
            body = new FilterInputStream(body) {
                @Override
                public void close() throws IOException {
                    throw new IOException("the connection dropped while closing");
                }
            };
        }
        return new ResponseInputStream<>(response, body);
    }

    @Override
    public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
        putRequests.add(request);
        putBodyContentTypes.add(body.contentType());
        putBodies.add(readFully(body));
        throwIfFailing();
        content.put(request.key(), putBodies.get(putBodies.size() - 1));
        lastModified.put(request.key(), Instant.EPOCH);
        return PutObjectResponse.builder().build();
    }

    @Override
    public HeadObjectResponse headObject(HeadObjectRequest request) {
        headRequests.add(request);
        throwIfFailing();
        byte[] bytes = content.get(request.key());
        if (bytes == null) {
            throw NoSuchKeyException.builder().message("no such key: " + request.key()).build();
        }
        return HeadObjectResponse.builder()
                .contentLength((long) bytes.length)
                .lastModified(lastModified.get(request.key()))
                .build();
    }

    /** Page size for listings, so the pagination loop can be exercised. */
    private int listPageSize = 1000;

    void listPageSize(int listPageSize) {
        this.listPageSize = listPageSize;
    }

    @Override
    public ListObjectsV2Response listObjectsV2(ListObjectsV2Request request) {
        listRequests.add(request);
        throwIfFailing();
        String prefix = request.prefix() == null ? "" : request.prefix();
        String delimiter = request.delimiter();

        java.util.TreeSet<String> keys = new java.util.TreeSet<>();
        for (String key : content.keySet()) {
            if (key.startsWith(prefix)) {
                keys.add(key);
            }
        }

        // Split into direct objects and rolled-up prefixes, the way S3 does with a delimiter.
        java.util.TreeSet<String> objects = new java.util.TreeSet<>();
        java.util.TreeSet<String> prefixes = new java.util.TreeSet<>();
        for (String key : keys) {
            String rest = key.substring(prefix.length());
            int boundary = delimiter == null ? -1 : rest.indexOf(delimiter);
            if (boundary < 0) {
                objects.add(key);
            } else {
                prefixes.add(prefix + rest.substring(0, boundary + delimiter.length()));
            }
        }

        List<String> ordered = new ArrayList<>(objects);
        int from = request.continuationToken() == null ? 0 : Integer.parseInt(request.continuationToken());
        int to = Math.min(from + listPageSize, ordered.size());
        boolean truncated = to < ordered.size();

        ListObjectsV2Response.Builder response = ListObjectsV2Response.builder()
                .contents(ordered.subList(from, to).stream()
                        .map(k -> S3Object.builder().key(k).size((long) content.get(k).length).build())
                        .collect(java.util.stream.Collectors.toList()))
                .isTruncated(truncated);
        if (truncated) {
            response.nextContinuationToken(String.valueOf(to));
        }
        // S3 repeats the common prefixes on every page; only the first page carries them here,
        // which is enough to prove the wagon does not lose or duplicate them.
        if (from == 0) {
            response.commonPrefixes(prefixes.stream()
                    .map(p -> CommonPrefix.builder().prefix(p).build())
                    .collect(java.util.stream.Collectors.toList()));
        }
        return response.build();
    }

    // ---- multipart ----

    private final Map<String, List<byte[]>> uploadedParts = new java.util.LinkedHashMap<>();
    private final List<CreateMultipartUploadRequest> createMultipartRequests = new ArrayList<>();
    private final List<UploadPartRequest> uploadPartRequests = new ArrayList<>();
    private boolean aborted;
    private boolean completed;
    private int failUploadPartNumber = -1;

    /** Makes the given part fail, to exercise the abort path. */
    void failUploadPart(int partNumber) {
        this.failUploadPartNumber = partNumber;
    }

    boolean isAborted() {
        return aborted;
    }

    boolean isMultipartCompleted() {
        return completed;
    }

    CreateMultipartUploadRequest lastCreateMultipartRequest() {
        return last(createMultipartRequests);
    }

    List<UploadPartRequest> uploadPartRequests() {
        return uploadPartRequests;
    }

    @Override
    public CreateMultipartUploadResponse createMultipartUpload(CreateMultipartUploadRequest request) {
        createMultipartRequests.add(request);
        throwIfFailing();
        String uploadId = "upload-" + createMultipartRequests.size();
        uploadedParts.put(uploadId, new ArrayList<>());
        return CreateMultipartUploadResponse.builder().uploadId(uploadId).build();
    }

    @Override
    public synchronized UploadPartResponse uploadPart(UploadPartRequest request, RequestBody body) {
        uploadPartRequests.add(request);
        throwIfFailing();
        byte[] part = readFully(body);
        if (request.partNumber() == failUploadPartNumber) {
            throw software.amazon.awssdk.core.exception.SdkClientException.create(
                    "part " + request.partNumber() + " failed");
        }
        // Parts can arrive out of order when uploaded concurrently, so index by part number.
        List<byte[]> parts = uploadedParts.get(request.uploadId());
        while (parts.size() < request.partNumber()) {
            parts.add(null);
        }
        parts.set(request.partNumber() - 1, part);
        return UploadPartResponse.builder().eTag("etag-" + request.partNumber()).build();
    }

    @Override
    public CompleteMultipartUploadResponse completeMultipartUpload(CompleteMultipartUploadRequest request) {
        throwIfFailing();
        ByteArrayOutputStream assembled = new ByteArrayOutputStream();
        for (CompletedPart part : request.multipartUpload().parts()) {
            byte[] bytes = uploadedParts.get(request.uploadId()).get(part.partNumber() - 1);
            assembled.write(bytes, 0, bytes.length);
        }
        content.put(request.key(), assembled.toByteArray());
        lastModified.put(request.key(), Instant.EPOCH);
        completed = true;
        return CompleteMultipartUploadResponse.builder().build();
    }

    @Override
    public AbortMultipartUploadResponse abortMultipartUpload(AbortMultipartUploadRequest request) {
        aborted = true;
        uploadedParts.remove(request.uploadId());
        return AbortMultipartUploadResponse.builder().build();
    }

    private void throwIfFailing() {
        if (failure != null) {
            throw failure;
        }
    }

    private byte[] readFully(RequestBody body) {
        try (InputStream in = body.contentStreamProvider().newStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.available();
            switch (readStyle) {
                case SINGLE_BYTE:
                    int b;
                    while ((b = in.read()) != -1) {
                        out.write(b);
                    }
                    break;
                case OFFSET:
                    byte[] offsetBuffer = new byte[8192];
                    int offsetRead;
                    while ((offsetRead = in.read(offsetBuffer, 512, 4096)) != -1) {
                        out.write(offsetBuffer, 512, offsetRead);
                    }
                    break;
                default:
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String serviceName() {
        return S3Client.SERVICE_NAME;
    }

    @Override
    public void close() {
        closed = true;
    }
}
