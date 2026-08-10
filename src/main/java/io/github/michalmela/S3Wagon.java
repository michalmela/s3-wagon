package io.github.michalmela;

import org.apache.maven.wagon.AbstractWagon;
import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.proxy.ProxyInfo;
import org.apache.maven.wagon.resource.Resource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sso.auth.ExpiredTokenException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.channels.Channels;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static software.amazon.awssdk.utils.StringUtils.isNotBlank;

public final class S3Wagon extends AbstractWagon {

    /** S3 accepts at most 10000 parts in a multipart upload. */
    private static final int MAX_PARTS = 10_000;

    /** Artifacts bigger than this are uploaded in parts. */
    private static final long DEFAULT_MULTIPART_THRESHOLD = 100L * 1024 * 1024;

    private static final long DEFAULT_MULTIPART_PART_SIZE = 16L * 1024 * 1024;

    /** S3 rejects parts smaller than 5MB, except for the last one. */
    private static final long MINIMUM_PART_SIZE = 5L * 1024 * 1024;

    private S3Client s3;

    private String bucket;

    private String baseDirectory;

    /**
     * Client supplied by tests; when null a real client is built on connect.
     */
    private final S3Client injectedClient;

    // Settings, injected by Plexus from a <server><configuration> block in settings.xml. Each one
    // also falls back to a system property and then an environment variable, because Leiningen has
    // no way to pass wagon configuration through.
    private String region;
    private String endpoint;
    private Boolean pathStyleAccess;
    private String serverSideEncryption;
    private String sseKmsKeyId;
    private String cannedAcl;
    private String requestChecksumCalculation;
    private String multipartThreshold;
    private String multipartPartSize;
    private String sessionToken;
    private String profile;

    /** Overridden by tests; environment variables cannot be set in-process. */
    private Function<String, String> environment = System::getenv;

    private ServerSideEncryption encryption;
    private ObjectCannedACL acl;
    private RequestChecksumCalculation checksumCalculation;

    public S3Wagon() {
        this(null);
    }

    S3Wagon(S3Client injectedClient) {
        this.injectedClient = injectedClient;
    }

