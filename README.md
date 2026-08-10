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

Everything is optional; leave a setting out and the wagon does not send it, which keeps the bucket's
own defaults in charge.

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

For example, to publish to a MinIO instance:

```sh
export S3WAGON_ENDPOINT=https://minio.example.com
export S3WAGON_PATH_STYLE_ACCESS=true
```

## Building

The JDK and Maven versions are pinned in `mise.toml`. With
[mise](https://mise.jdx.dev/) installed:

```sh
mise install     # once, to fetch the pinned toolchain
mise run test    # run the test suite
mise run build   # build the jar
```
