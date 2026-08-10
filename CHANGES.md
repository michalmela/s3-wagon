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
* Every setting can also come from a system property or an environment variable, since Leiningen
  cannot pass wagon configuration
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

### Changed

* Updated the AWS SDK from 2.19.14 to 2.51.4
* Dropped the Jackson dependencies, which were pinned to a release candidate (2.14.0-rc2) and are
  not reachable from the dependency tree at all
* Excluded the Netty and Apache 5 HTTP clients, which the SDK pulls in transitively even though the
  wagon always builds an Apache client
* The JDK and Maven versions are pinned with mise, and CI builds on the same toolchain

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
