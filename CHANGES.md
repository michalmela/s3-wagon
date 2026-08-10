# Change Log
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/)
and this project adheres to [Semantic Versioning](http://semver.org/).

## [1.1.0] - 2026-08-11

### Added

* Server-side encryption on upload (`serverSideEncryption`, `sseKmsKeyId`)
* Canned ACLs on upload (`cannedAcl`), for cross-account buckets
* Custom endpoints (`endpoint`) and path-style access (`pathStyleAccess`), so the wagon works
  against S3-compatible stores such as MinIO
* An explicit `region` setting, for when the default provider chain cannot work one out
* A `requestChecksumCalculation` setting, to opt out of the upload checksum trailer the SDK adds by
  default and which some S3-compatible stores reject
* Every setting can also come from a system property or an environment variable, since Leiningen
  cannot pass wagon configuration
* Multipart upload for large artifacts (`multipartThreshold`, `multipartPartSize`), lifting the 5GB
  ceiling a single PutObject imposes
* `getFileList`, so tooling that browses a repository gets a listing instead of an
  UnsupportedOperationException
* Credentials from a session token (`sessionToken`) or a named profile (`profile`), including roles
  assumed through a profile
* Transfer events for uploads, so Maven can report upload progress
* Object size and age are published to transfer listeners

### Fixed

* Repository URLs without a trailing slash produced mangled S3 keys (`releasesg/a/1.0/a.jar`),
  which silently wrote every artifact to the wrong prefix. This affected the Maven configuration
  the README itself recommended.
* Uploads were read into a byte array whose read result was discarded, so a short read produced a
  truncated, zero-padded artifact; artifacts are now streamed
* SDK failures - network errors, missing credentials, 403s - escaped as unchecked exceptions
  instead of the Wagon exception types the resolver classifies
* Disconnecting a wagon that never connected, or disconnecting twice, threw an NPE that masked the
  real failure
* `getIfNewer` compared seconds against milliseconds, so it never considered a remote artifact newer
* Every download was reported to Maven twice
* `s3://` worked under Leiningen but not under Maven
* Proxy settings were built into a URL with no scheme, so the proxy was silently ignored
* Maven's connect and read timeouts were accepted and then dropped
* Uploads were stored as `application/octet-stream` instead of the type derived from the file name

### Upgrading from 1.0.x

* **Repository URLs without a trailing slash now resolve to different S3 keys.** 1.0.x wrote them
  to a mangled prefix (`releasescom/example/...` for a `releases` prefix and a `com.example` group);
  1.1.0 writes and reads `releases/com/example/...` and will not see previously published
  artifacts. Move the objects or re-deploy - see the README. URLs that already ended with a slash
  are unaffected.
* **Uploads now carry a CRC32 checksum trailer** (`x-amz-trailer`, `Content-Encoding: aws-chunked`),
  which the SDK adds by default from 2.30 onwards. AWS S3 accepts it; some S3-compatible stores do
  not. Set `requestChecksumCalculation` to `when_required` to restore the 1.0.x wire format.

### Changed

* Updated the AWS SDK from 2.19.14 to 2.51.4
* Dropped the Jackson dependencies, which were pinned to a release candidate (2.14.0-rc2) and are
  not reachable from the dependency tree at all
* Excluded the Netty and Apache 5 HTTP clients, which the SDK pulls in transitively even though the
  wagon always builds an Apache client
* The JDK and Maven versions are pinned with mise, and CI builds on the same toolchain
* Integration tests run against a real MinIO server, and CI checks that the jar runs on Java 8, 11,
  17, 21 and 25
* SpotBugs, a CycloneDX SBOM, reproducible jars and dependency automation via Renovate

## [1.0.2] - 2026-08-11

### Fixed

* Treat generic S3 HTTP 404 responses as missing resources instead of retryable transfer failures

## [1.0.1] - 2023-01-12

### Changed

* Updated the AWS SDK from 2.17.x to 2.19.14 to fix authentication problems resulting from the AWS CLI (around 2.9.14) changing its configuration format for `~/.aws/config` (splitting `sso` properties to a separate configuration section)
* Added the AWS SDK OIDC plugin required when using SSO with OIDC

## [1.0.0] - 2022-10-20

### Changed

* Initial version
