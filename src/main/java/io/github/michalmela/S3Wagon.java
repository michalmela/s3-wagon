package io.github.michalmela;

import org.apache.maven.wagon.AbstractWagon;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.apache.maven.wagon.proxy.ProxyInfo;
import org.apache.maven.wagon.resource.Resource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sso.auth.ExpiredTokenException;

import java.io.File;
import java.io.IOException;
import java.net.URI;

import static software.amazon.awssdk.utils.StringUtils.isNotBlank;

public final class S3Wagon extends AbstractWagon {

    private S3Client s3;

    private String bucket;

    private String baseDirectory;

    /**
     * Client supplied by tests; when null a real client is built on connect.
     */
    private final S3Client injectedClient;

    public S3Wagon() {
        this(null);
    }

    S3Wagon(S3Client injectedClient) {
        this.injectedClient = injectedClient;
    }

    @Override
    protected void openConnectionInternal() {
        if (this.s3 == null) {
            this.bucket = repository.getHost();
            this.baseDirectory = baseDirectory(repository.getBasedir());
            this.s3 = injectedClient != null
                    ? injectedClient
                    : s3(authenticationInfo, awsHttpClient(getProxyInfo()));
        }
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
            this.fireGetStarted(resource, destination);
            try (ResponseInputStream<GetObjectResponse> is = s3.getObject(objectRequest)) {
                this.getTransfer(resource, destination, is);
            }
            this.fireGetCompleted(resource, destination);
        } catch (NoSuchKeyException | NoSuchBucketException e) {
            throw new ResourceDoesNotExistException(resourceName + " not found in S3", e);
        } catch (S3Exception e) {
            if (isNotFound(e)) {
                throw new ResourceDoesNotExistException(resourceName + " not found in S3", e);
            }
            throw new TransferFailedException("Transfer from S3 failed", e);
        } catch (ExpiredTokenException e) {
            throw new AuthorizationException("S3 authorization error", e);
        } catch (IOException e) {
            throw new TransferFailedException("Transfer from S3 failed", e);
        }
    }

    @Override
    public void put(File source, String destination) {
        PutObjectRequest putOb = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key(destination))
                .contentLength(source.length())
                .build();

        s3.putObject(putOb, RequestBody.fromFile(source.toPath()));
    }

    @Override
    public void closeConnection() {
        this.s3.close();
        this.s3 = null;
        this.bucket = null;
        this.baseDirectory = null;
    }

    private boolean isNewer(String resourceName, long timestamp) throws ResourceDoesNotExistException, AuthorizationException, TransferFailedException {
        try {
            HeadObjectResponse headObject = headObject(resourceName);
            return headObject.lastModified().getEpochSecond() > timestamp;
        } catch (NoSuchKeyException | NoSuchBucketException e) {
            throw new ResourceDoesNotExistException(resourceName + " not found in S3", e);
        } catch (S3Exception e) {
            if (isNotFound(e)) {
                throw new ResourceDoesNotExistException(resourceName + " not found in S3", e);
            }
            throw new TransferFailedException("Transfer from S3 failed", e);
        } catch (ExpiredTokenException e) {
            throw new AuthorizationException("S3 authorization error", e);
        } catch (AwsServiceException e) {
            throw new TransferFailedException("Transfer from S3 failed", e);
        }

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

    static boolean isNotFound(S3Exception exception) {
        return exception.statusCode() == 404;
    }

    private static S3Client s3(AuthenticationInfo authenticationInfo, SdkHttpClient httpClient) {
        S3ClientBuilder s3 = S3Client.builder().httpClient(httpClient);
        if (hasMinimumRequiredFields(authenticationInfo)) {
            s3.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(authenticationInfo.getUserName(), authenticationInfo.getPassword())));
        }
        return s3.build();
    }

    private static boolean hasMinimumRequiredFields(AuthenticationInfo authenticationInfo) {
        return authenticationInfo != null
                && isNotBlank(authenticationInfo.getUserName())
                && isNotBlank(authenticationInfo.getPassword());
    }

    private static SdkHttpClient awsHttpClient(ProxyInfo proxyInfo) {
        ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder();
        if (proxyInfo != null) {
            httpClientBuilder.proxyConfiguration(ProxyConfiguration.builder()
                    .endpoint(URI.create(proxyInfo.getHost() + ":" + proxyInfo.getPort()))
                    .ntlmDomain(proxyInfo.getNtlmDomain())
                    .ntlmWorkstation(proxyInfo.getNtlmHost())
                    .username(proxyInfo.getUserName())
                    .password(proxyInfo.getPassword())
                    .build());
        }
        return httpClientBuilder.build();
    }

    @Override
    public boolean resourceExists(String resourceName) throws TransferFailedException, AuthorizationException {
        try {
            headObject(resourceName);
            return true;
        } catch (S3Exception e) {
            if (isNotFound(e)) {
                return false;
            }
            throw e;
        }
    }

}