    @Override
    protected void openConnectionInternal() throws ConnectionException {
        if (this.s3 == null) {
            this.bucket = repository.getHost();
            this.baseDirectory = baseDirectory(repository.getBasedir());
            // Resolved once per connection so that a misspelled value fails immediately, with a
            // message naming the setting, rather than part-way through a deploy.
            this.encryption = encryption(serverSideEncryption());
            this.acl = cannedAcl(cannedAcl());
            this.checksumCalculation = checksumCalculation(requestChecksumCalculation());
            validateSizes();
            this.s3 = injectedClient != null
                    ? injectedClient
                    : s3(authenticationInfo, awsHttpClient(getProxyInfo()));
        }
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public void setPathStyleAccess(boolean pathStyleAccess) {
        this.pathStyleAccess = pathStyleAccess;
    }

    public void setServerSideEncryption(String serverSideEncryption) {
        this.serverSideEncryption = serverSideEncryption;
    }

    public void setSseKmsKeyId(String sseKmsKeyId) {
        this.sseKmsKeyId = sseKmsKeyId;
    }

    public void setCannedAcl(String cannedAcl) {
        this.cannedAcl = cannedAcl;
    }

    public void setRequestChecksumCalculation(String requestChecksumCalculation) {
        this.requestChecksumCalculation = requestChecksumCalculation;
    }

    public void setMultipartThreshold(String multipartThreshold) {
        this.multipartThreshold = multipartThreshold;
    }

    public void setMultipartPartSize(String multipartPartSize) {
        this.multipartPartSize = multipartPartSize;
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    void setEnvironment(Function<String, String> environment) {
        this.environment = environment;
    }

    String region() {
        return setting(region, "s3wagon.region", "S3WAGON_REGION");
    }

    String endpoint() {
        return setting(endpoint, "s3wagon.endpoint", "S3WAGON_ENDPOINT");
    }

    String serverSideEncryption() {
        return setting(serverSideEncryption, "s3wagon.serverSideEncryption", "S3WAGON_SERVER_SIDE_ENCRYPTION");
    }

    String sseKmsKeyId() {
        return setting(sseKmsKeyId, "s3wagon.sseKmsKeyId", "S3WAGON_SSE_KMS_KEY_ID");
    }

    String cannedAcl() {
        return setting(cannedAcl, "s3wagon.cannedAcl", "S3WAGON_CANNED_ACL");
    }

    String requestChecksumCalculation() {
        return setting(requestChecksumCalculation,
                "s3wagon.requestChecksumCalculation", "S3WAGON_REQUEST_CHECKSUM_CALCULATION");
    }

    String sessionToken() {
        return setting(sessionToken, "s3wagon.sessionToken", "S3WAGON_SESSION_TOKEN");
    }

    String profile() {
        return setting(profile, "s3wagon.profile", "S3WAGON_PROFILE");
    }

    long multipartThreshold() {
        return bytes(setting(multipartThreshold, "s3wagon.multipartThreshold", "S3WAGON_MULTIPART_THRESHOLD"),
                DEFAULT_MULTIPART_THRESHOLD);
    }

    long multipartPartSize() {
        return Math.max(MINIMUM_PART_SIZE,
                bytes(setting(multipartPartSize, "s3wagon.multipartPartSize", "S3WAGON_MULTIPART_PART_SIZE"),
                        DEFAULT_MULTIPART_PART_SIZE));
    }

    private static long bytes(String value, long fallback) {
        return value == null ? fallback : Long.parseLong(value.trim());
    }

    /** Parsed up front so a malformed size fails on connect rather than on the first big deploy. */
    private void validateSizes() throws ConnectionException {
        try {
            multipartThreshold();
            multipartPartSize();
        } catch (NumberFormatException e) {
            throw new ConnectionException("multipartThreshold and multipartPartSize must be a number of bytes", e);
        }
    }

    /**
     * Since 2.30 the SDK adds a CRC32 trailer to uploads by default, which some S3-compatible
     * stores reject. {@code when_required} restores the wire format older versions used.
     */
    private static RequestChecksumCalculation checksumCalculation(String value) throws ConnectionException {
        if (value == null) {
            return null;
        }
        try {
            return RequestChecksumCalculation.fromValue(value);
        } catch (IllegalArgumentException e) {
            throw new ConnectionException("unknown requestChecksumCalculation \"" + value
                    + "\"; expected when_supported or when_required", e);
        }
    }

    Boolean pathStyleAccess() {
        if (pathStyleAccess != null) {
            return pathStyleAccess;
        }
        String configured = setting(null, "s3wagon.pathStyleAccess", "S3WAGON_PATH_STYLE_ACCESS");
        return configured == null ? null : Boolean.valueOf(configured);
    }

    /**
     * Explicit configuration wins, then a system property, then an environment variable.
     */
    private String setting(String explicit, String property, String variable) {
        if (isNotBlank(explicit)) {
            return explicit;
        }
        String fromProperty = System.getProperty(property);
        if (isNotBlank(fromProperty)) {
            return fromProperty;
        }
        String fromEnvironment = environment.apply(variable);
        return isNotBlank(fromEnvironment) ? fromEnvironment : null;
    }

    private static ServerSideEncryption encryption(String value) throws ConnectionException {
        if (value == null) {
            return null;
        }
        ServerSideEncryption encryption = ServerSideEncryption.fromValue(value);
        if (encryption == ServerSideEncryption.UNKNOWN_TO_SDK_VERSION) {
            throw new ConnectionException("unknown serverSideEncryption \"" + value + "\"; expected one of "
                    + ServerSideEncryption.knownValues());
        }
        return encryption;
    }

    private static ObjectCannedACL cannedAcl(String value) throws ConnectionException {
        if (value == null) {
            return null;
        }
        ObjectCannedACL acl = ObjectCannedACL.fromValue(value);
        if (acl == ObjectCannedACL.UNKNOWN_TO_SDK_VERSION) {
            throw new ConnectionException("unknown cannedAcl \"" + value + "\"; expected one of "
                    + ObjectCannedACL.knownValues());
        }
        return acl;
    }

    /**
     * Turns a repository base directory into an S3 key prefix.
     *
     * <p>Repository URLs reach us exactly as the user wrote them, so the base directory may or may
     * not have a leading or trailing slash. S3 keys have no leading slash, and the prefix has to
     * end with one so that it does not run into the resource name.
     */
    static String baseDirectory(String basedir) {
        if (basedir == null) {
            return "";
        }
        String prefix = basedir.trim();
        while (prefix.startsWith("/")) {
            prefix = prefix.substring(1);
        }
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix.isEmpty() ? "" : prefix + "/";
    }

    @Override
    public boolean getIfNewer(String resourceName, File destination, long timestamp) throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
        if (timestamp == 0 || isNewer(resourceName, timestamp)) {
            get(resourceName, destination);
            return true;
        }
        return false;
    }

    @Override
    public void get(String resourceName, File destination) throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
        Resource resource = new Resource(resourceName);
        this.fireGetInitiated(resource, destination);
        try {
            GetObjectRequest objectRequest = GetObjectRequest
                    .builder()
                    .key(key(resourceName))
                    .bucket(bucket)
                    .build();
            try (ResponseInputStream<GetObjectResponse> is = s3.getObject(objectRequest)) {
                describe(resource, is.response());
                // getTransfer fires started/progress/completed itself.
                this.getTransfer(resource, destination, is);
            }
        } catch (SdkException e) {
            this.fireTransferError(resource, e, TransferEvent.REQUEST_GET);
            if (isMissing(e)) {
                throw new ResourceDoesNotExistException(resourceName + " not found in S3", e);
            }
            if (isAuthorizationFailure(e)) {
                throw new AuthorizationException("Not authorized to read " + resourceName + " from S3", e);
            }
            throw new TransferFailedException("Transfer of " + resourceName + " from S3 failed", e);
        } catch (IOException e) {
            // Only close() declares IOException here, and getTransfer closes the stream first, so
            // in practice a broken stream is reported there. This keeps the contract honest anyway.
            this.fireTransferError(resource, e, TransferEvent.REQUEST_GET);
            throw new TransferFailedException("Transfer of " + resourceName + " from S3 failed", e);
        }
    }

