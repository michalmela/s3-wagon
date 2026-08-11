# Upgrading

Migration notes that a generated changelog cannot know. [CHANGES.md](CHANGES.md) records *what*
changed in each release; this records what you have to *do* about it.

## To 1.1.0, from 1.0.x

### Repository URLs without a trailing slash now resolve to different S3 keys

1.0.x built the key by joining the URL path and the resource name without a separator, so a URL like
`s3p://somebucket/releases` (no trailing slash) wrote artifacts to keys that ran the two together.
The prefix collided with the *group path*, so `com.example` ended up under
`releasescom/example/...` and `org.foo` under `releasesorg/foo/...`.

1.1.0 writes and reads `releases/com/example/...` — the correct location — and will **not** see
artifacts published by earlier versions. Repository URLs that already ended with a slash are
unaffected.

Survey what you actually have before moving anything:

```sh
aws s3 ls s3://somebucket/ | grep -v ' releases/'
```

then move each mangled prefix into place:

```sh
aws s3 mv --recursive s3://somebucket/releasescom/ s3://somebucket/releases/com/
```

Re-deploying the affected artifacts works just as well.

### Uploads now carry a CRC32 checksum trailer

The AWS SDK adds a checksum trailer (`x-amz-trailer`, `Content-Encoding: aws-chunked`) to uploads
by default from 2.30 onwards; the 2.19 SDK that 1.0.x used did not. AWS S3 accepts it, but some
S3-compatible stores reject it — and those are exactly the stores the new `endpoint` and
`pathStyleAccess` settings were added for.

If an upload that used to work starts failing against a non-AWS endpoint, restore the old wire
format:

```sh
export S3WAGON_REQUEST_CHECKSUM_CALCULATION=when_required
```
