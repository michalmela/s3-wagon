# Contributing

Thanks for taking a look. This is a small, focused project — a Maven wagon that moves artifacts in
and out of S3 — and it intends to stay that way.

## Getting set up

The JDK and Maven versions are pinned in `mise.toml`, so [mise](https://mise.jdx.dev/) is the only
thing you need installed:

```sh
mise install
mise run test
```

`mise run verify` additionally runs the integration tests against a MinIO container and the static
analysis. It needs Docker; without it the integration tests skip rather than fail.

## Before opening a pull request

```sh
mise run pre-commit
```

If you would rather not remember, wire it into git:

```sh
mise generate git-pre-commit --write --task=pre-commit
```

For anything touching the transfer paths, also run the end-to-end check — it deploys to MinIO
through real Maven and resolves the artifact back:

```sh
mise run verify:maven
mise run verify:maven --maven 4.0.0-rc-6
```

## What a good change looks like

**Every bug fix comes with a test that fails without it.** Not a test that passes afterwards — one
you have watched fail first. Several of the bugs already fixed here were invisible in normal use
(mangled S3 keys, a proxy that was silently ignored, timeouts that were quietly dropped), and the
only thing separating a real fix from a plausible one was a test that demonstrably caught it.

Beyond that:

* Match the surrounding style. Comments explain *why*, not what the next line does.
* Keep commits small and focused, with a conventional-commit subject (`fix(keys): ...`). Say what
  was wrong and what breaks if it is wrong, not just what changed.
* The artifact targets Java 8 bytecode because it is loaded into whatever JVM is running Maven or
  Leiningen. Tests compile at a modern level and are free to use it; `src/main` is not.
* New configuration follows the existing pattern: a setter for Maven's `<configuration>`, plus a
  system property and an environment variable, because Leiningen cannot pass wagon configuration.
  Validate enum-like values on connect so a typo fails immediately.

## Testing layers

There are four, and they catch different things:

| Layer | Where | Catches |
|-------|-------|---------|
| Unit, against an in-memory S3 client | `src/test/java/**/*Test.java` | what the wagon *sends* |
| Integration, against MinIO | `S3WagonMinioIT` | what a server *accepts* |
| Integration, against real AWS | `S3WagonAwsIT` (needs `S3_WAGON_TEST_BUCKET`) | where MinIO and S3 differ |
| End-to-end, through real Maven | `mise run verify:maven` | whether the whole thing works at all |

A change to what goes over the wire deserves more than the first row. The in-memory client cannot
see content encodings, checksum trailers, or signing — regressions have hidden in exactly that gap.

## Reporting bugs

Include the wagon version, the Maven or Leiningen version, the repository URL shape (with the bucket
name redacted), and the output of the failing command with `-X`. The wagon logs what it resolved —
endpoint, region, prefix, credential source — at debug level, and that is usually the answer.

For security issues, see [SECURITY.md](SECURITY.md) instead.