    @Override
    public void put(File source, String destination) throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
        Resource resource = new Resource(destination);
        resource.setContentLength(source.length());
        resource.setLastModified(source.lastModified());
        this.firePutInitiated(resource, source);
        if (!source.exists()) {
            ResourceDoesNotExistException e = new ResourceDoesNotExistException(source + " does not exist locally");
            this.fireTransferError(resource, e, TransferEvent.REQUEST_PUT);
            throw e;
        }
        try {
            this.firePutStarted(resource, source);
            if (source.length() > multipartThreshold()) {
                uploadInParts(source, destination, resource);
            } else {
                s3.putObject(putObjectRequest(source, destination), progressReporting(source, resource));
            }
            this.firePutCompleted(resource, source);
        } catch (SdkException e) {
            this.fireTransferError(resource, e, TransferEvent.REQUEST_PUT);
            if (isMissing(e)) {
                throw new ResourceDoesNotExistException("bucket " + bucket + " not found in S3", e);
            }
            if (isAuthorizationFailure(e)) {
                throw new AuthorizationException("Not authorized to write " + destination + " to S3", e);
            }
            throw new TransferFailedException("Transfer of " + destination + " to S3 failed", e);
        }
    }

    /**
     * Streams the file to S3 while reporting progress to the transfer listeners.
     *
     * <p>The SDK opens a fresh stream for every attempt, which is what makes retries safe.
     */
    private RequestBody progressReporting(File source, Resource resource) {
        return RequestBody.fromContentProvider(
                () -> new ProgressReportingInputStream(openForReading(source), resource),
                source.length(),
                // Ask the SDK what it would have derived from the file name, so wrapping the stream
                // does not downgrade every artifact to application/octet-stream.
                RequestBody.fromFile(source.toPath()).contentType());
    }

    private static InputStream openForReading(File source) {
        try {
            return new BufferedInputStream(new FileInputStream(source));
        } catch (IOException e) {
            throw SdkClientException.create("Could not read " + source, e);
        }
    }

    /**
     * Reports every byte the SDK reads to the wagon's transfer listeners, so that uploads show
     * progress the same way downloads do.
     */
    private final class ProgressReportingInputStream extends FilterInputStream {

        private final Resource resource;

        private ProgressReportingInputStream(InputStream in, Resource resource) {
            super(in);
            this.resource = resource;
        }

        @Override
        public int read() throws IOException {
            int read = super.read();
            if (read != -1) {
                report(new byte[]{(byte) read}, 1);
            }
            return read;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                report(offset == 0 ? buffer : Arrays.copyOfRange(buffer, offset, offset + read), read);
            }
            return read;
        }

        private void report(byte[] buffer, int read) {
            TransferEvent event = new TransferEvent(
                    S3Wagon.this, resource, TransferEvent.TRANSFER_PROGRESS, TransferEvent.REQUEST_PUT);
            fireTransferProgress(event, buffer, read);
        }
    }

    /**
     * Copies the object metadata onto the resource, so that transfer listeners can report the size
     * of a download and callers can see how old it is. Without this the listeners are handed an
     * unknown content length and progress output is degraded.
     */
    private static void describe(Resource resource, GetObjectResponse response) {
        if (response.contentLength() != null) {
            resource.setContentLength(response.contentLength());
        }
        if (response.lastModified() != null) {
            resource.setLastModified(response.lastModified().toEpochMilli());
        }
    }

    /**
     * Uploads a large artifact as a multipart upload.
     *
     * <p>A single PutObject is capped at 5GB by S3, so anything bigger cannot be uploaded any other
     * way. Parts are sent sequentially: a Wagon's transfer listeners are not documented as thread
     * safe, so uploading parts in parallel would need its own progress accounting.
     *
     * <p>A failed upload is aborted so the bucket is not left paying for orphaned parts.
     */
    private void uploadInParts(File source, String destination, Resource resource) {
        long length = source.length();
        long partSize = partSizeFor(length);
        String uploadId = s3.createMultipartUpload(createMultipartUploadRequest(source, destination)).uploadId();
        try {
            List<CompletedPart> parts = new ArrayList<>();
            long offset = 0;
            int partNumber = 1;
            while (offset < length) {
                long thisPart = Math.min(partSize, length - offset);
                UploadPartRequest request = UploadPartRequest.builder()
                        .bucket(bucket)
                        .key(key(destination))
                        .uploadId(uploadId)
                        .partNumber(partNumber)
                        .contentLength(thisPart)
                        .build();
                String eTag = s3.uploadPart(request, partBody(source, offset, thisPart, resource)).eTag();
                parts.add(CompletedPart.builder().partNumber(partNumber).eTag(eTag).build());
                offset += thisPart;
                partNumber++;
            }
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key(destination))
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                    .build());
        } catch (RuntimeException e) {
            abortQuietly(destination, uploadId, e);
            throw e;
        }
    }

    private void abortQuietly(String destination, String uploadId, RuntimeException failure) {
        try {
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key(destination))
                    .uploadId(uploadId)
                    .build());
        } catch (RuntimeException e) {
            // The upload already failed; losing the abort as well must not hide the real cause.
            failure.addSuppressed(e);
        }
    }

    /**
     * S3 allows at most 10000 parts, so the configured size is raised when a file would need more.
     */
    long partSizeFor(long length) {
        long partSize = multipartPartSize();
        long minimum = (length + MAX_PARTS - 1) / MAX_PARTS;
        return Math.max(partSize, minimum);
    }

    private RequestBody partBody(File source, long offset, long length, Resource resource) {
        return RequestBody.fromContentProvider(
                () -> new ProgressReportingInputStream(openPart(source, offset, length), resource),
                length,
                "application/octet-stream");
    }

    private static InputStream openPart(File source, long offset, long length) {
        try {
            RandomAccessFile file = new RandomAccessFile(source, "r");
            file.seek(offset);
            return new BoundedInputStream(new BufferedInputStream(Channels.newInputStream(file.getChannel())), length);
        } catch (IOException e) {
            throw SdkClientException.create("Could not read " + source, e);
        }
    }

    private CreateMultipartUploadRequest createMultipartUploadRequest(File source, String destination) {
        CreateMultipartUploadRequest.Builder request = CreateMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key(destination))
                .contentType(RequestBody.fromFile(source.toPath()).contentType());
        if (encryption != null) {
            request.serverSideEncryption(encryption);
        }
        String kmsKeyId = sseKmsKeyId();
        if (kmsKeyId != null) {
            request.ssekmsKeyId(kmsKeyId);
        }
        if (acl != null) {
            request.acl(acl);
        }
        return request.build();
    }

    /** Reads at most a fixed number of bytes, so one part cannot run into the next. */
    private static final class BoundedInputStream extends FilterInputStream {

        private long remaining;

        private BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = super.read();
            if (read != -1) {
                remaining--;
            }
            return read;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(super.available(), remaining);
        }
    }

    private PutObjectRequest putObjectRequest(File source, String destination) {
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key(destination))
                .contentLength(source.length());
        if (encryption != null) {
            request.serverSideEncryption(encryption);
        }
        String kmsKeyId = sseKmsKeyId();
        if (kmsKeyId != null) {
            request.ssekmsKeyId(kmsKeyId);
        }
        if (acl != null) {
            request.acl(acl);
        }
        return request.build();
    }

    @Override
    public void closeConnection() {
        if (this.s3 != null) {
            this.s3.close();
        }
        this.s3 = null;
        this.bucket = null;
        this.baseDirectory = null;
    }

    private boolean isNewer(String resourceName, long timestamp) throws ResourceDoesNotExistException, AuthorizationException, TransferFailedException {
        try {
            HeadObjectResponse headObject = headObject(resourceName);
            return headObject.lastModified().toEpochMilli() > timestamp;
        } catch (SdkException e) {
            if (isMissing(e)) {
                throw new ResourceDoesNotExistException(resourceName + " not found in S3", e);
            }
            if (isAuthorizationFailure(e)) {
                throw new AuthorizationException("Not authorized to read " + resourceName + " from S3", e);
            }
            throw new TransferFailedException("Lookup of " + resourceName + " in S3 failed", e);
        }
    }

    /**
     * Whether the failure means "this object or bucket is not there".
     *
     * <p>A HEAD response carries no body for the SDK to parse an error code out of, so a missing
     * object can surface as a plain 404 rather than as {@link NoSuchKeyException}.
     */
    private static boolean isMissing(SdkException e) {
        return e instanceof NoSuchKeyException
                || e instanceof NoSuchBucketException
                || isNotFound(e);
    }

    private static boolean isAuthorizationFailure(SdkException e) {
        return e instanceof ExpiredTokenException
                || statusCode(e) == 401
                || statusCode(e) == 403;
    }

    private static int statusCode(SdkException e) {
        return e instanceof AwsServiceException ? ((AwsServiceException) e).statusCode() : -1;
    }

    private HeadObjectResponse headObject(String resourceName) {
        HeadObjectRequest headObjectRequest = HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key(resourceName))
                .build();
        return s3.headObject(headObjectRequest);
    }

    private String key(String resourceName) {
        String resource = resourceName;
        while (resource.startsWith("/")) {
            resource = resource.substring(1);
        }
        return this.baseDirectory + resource;
    }

    /**
     * Whether the failure is a bare HTTP 404, with no error code in the body to identify it by.
     *
     * <p>Package-private so it can be asserted on directly; {@link #isMissing} is the predicate the
     * transfer paths actually use.
     */
    static boolean isNotFound(SdkException e) {
        return statusCode(e) == 404;
    }

    private S3Client s3(AuthenticationInfo authenticationInfo, SdkHttpClient httpClient) throws ConnectionException {
        S3ClientBuilder s3 = S3Client.builder().httpClient(httpClient);
        AwsCredentialsProvider credentials = credentialsProvider(authenticationInfo);
        if (credentials != null) {
            s3.credentialsProvider(credentials);
        }
        String region = region();
        if (region != null) {
            s3.region(Region.of(region));
        }
        String endpoint = endpoint();
        if (endpoint != null) {
            s3.endpointOverride(endpoint(endpoint));
        }
        Boolean pathStyleAccess = pathStyleAccess();
        if (pathStyleAccess != null) {
            s3.forcePathStyle(pathStyleAccess);
        }
        if (checksumCalculation != null) {
            s3.requestChecksumCalculation(checksumCalculation);
        }
        return s3.build();
    }

    private static URI endpoint(String endpoint) throws ConnectionException {
        URI uri = URI.create(endpoint);
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new ConnectionException("endpoint must be an absolute URL such as https://minio.example.com,"
                    + " but was \"" + endpoint + "\"");
        }
        return uri;
    }

    /**
     * Credentials from settings.xml win, then a named profile, then the SDK's default chain.
     *
     * <p>Returning null leaves the builder alone so the default chain applies - which is what makes
     * {@code aws sso login} work without exporting anything.
     */
    AwsCredentialsProvider credentialsProvider(AuthenticationInfo authenticationInfo) {
        if (hasMinimumRequiredFields(authenticationInfo)) {
            String token = sessionToken();
            AwsCredentials credentials = token != null
                    ? AwsSessionCredentials.create(
                            authenticationInfo.getUserName(), authenticationInfo.getPassword(), token)
                    : AwsBasicCredentials.create(
                            authenticationInfo.getUserName(), authenticationInfo.getPassword());
            return StaticCredentialsProvider.create(credentials);
        }
        String profile = profile();
        if (profile != null) {
            // Role assumption configured in ~/.aws/config resolves through this too, which is why
            // the STS module is on the classpath.
            return ProfileCredentialsProvider.create(profile);
        }
        return null;
    }

    private static boolean hasMinimumRequiredFields(AuthenticationInfo authenticationInfo) {
        return authenticationInfo != null
                && isNotBlank(authenticationInfo.getUserName())
                && isNotBlank(authenticationInfo.getPassword());
    }

    private SdkHttpClient awsHttpClient(ProxyInfo proxyInfo) {
        ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder();
        // Maven configures these through the Wagon interface; before this they were accepted and
        // then dropped, leaving every build on the SDK defaults.
        if (getTimeout() > 0) {
            httpClientBuilder.connectionTimeout(Duration.ofMillis(getTimeout()));
        }
        if (getReadTimeout() > 0) {
            httpClientBuilder.socketTimeout(Duration.ofMillis(getReadTimeout()));
        }
        if (proxyInfo != null && isNotBlank(proxyInfo.getHost())) {
            httpClientBuilder.proxyConfiguration(ProxyConfiguration.builder()
                    .endpoint(proxyEndpoint(proxyInfo))
                    .nonProxyHosts(nonProxyHosts(proxyInfo))
                    .ntlmDomain(proxyInfo.getNtlmDomain())
                    .ntlmWorkstation(proxyInfo.getNtlmHost())
                    .username(proxyInfo.getUserName())
                    .password(proxyInfo.getPassword())
                    .build());
        }
        return httpClientBuilder.build();
    }

    /**
     * The proxy endpoint needs a scheme: without one, {@link URI} reads "proxy.example.com:8080" as
     * a scheme of "proxy.example.com" and leaves the host null, and the proxy is quietly ignored.
     *
     * <p>Apache's proxy support speaks HTTP, so a SOCKS proxy type cannot be honoured here and is
     * treated as HTTP rather than producing a URI the SDK will reject.
     */
    static URI proxyEndpoint(ProxyInfo proxyInfo) {
        String type = proxyInfo.getType();
        String scheme = "https".equalsIgnoreCase(type) ? "https" : "http";
        return URI.create(scheme + "://" + proxyInfo.getHost() + ":" + proxyInfo.getPort());
    }

    /** Wagon separates non-proxy hosts with '|'; the SDK wants them one by one. */
    static Set<String> nonProxyHosts(ProxyInfo proxyInfo) {
        String nonProxyHosts = proxyInfo.getNonProxyHosts();
        if (!isNotBlank(nonProxyHosts)) {
            return Collections.emptySet();
        }
        Set<String> hosts = new LinkedHashSet<>();
        for (String host : nonProxyHosts.split("[|,]")) {
            if (isNotBlank(host)) {
                hosts.add(host.trim());
            }
        }
        return hosts;
    }

    /**
     * Lists the immediate children of a directory, the way the other wagons do: plain names for
     * objects, and names ending in "/" for the prefixes below it.
     *
     * <p>{@link AbstractWagon} would otherwise throw {@link UnsupportedOperationException} - an
     * unchecked exception escaping the Wagon contract - at anything that browses a repository.
     */
    @Override
    public List<String> getFileList(String destinationDirectory)
            throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
        String prefix = directoryPrefix(destinationDirectory);
        try {
            List<String> names = new ArrayList<>();
            String continuationToken = null;
            do {
                ListObjectsV2Response response = s3.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(prefix)
                        .delimiter("/")
                        .continuationToken(continuationToken)
                        .build());
                for (CommonPrefix commonPrefix : response.commonPrefixes()) {
                    names.add(relativeName(prefix, commonPrefix.prefix()));
                }
                for (S3Object object : response.contents()) {
                    // A "directory marker" object is the prefix itself; it is not a child.
                    if (!object.key().equals(prefix)) {
                        names.add(relativeName(prefix, object.key()));
                    }
                }
                continuationToken = Boolean.TRUE.equals(response.isTruncated())
                        ? response.nextContinuationToken()
                        : null;
            } while (continuationToken != null);

            if (names.isEmpty()) {
                throw new ResourceDoesNotExistException(destinationDirectory + " not found in S3");
            }
            return names;
        } catch (SdkException e) {
            if (isMissing(e)) {
                throw new ResourceDoesNotExistException(destinationDirectory + " not found in S3", e);
            }
            if (isAuthorizationFailure(e)) {
                throw new AuthorizationException("Not authorized to list " + destinationDirectory + " in S3", e);
            }
            throw new TransferFailedException("Listing of " + destinationDirectory + " in S3 failed", e);
        }
    }

    /** A listing prefix is a key that must end with "/" so the delimiter can do its job. */
    private String directoryPrefix(String destinationDirectory) {
        String prefix = key(destinationDirectory);
        return prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
    }

    private static String relativeName(String prefix, String key) {
        return key.substring(prefix.length());
    }

    @Override
    public boolean resourceExists(String resourceName) throws TransferFailedException, AuthorizationException {
        try {
            headObject(resourceName);
            return true;
        } catch (SdkException e) {
            if (isMissing(e)) {
                return false;
            }
            if (isAuthorizationFailure(e)) {
                throw new AuthorizationException("Not authorized to read " + resourceName + " from S3", e);
            }
            throw new TransferFailedException("Lookup of " + resourceName + " in S3 failed", e);
        }
    }

}
