# Benchmarks

Run them with:

```sh
mise run bench                                    # loopback
mise run bench --latency 40                       # with 40ms round-trip latency
mise run bench --size 128 --runs 20 --latency 40  # bigger, more runs
```

The task starts MinIO in Docker, optionally puts Toxiproxy in front of it to inject latency, and
uses [hyperfine](https://github.com/sharkdp/hyperfine) to time each variant. Results below are from
an Apple silicon laptop, 64MB upload, hyperfine with one warmup run.

## Method

Each measured command is a short-lived JVM, so every number includes roughly 0.4s of JVM startup.
That overhead is identical across variants, so comparisons hold even though absolute times do not
mean much on their own.

The upload payload is generated **before** timing starts. An earlier version of this benchmark built
the 64MB payload inside the timed region, which swamped the upload and made every concurrency
setting look identical — the first results it produced were meaningless.

Latency matters more than anything else here, so both numbers are given. A loopback endpoint has
essentially no round-trip time, which is not what anyone's S3 bucket looks like.

## Multipart upload concurrency

64MB artifact, 5MB parts, so 13 parts.

| Concurrency | Loopback | 40ms RTT |
|-------------|----------|----------|
| 1           | **1.94s** | 2.06s |
| 2           | 2.04s     | 1.76s |
| 4 (default) | 2.02s     | 1.55s |
| 8           | 2.04s     | **1.50s** |

On loopback, parallelism **costs** about 5%: there is no latency to hide, so the extra threads and
connections are pure overhead.

With 40ms of round-trip latency — a plausible figure for an S3 bucket that is not in the same
region as the build — concurrency 8 is **1.38x faster** than sequential, and the default of 4 gets
**1.33x**. The gain flattens between 4 and 8, which is why 4 is the default: most of the benefit,
half the connections.

**Conclusion: the parallel upload path earns its place, but only against a real endpoint.** Anyone
benchmarking this against a local MinIO should expect it to look useless.

## StreamingWagon vs the file-based path

Small object (a 40-byte checksum), fetched repeatedly, comparing `get(name, file)` — the path the
resolver takes when a wagon is *not* a `StreamingWagon` — against `getToStream(name, stream)`.

| Path | Loopback (200 fetches) | 40ms RTT (100 fetches) |
|------|------------------------|------------------------|
| To a file | 1.39s | 3.78s |
| To a stream | **1.26s** | **3.73s** |
| | 1.10x faster | 1.01x faster |

On loopback, skipping the temporary file is worth about **10%** across many small transfers. With
realistic latency it disappears into the noise — the network dominates, and the file I/O it avoids
was never the bottleneck.

**Conclusion: `StreamingWagon` is worth having, but it is not a throughput win.** It removes I/O
churn — a temporary file created, written, read and deleted for every checksum — and it is the
interface the resolver looks for. Against a remote bucket it is free rather than fast.

This contradicts an earlier claim in this project's own history that `StreamingWagon` was the
biggest performance item outstanding. Measured, it is the smaller of the two: parallel multipart is
what actually moves the needle, and only under latency.

## What is not measured

* Real AWS S3, as opposed to MinIO behind a latency proxy. Toxiproxy models round-trip delay, not
  bandwidth limits, request throttling or S3's own per-request behaviour.
* Throughput under concurrent *builds* hitting the same bucket.
* Download parallelism, which does not exist — only uploads are parallelised.

Benchmarks are deliberately not part of CI. Shared runners are too noisy for the differences here to
mean anything.
