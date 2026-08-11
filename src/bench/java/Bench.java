import io.github.michalmela.S3Wagon;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.repository.Repository;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Benchmark harness driven by hyperfine.
 *
 * <p>Each mode is a single short-lived process so that hyperfine can time it. JVM startup is a
 * constant overhead across every variant, so comparisons between them remain meaningful even
 * though the absolute numbers include it.
 */
public final class Bench {

    private static final String BUCKET = "bench";

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        switch (mode) {
            case "upload":
                upload(args[1], args[2]);
                break;
            case "make-payload":
                makePayload(args[1], Integer.parseInt(args[2]));
                break;
            case "get-to-file":
                getToFile(Integer.parseInt(args[1]));
                break;
            case "get-to-stream":
                getToStream(Integer.parseInt(args[1]));
                break;
            case "seed":
                seed();
                break;
            default:
                throw new IllegalArgumentException("unknown mode " + mode);
        }
    }

    /** Writes the payload once, outside any timed run. */
    private static void makePayload(String path, int megabytes) throws Exception {
        byte[] payload = new byte[megabytes * 1024 * 1024];
        new Random(megabytes).nextBytes(payload);
        Files.write(Path.of(path), payload);
        System.out.println("wrote " + megabytes + "MB to " + path);
    }

    /**
     * Uploads one large artifact at a given multipart concurrency.
     *
     * <p>The payload is built beforehand: generating it here would dominate the measurement and
     * hide the very thing being measured.
     */
    private static void upload(String path, String concurrency) throws Exception {
        File file = new File(path);

        S3Wagon wagon = wagon();
        wagon.setMultipartThreshold("1048576");
        wagon.setMultipartPartSize("5242880");
        wagon.setMultipartConcurrency(concurrency);
        connect(wagon);
        wagon.put(file, "g/big/1.0/big-1.0.jar");
        wagon.disconnect();
    }

    /** The path the resolver takes when a wagon is not a StreamingWagon: via a temporary file. */
    private static void getToFile(int iterations) throws Exception {
        S3Wagon wagon = wagon();
        connect(wagon);
        for (int i = 0; i < iterations; i++) {
            File destination = File.createTempFile("bench-get", ".sha1");
            if (!destination.delete()) {
                throw new IllegalStateException("could not clear the destination");
            }
            wagon.get("g/a/1.0/a-1.0.jar.sha1", destination);
            if (!destination.delete()) {
                throw new IllegalStateException("could not clean up");
            }
        }
        wagon.disconnect();
    }

    /** The path StreamingWagon unlocks: straight into memory, no file involved. */
    private static void getToStream(int iterations) throws Exception {
        S3Wagon wagon = wagon();
        connect(wagon);
        for (int i = 0; i < iterations; i++) {
            ByteArrayOutputStream destination = new ByteArrayOutputStream();
            wagon.getToStream("g/a/1.0/a-1.0.jar.sha1", destination);
        }
        wagon.disconnect();
    }

    private static void seed() throws Exception {
        S3Wagon wagon = wagon();
        connect(wagon);
        Path directory = Files.createTempDirectory("bench-seed");
        Path file = directory.resolve("a-1.0.jar.sha1");
        Files.write(file, "0123456789abcdef0123456789abcdef01234567".getBytes());
        wagon.put(file.toFile(), "g/a/1.0/a-1.0.jar.sha1");
        wagon.disconnect();
        System.out.println("seeded");
    }

    private static S3Wagon wagon() {
        S3Wagon wagon = new S3Wagon();
        wagon.setEndpoint(System.getenv("BENCH_ENDPOINT"));
        wagon.setPathStyleAccess(true);
        wagon.setRegion("us-east-1");
        return wagon;
    }

    private static void connect(S3Wagon wagon) throws Exception {
        AuthenticationInfo authentication = new AuthenticationInfo();
        authentication.setUserName("minioadmin");
        authentication.setPassword("minioadmin");
        wagon.connect(new Repository("bench", "s3p://" + BUCKET + "/releases/"), authentication);
    }
}
