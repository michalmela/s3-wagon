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

`mise.lock` records a checksum for every pinned tool on every platform, and `mise install` refuses
to install anything that does not match. Without it mise still checksums downloads, but it fetches
the expected digest from the same server as the download, so the two agree even if that server is
lying; the lockfile is what makes the digest something this repository asserts. After changing a
version in `mise.toml`, regenerate it:

```sh
mise lock
```

Commit the result. Do not hand-edit it, and treat an unexplained checksum change in a diff the way
you would treat an unexplained change to a pinned action SHA.

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
branch carries `feat:` commits and the last released version is 1.0.2. Paste the migration notes for
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

Releases are signed, and so are commits. Generate the key with your GitHub **noreply** address as
its only identity:

```sh
gpg --quick-generate-key "Your Name <ID+username@users.noreply.github.com>" rsa4096 sign 2y
```

That address matters twice over. GitHub only marks a signature verified when the committer email
matches an identity on the key, and commits here use the noreply address — so a key carrying only a
personal address produces signatures marked Unverified. It also keeps a personal address off the
key entirely, which is worth caring about because **an identity published to a keyserver cannot be
taken back**: the SKS family, keyserver.ubuntu.com among them, is append-only. A UID can be revoked
but not removed, and revoked UIDs stay legible.

