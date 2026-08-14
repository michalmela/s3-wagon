# Security policy

## Reporting a vulnerability

Please report vulnerabilities through
[GitHub's private vulnerability reporting](https://github.com/michalmela/s3-wagon/security/advisories/new)
rather than opening a public issue.

You should get an acknowledgement within a week. This is a hobby project maintained by one person,
so please treat that as best effort rather than a guarantee — if the issue is being actively
exploited elsewhere, say so and disclose on whatever timeline you think is right.

Useful things to include:

* the version of the wagon, and of Maven or Leiningen
* what an attacker gains, and what they need in order to get it
* a reproduction, if you have one

## Supported versions

Fixes go onto the latest released version. There are no long-term support branches.

| Version | Supported |
|---------|-----------|
| 1.1.x   | yes       |
| 1.0.x   | no        |

## Scope

This project is a transport: it moves build artifacts between a local build and an S3 bucket. The
things worth reporting are roughly:

* credentials being logged, leaked into error messages, or sent to the wrong endpoint
* an artifact being written to or read from a key other than the one requested
* transport security being weakened — certificate validation, signing, or the proxy configuration
* a dependency vulnerability that is actually reachable through this code

Out of scope: anything that requires an attacker to already control your `settings.xml`,
`~/.aws/config`, or the machine running the build. If your build environment is compromised, the
wagon is not the interesting part.
