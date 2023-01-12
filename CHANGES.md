# Change Log
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/)
and this project adheres to [Semantic Versioning](http://semver.org/).

## [1.0.1] - 2023-01-12

### Changed

* Updated the AWS SDK from 2.17.x to 2.19.14 to fix authentication problems resulting from the AWS CLI (around 2.9.14) changing its configuration format for `~/.aws/config` (splitting `sso` properties to a separate configuration section)
* Added the AWS SDK OIDC plugin required when using SSO with OIDC

## [1.0.0] - 2022-10-20

### Changed

* Initial version
