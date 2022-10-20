# s3-wagon

A drop-in replacement for https://github.com/s3-wagon-private/s3-wagon-private but:

 * based on newer AWS SDK and Maven APIs
 * with support for `aws sso login` credentials without additional hassle of manually exporting environment variables
 * distributed based on MIT license instead of Apache

## Usage

### Leiningen

In `project.clj`:

```clj
:plugins [[michalmela/s3-wagon "1.0.0"]]

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
        <url>http://clojars.org/repo</url>
    </pluginRepository>
</pluginRepositories>

<build>
    <extensions>
        <extension>
            <groupId>io.github.michalmela</groupId>
            <artifactId>s3-wagon</artifactId>
            <version>1.0.0</version>
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