If you publish the public key at all, publish it to [keys.openpgp.org](https://keys.openpgp.org),
which only distributes an address after the owner confirms it and honours deletion afterwards.
Publishing is optional: GitHub verifies signatures from the key uploaded to your account, with no
keyserver involved.

```sh
FPR=$(gpg --list-secret-keys --with-colons "ID+username@users.noreply.github.com" \
        | awk -F: '/^fpr/{print $10; exit}')
gpg --armor --export "$FPR"                                  # paste into GitHub, Settings > SSH and GPG keys
gpg --armor --export-secret-keys "$FPR" | gh secret set GPG_PRIVATE_KEY
gh secret set GPG_PASSPHRASE                                 # prompts on stdin
git config user.signingkey "$FPR"
git config commit.gpgsign true
```

Beware `gpg --refresh-keys`: it merges whatever a keyserver holds back into your keyring, so a UID
you deleted locally reappears if it was ever published.

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

## Testing the workflows before pushing

Three levels, cheapest first:

```sh
mise run lint          # actionlint: syntax and expressions, instant
mise run ci:dryrun     # act: does every action reference resolve to something runnable?
mise run ci:local -- -j Verify   # act: actually run a job on Linux
```

The middle one exists because of a real failure: `actionlint` passed a workflow whose action
reference pointed at a repository root with no `runs:` section, and it only broke once GitHub tried
to run it. A dry run catches that in milliseconds.

Neither can reproduce everything. Pages deployment, OIDC-signed Scorecard publishing, code-scanning
uploads, release-please and the Clojars deploy all need the real GitHub. And for shell-portability
bugs a container is more decisive than act — `sh` on the runner is dash, which rejects
`set -o pipefail`, so an inline mise task without a bash shebang fails there and nowhere on macOS.

## Fuzzing

Three targets live in `src/fuzz/java`, run by
[ClusterFuzzLite](https://google.github.io/clusterfuzzlite/): briefly on pull requests that touch
`src/main`, and for ten minutes weekly. They assert properties rather than merely looking for
crashes — that a key prefix never starts with a slash and normalising it twice changes nothing,
that a proxy endpoint either parses with a host or reports why not, that no non-proxy host entry is
blank.

`S3WagonInvariantsTest` checks the same properties against randomised input on every build, so the
invariants are enforced without waiting for a scheduled fuzzing run.

To build and run them locally the way CI does:

```sh
docker build --platform linux/amd64 -t s3-wagon-cfl -f .clusterfuzzlite/Dockerfile .
docker run --rm --platform linux/amd64 s3-wagon-cfl bash -c \
  'export OUT=/out WORK=/work && mkdir -p $OUT $WORK && $SRC/build.sh \
   && cp /usr/local/bin/jazzer_driver /usr/local/bin/jazzer_agent_deploy.jar $OUT/ \
   && cd $OUT && ./BaseDirectoryFuzzer -runs=10000'
```

The image is amd64, so on Apple silicon this runs emulated and slowly. The `jazzer_driver` copy is
what the OSS-Fuzz `compile` wrapper does for you in CI; `build.sh` deliberately does not.

Between them these found three bugs the ordinary tests had missed: an `IllegalArgumentException`
escaping connect on a malformed proxy host, a base directory whose normalisation was not idempotent,
and a non-proxy host entry that trimmed away to an empty string.

## Repository settings these workflows expect

Two workflows are wired up but depend on a setting only a repository admin can flip:

* **GitHub Pages** — the Javadoc workflow builds the docs on every push to `master` and publishes
  them to Pages. Publishing is skipped, with a notice, until Pages is enabled under
  *Settings > Pages* with *Source* set to *GitHub Actions*. The workflow token cannot enable it
  itself; that needs a personal access token, which is not worth holding for a one-off click.
* **Renovate** — `renovate.json` is only read once the Renovate GitHub App is installed on the
  repository. Until then nothing opens dependency pull requests.

### Secrets the release needs

Four, all repository secrets (or scoped to a `release` environment if you want an approval gate on
publishing). `GITHUB_TOKEN` is provided automatically and needs nothing.

| Secret | What it is |
|---|---|
| `GPG_PRIVATE_KEY` | armoured export of the signing key |
| `GPG_PASSPHRASE` | that key's passphrase |
| `CLOJARS_USERNAME` | your Clojars account name |
| `CLOJARS_DEPLOY_TOKEN` | a Clojars **deploy token**, not your account password |

Clojars authenticates a deploy with the account username and a deploy token in place of the
password; a token cannot be used to log in, which is the point of it. Create one under
*Clojars > Dashboard > Deploy Tokens* and scope it as narrowly as it will go —
`io.github.michalmela/s3-wagon` covers this artifact and nothing else, where `*` would cover
everything you can publish.

```sh
gh secret set CLOJARS_USERNAME
gh secret set CLOJARS_DEPLOY_TOKEN
```

Both prompt on stdin, so neither ends up in shell history.

### Required status checks

These can only be configured once the workflows have run on `master`, because GitHub offers checks
it has seen report. So merge first, then add them.

Safe to require — every one of these runs on every pull request:

| Check | From |
|---|---|
| `Verify` | unit tests, MinIO integration tests, SpotBugs, linters |
| `Runtime on Java 8`, `11`, `17`, `21`, `25` | the shipped jar loading on each JDK |
| `Deploy and resolve on Maven 3.9.16` | a real deploy and resolve through the wagon |
| `Deploy and resolve on Maven 4.0.0-rc-6` | the same, on Maven 4 |
| `Scan dependencies` | OSV against the SBOM |

**Do not require `Fuzz changed code`.** It is path-filtered to `src/main`, `src/fuzz`,
`.clusterfuzzlite` and `pom.xml`, so it does not run on a documentation-only change — and a required
check that never reports blocks the pull request forever. The same applies to anything in
`javadoc`, `release`, `release-please` or `cflite-batch`, none of which run on pull requests at all.

`Scorecard analysis` does run on pull requests, but it grades repository practices rather than the
change, so requiring it mostly means a red mark for things a single pull request cannot fix.

OpenSSF Scorecard needs nothing: it runs on pushes to `master` and weekly, and publishes its results
for a public repository. The badge for it is deliberately absent from the README until the first run
produces data, because it would render broken before that.

## Reporting bugs

Include the wagon version, the Maven or Leiningen version, the repository URL shape (with the bucket
name redacted), and the output of the failing command with `-X`. The wagon logs what it resolved —
endpoint, region, prefix, credential source — at debug level, and that is usually the answer.

For security issues, see [SECURITY.md](SECURITY.md) instead.
