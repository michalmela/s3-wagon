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

That runs the linters and the unit tests. `mise run lint` on its own checks the GitHub Actions
workflows with actionlint, which CI runs too — before this they were only ever linted by hand.

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

## Releasing

Releases are driven by [release-please](https://github.com/googleapis/release-please), which reads
the conventional-commit subjects on `master`:

1. Every push to `master` updates an open **release pull request** that bumps the version in
   `pom.xml` and adds the changelog entries for what has landed.
2. **Add anything a generator cannot know to that pull request before merging it** — migration
   steps, behaviour changes worth calling out, anything a user needs in order to upgrade safely.
   The changelog in this project has always carried that kind of note, and it is the reason the
   release pull request is a review step rather than a rubber stamp.
3. Merging it tags `vX.Y.Z` and creates the GitHub release.
4. The tag triggers the release workflow, which runs the full verification, refuses to publish if
   the tag and the project version disagree, signs the artifacts and deploys them to Clojars, then
   attaches the jars and the SBOM to the GitHub release.

Tags carry no `v` prefix, matching the existing `1.0.0` and `1.0.1`.

### The first release after this branch lands

Nothing special: merge, and release-please opens a release pull request proposing 1.1.0, because the
branch carries `feat:` commits and the last released version is 1.0.1. Paste the migration notes for
this release from [UPGRADING.md](UPGRADING.md) into that pull request if you want them echoed in the
changelog, then merge it. That tags 1.1.0, publishes to Clojars, and attaches the artifacts.

The version in `pom.xml` and the two version references in `README.md` are all updated by
release-please, so none of them should be edited by hand.

To see what it would propose before merging anything:

```sh
mise run release:preview
```

Two caveats. It reads the branch from GitHub, so it can only describe work that has been pushed —
there is no previewing a local branch. And it runs the release-please CLI pinned in `mise.toml`,
while CI runs whatever the action bundles (`^17.6.0` at the time of writing): the same major
version, so representative, but not the identical build. Treat a disagreement between the two as
the action being right.

Merge this branch with a **merge commit rather than a squash**. Release-please reads the individual
commit subjects to decide the version and write the changelog; squashing collapses 48 of them into
one, and if that one is titled `chore:` it produces no release at all.

So the commit subject is not cosmetic: `feat:` and `fix:` decide both the next version number and
what appears in the changelog. A `feat!:` or a `BREAKING CHANGE:` footer drives a major bump.

Nothing publishes snapshots — `distributionManagement` declares the release repository only.

### The signing key

Releases are signed. Generating the key is a one-off:

```sh
gpg --quick-generate-key "Your Name <you@example.com>" rsa4096 sign 2y
```

GnuPG identifies that key three ways, which is worth knowing because different commands want
different ones:

| | Looks like | Where it comes from |
|---|---|---|
| Fingerprint | 40 hex characters | `gpg --fingerprint`; the unambiguous identifier |
| Key ID | the last 16 hex characters of the fingerprint | shown after `rsa4096/` in `--list-keys` |
| User ID | `Your Name <you@example.com>` | whatever you typed when generating |

Any of them works in most commands. Rather than copying hex around, put the fingerprint in a shell
variable once and use it throughout:

```sh
FPR=$(gpg --list-secret-keys --with-colons | awk -F: '/^fpr/{print $10; exit}')
echo "$FPR"
```

The private key and its passphrase then go into repository secrets. Piping avoids putting the key
in shell history:

```sh
gpg --armor --export-secret-keys "$FPR" | gh secret set GPG_PRIVATE_KEY
gh secret set GPG_PASSPHRASE                      # prompts on stdin
```

Exporting asks for the passphrase; that prompt is interactive, so run it in a terminal rather than
from a script — non-interactively it fails silently and exports nothing. Publish the public half so
signatures can be checked by anyone:

```sh
gpg --keyserver keyserver.ubuntu.com --send-keys "$FPR"
```

The `2y` sets the **key's** expiry, not the signature's. The difference matters:

* signatures made before expiry keep verifying afterwards - GnuPG still reports `Good signature`,
  just with the key marked `[expired]`, and exits 0. Artifacts already on Clojars stay verifiable.
* what breaks is making *new* signatures: gpg refuses, so a release two years from now would fail.

Expiry is a dead-man's switch rather than a deadline, and it is extendable at any time:

```sh
FPR=$(gpg --list-secret-keys --with-colons | awk -F: '/^fpr/{print $10; exit}')
gpg --quick-set-expire "$FPR" 2y
gpg --keyserver keyserver.ubuntu.com --send-keys "$FPR"        # publish the new expiry
gpg --armor --export-secret-keys "$FPR" | gh secret set GPG_PRIVATE_KEY
```

Both `--quick-set-expire` and `--export-secret-keys` unlock the key, so both prompt for the
passphrase — run them in a terminal, not from a script.

That last line is the one people forget: the secret holds a copy of the key as it was when
exported, so extending the expiry locally without re-exporting leaves CI signing with the old one.

So that nobody discovers this the hard way, the release workflow checks the key before signing: it
warns when fewer than 60 days remain and fails outright once the key has expired, naming the command
to fix it. Run it locally with `mise run release:check-key`.

Two details make this work unattended on a runner, both already configured:

* the passphrase reaches gpg through `MAVEN_GPG_PASSPHRASE` rather than a prompt
* `--pinentry-mode loopback` is pinned in the release profile, because a runner has no tty and gpg
  would otherwise wait forever for a passphrase dialog

The whole path — export an armoured key, import it into an empty keyring, sign non-interactively —
has been rehearsed against a throwaway key: six artifacts get signed, the signatures verify, and a
modified file fails verification. Before the first real release, run the Release workflow with
`dry_run` enabled: it imports the key and signs, and stops short of deploying.

## Security scanning

The build produces a CycloneDX SBOM, and CI scans it with
[OSV-Scanner](https://github.com/google/osv-scanner) on every pull request and once a week. The
weekly run matters: Renovate only opens a pull request when a fixed version exists, whereas an
advisory published against a dependency nobody has patched yet will only show up here.

Everything found is reported to the repository's Security tab. High and critical findings
(CVSS >= 7.0) fail the build; anything lower is reported without blocking, so a red build stays
worth reacting to. That threshold lives in `mise-tasks/scan/gate`.

## Reporting bugs

Include the wagon version, the Maven or Leiningen version, the repository URL shape (with the bucket
name redacted), and the output of the failing command with `-X`. The wagon logs what it resolved —
endpoint, region, prefix, credential source — at debug level, and that is usually the answer.

For security issues, see [SECURITY.md](SECURITY.md) instead.
