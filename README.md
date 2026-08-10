# s3-wagon

A drop-in replacement for https://github.com/s3-wagon-private/s3-wagon-private but:

 * based on newer AWS SDK and Maven APIs
 * with support for `aws sso login` credentials without additional hassle of manually exporting environment variables
 * distributed based on MIT license instead of Apache

Repository URLs use the `s3p://` scheme (`s3://` works too), as `s3p://<bucket>/<prefix>`. The
trailing slash on the prefix is optional.

## Usage

### Leiningen

In `project.clj`:

```clj
:plugins [[io.github.michalmela/s3-wagon "1.1.0"]]

;;; option 1: use default credentials provider
; cf. https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials.html#credentials-chain
:repositories [["private" {:url "s3p://mybucket/releases/" :no-auth true}]]

;;; option 2: feed credentials with GPG
:repositories [["private" {:url "s3p://somebucket/releases/" :creds :gpg}]]
; in ~/.lein/credentials.clj.gpg
; {"s3p://somebucket/releases"
;   {:username "YOUR AWS ACCESS KEY"
;    :password "YOUR AWS SECRET KEY"}}

;;; option 3: yolo
:repositories {
  "releases"  {:url           "s3p://somebucket/releases/"
               :username      "literal AWS access key or a function to retrieve it"
               :password      "literal AWS secret key or a function to retrieve it"
               :sign-releases false}
  "snapshots" {:url           "s3p://somebucket/snapshots/"
               :username      "literal AWS access key or a function to retrieve it"
               :password      "literal AWS secret key or a function to retrieve it"}}
```

### Maven

In `pom.xml`:

```xml
<pluginRepositories>
    <pluginRepository>
        <id>clojars.org</id>
        <name>Clojars Repository</name>
        <url>https://clojars.org/repo</url>
    </pluginRepository>
</pluginRepositories>

<build>
    <extensions>
        <extension>
            <groupId>io.github.michalmela</groupId>
            <artifactId>s3-wagon</artifactId>
            <version>1.1.0</version>
        </extension>
    </extensions>
</build>

<distributionManagement>
    <repository>
        <id>somebucket</id>
        <name>Some Bucket Releases</name>
        <url>s3p://somebucket/release</url>
    </repository>
    <snapshotRepository>
        <id>somebucket</id>
        <name>Some Bucket Snapshots</name>
        <url>s3p://somebucket/snapshot</url>
    </snapshotRepository>
</distributionManagement>

<repositories>
    <repository>
        <id>somebucket</id>
        <name>Some Bucket Releases</name>
        <url>s3p://somebucket/release</url>
    </repository>
</repositories>
```

