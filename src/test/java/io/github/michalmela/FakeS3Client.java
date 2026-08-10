package io.github.michalmela;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayInputStream;
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
final class FakeS3Client implements S3Client {

    private final Map<String, byte[]> content = new HashMap<>();
    private final Map<String, Instant> lastModified = new HashMap<>();

    private final List<GetObjectRequest> getRequests = new ArrayList<>();
    private final List<PutObjectRequest> putRequests = new ArrayList<>();
    private final List<HeadObjectRequest> headRequests = new ArrayList<>();
    private final List<byte[]> putBodies = new ArrayList<>();

    private RuntimeException failure;
    private boolean closed;

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

    @Override
    public ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest request) {
        getRequests.add(request);
        throwIfFailing();
        byte[] bytes = content.get(request.key());
        if (bytes == null) {
            throw NoSuchKeyException.builder().message("no such key: " + request.key()).build();
        }
        GetObjectResponse response = GetObjectResponse.builder()
                .contentLength((long) bytes.length)
                .lastModified(lastModified.get(request.key()))
                .build();
        return new ResponseInputStream<>(response, new ByteArrayInputStream(bytes));
    }

    @Override
    public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
        putRequests.add(request);
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

    private void throwIfFailing() {
        if (failure != null) {
            throw failure;
        }
    }

    private static byte[] readFully(RequestBody body) {
        try (InputStream in = body.contentStreamProvider().newStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
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
