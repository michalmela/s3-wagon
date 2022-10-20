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
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;

import static software.amazon.awssdk.utils.StringUtils.isNotBlank;

public final class S3Wagon extends AbstractWagon {

    private volatile S3Client s3;

    private volatile String bucket;

    private volatile String baseDirectory;

    @Override
    protected void openConnectionInternal() {
        if (this.s3 == null) {
            this.bucket = repository.getHost();
            String basedir = repository.getBasedir();
            this.baseDirectory = basedir.startsWith("/") ? basedir.substring(1) : basedir;
            this.s3 = s3(authenticationInfo, awsHttpClient(getProxyInfo()));
        }
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
        } catch (ExpiredTokenException e) {
            throw new AuthorizationException("S3 authorization error", e);
        } catch (S3Exception | IOException e) {
            throw new TransferFailedException("Transfer from S3 failed", e);
        }
    }

    @Override
    public void put(File source, String destination) {
        PutObjectRequest putOb = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key(destination))
                .build();

        s3.putObject(putOb, RequestBody.fromBytes(getObjectFile(source)));
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
        return this.baseDirectory + resourceName;
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
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    private static byte[] getObjectFile(File file) {
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            byte[] bytesArray = new byte[(int) file.length()];
            fileInputStream.read(bytesArray);
            return bytesArray;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