In `settings.xml`: unless you use [the default credential provider chain](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials.html#credentials-chain):
```xml
<servers>
    <server>
        <id>somebucket</id>
        <username>YOUR AWS ACCESS KEY</username>
        <password>YOUR AWS SECRET KEY</password>
    </server>
</servers>
```

## Configuration

| Setting                | Meaning                                                                  | Example                        |
|------------------------|--------------------------------------------------------------------------|--------------------------------|
| `region`               | Region to use, when the default provider chain cannot work one out       | `eu-central-1`                 |
| `endpoint`             | Absolute URL of an S3-compatible store to use instead of AWS             | `https://minio.example.com`    |
| `pathStyleAccess`      | Address buckets as `endpoint/bucket` instead of `bucket.endpoint`        | `true`                         |
| `serverSideEncryption` | Server-side encryption to apply on upload                                | `AES256`, `aws:kms`            |
| `sseKmsKeyId`          | KMS key to encrypt with, when `serverSideEncryption` is `aws:kms`        | `arn:aws:kms:...:key/abcd`     |
| `cannedAcl`            | Canned ACL to apply on upload                                            | `bucket-owner-full-control`    |
| `requestChecksumCalculation` | When to add upload checksums; see [Upgrading](#upgrading)          | `when_required`                |
| `sessionToken`         | Session token, for temporary credentials                                 | `FwoGZXIvYXdzE...`             |
| `profile`              | Named profile to take credentials from, including assumed roles          | `build`                        |
| `multipartThreshold`   | Artifacts larger than this many bytes are uploaded in parts (100MB)      | `104857600`                    |
| `multipartPartSize`    | Size of each part in bytes (16MB, never below S3's 5MB minimum)          | `16777216`                     |
| `multipartConcurrency` | Parts uploaded at once (4, clamped to 1..16)                             | `8`                            |
| `storageClass`         | Storage class for uploaded objects                                       | `STANDARD_IA`                  |
| `objectTags`           | Tags to apply on upload, as a URL-encoded query string                   | `team=platform&tier=build`     |
| `retries`              | Retry attempts after the first (the SDK's default when unset)            | `5`                            |

Everything is optional; leave a setting out and the wagon does not send it, which keeps the bucket's
own defaults in charge.

Credentials are resolved in the order: a username/password in `settings.xml` (with `sessionToken` if
set), then `profile`, then
[the default provider chain](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials.html#credentials-chain) -
which is what makes `aws sso login` work without exporting anything. Connect and read timeouts come
from Maven's own wagon configuration.

Under Maven, configure them per server in `settings.xml`:

```xml
<servers>
    <server>
        <id>somebucket</id>
        <configuration>
            <serverSideEncryption>AES256</serverSideEncryption>
            <cannedAcl>bucket-owner-full-control</cannedAcl>
        </configuration>
    </server>
</servers>
```

Leiningen has no way to pass wagon configuration, so every setting also reads from a system property
and then an environment variable. Explicit configuration wins over a system property, which wins
over an environment variable:

| Setting                | System property                | Environment variable              |
|------------------------|--------------------------------|-----------------------------------|
| `region`               | `s3wagon.region`               | `S3WAGON_REGION`                  |
| `endpoint`             | `s3wagon.endpoint`             | `S3WAGON_ENDPOINT`                |
| `pathStyleAccess`      | `s3wagon.pathStyleAccess`      | `S3WAGON_PATH_STYLE_ACCESS`       |
| `serverSideEncryption` | `s3wagon.serverSideEncryption` | `S3WAGON_SERVER_SIDE_ENCRYPTION`  |
| `sseKmsKeyId`          | `s3wagon.sseKmsKeyId`          | `S3WAGON_SSE_KMS_KEY_ID`          |
| `cannedAcl`            | `s3wagon.cannedAcl`            | `S3WAGON_CANNED_ACL`              |
| `requestChecksumCalculation` | `s3wagon.requestChecksumCalculation` | `S3WAGON_REQUEST_CHECKSUM_CALCULATION` |
| `sessionToken`         | `s3wagon.sessionToken`         | `S3WAGON_SESSION_TOKEN`           |
| `profile`              | `s3wagon.profile`              | `S3WAGON_PROFILE`                 |
| `multipartThreshold`   | `s3wagon.multipartThreshold`   | `S3WAGON_MULTIPART_THRESHOLD`     |
| `multipartPartSize`    | `s3wagon.multipartPartSize`    | `S3WAGON_MULTIPART_PART_SIZE`     |
| `multipartConcurrency` | `s3wagon.multipartConcurrency` | `S3WAGON_MULTIPART_CONCURRENCY`   |
| `storageClass`         | `s3wagon.storageClass`         | `S3WAGON_STORAGE_CLASS`           |
| `objectTags`           | `s3wagon.objectTags`           | `S3WAGON_OBJECT_TAGS`             |
| `retries`              | `s3wagon.retries`              | `S3WAGON_RETRIES`                 |

For example, to publish to a MinIO instance:

```sh
export S3WAGON_ENDPOINT=https://minio.example.com
export S3WAGON_PATH_STYLE_ACCESS=true
```

## Troubleshooting

The wagon reports what it resolved at debug level, so `mvn -X` (or `lein -o ... :debug`) answers
most configuration questions directly:

```
s3-wagon: bucket=somebucket prefix=releases/ region=eu-central-1 endpoint=<aws> \
          pathStyleAccess=<default> credentials=default provider chain \
          connectTimeout=0ms readTimeout=0ms
```

Credentials themselves are never logged.

## Upgrading

### From 1.0.x, if your repository URL has no trailing slash

Before 1.1.0 the S3 key was built by concatenating the URL path and the resource name without a
separator, so a URL like `s3p://somebucket/releases` (no trailing slash) wrote artifacts to keys
such as `releasesg/a/1.0/a-1.0.jar`. 1.1.0 fixes that, which means it now reads and writes
`releases/g/a/1.0/a-1.0.jar` - and will **not** see artifacts published by earlier versions.

Note that the old keys are the prefix run together with the *group path*, so there is one mangled
prefix per leading group segment - `com.example` ended up under `releasescom/example/...`, `org.foo`
under `releasesorg/foo/...`. List what you actually have first:

```sh
aws s3 ls s3://somebucket/ | grep -v ' releases/'
```

then move each one into place:

```sh
aws s3 mv --recursive s3://somebucket/releasescom/ s3://somebucket/releases/com/
```

Re-deploying the affected artifacts works just as well. Repository URLs that already ended with a
slash are unaffected.

### Uploads carry a checksum trailer

The AWS SDK adds a CRC32 trailer (`x-amz-trailer`, `Content-Encoding: aws-chunked`) to uploads by
default, which SDK 2.19 - used by 1.0.x - did not. AWS S3 handles this fine, but some S3-compatible
stores reject it. If a previously working upload starts failing against a non-AWS endpoint, restore
the old wire format:

```sh
export S3WAGON_REQUEST_CHECKSUM_CALCULATION=when_required
```

## Building

The JDK and Maven versions are pinned in `mise.toml`. With
[mise](https://mise.jdx.dev/) installed:

```sh
mise install     # once, to fetch the pinned toolchain
mise run test    # unit tests
mise run verify  # unit tests, integration tests and static analysis
mise run build   # build the jar
```

`mise run verify` also runs the integration tests, which drive the wagon against a real MinIO server
through [Testcontainers](https://testcontainers.com/). They are skipped when Docker is unavailable.

Two further checks run in CI and can be run locally:

```sh
mise run verify:runtime --java zulu-8.96.0.19   # load the built jar on a given JDK
mise run verify:maven --maven 4.0.0-rc-6        # deploy and resolve through real Maven
```

The published jar is Java 8 bytecode; CI loads it on Java 8, 11, 17, 21 and 25, and runs a real
`mvn deploy` plus resolve against MinIO on both Maven 3 and Maven 4.

To run the integration tests against real AWS S3 instead of MinIO, set `S3_WAGON_TEST_BUCKET` (and
optionally `S3_WAGON_TEST_REGION`); credentials come from the default provider chain. Those tests
are skipped when the variable is unset.
