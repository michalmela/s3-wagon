import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.michalmela.S3Wagon;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.repository.Repository;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Arrays;

/**
 * Verifies that the wagon actually runs on the JDK it is handed, down to the Java 8 bytecode level
 * it ships.
 *
 * <p>The test suite cannot do this: it is built against JUnit 6, which needs Java 17. So this is a
 * plain main() with no dependencies beyond the wagon itself, run against a stub S3 served by the
 * JDK's own HTTP server. CI runs it on every JDK a user might have Maven on. It is about class
 * loading and runtime API compatibility - behaviour is covered by the MinIO integration tests.
 */
public final class Java8SmokeCheck {

    private static final byte[] PAYLOAD = "an artifact served to java 8".getBytes();

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new StubS3());
        server.start();
        try {
            S3Wagon wagon = new S3Wagon();
            wagon.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
            wagon.setPathStyleAccess(true);
            wagon.setRegion("us-east-1");

            AuthenticationInfo authentication = new AuthenticationInfo();
            authentication.setUserName("AKIAEXAMPLE");
            authentication.setPassword("secret");
            wagon.connect(new Repository("smoke", "s3p://bucket/releases/"), authentication);

            if (!wagon.resourceExists("g/a/1.0/a-1.0.jar")) {
                throw new IllegalStateException("resourceExists returned false");
            }

            File destination = File.createTempFile("java8-smoke", ".jar");
            if (!destination.delete()) {
                throw new IllegalStateException("could not clear the destination file");
            }
            wagon.get("g/a/1.0/a-1.0.jar", destination);
            byte[] downloaded = readFully(destination);
            if (!Arrays.equals(PAYLOAD, downloaded)) {
                throw new IllegalStateException("downloaded " + downloaded.length + " bytes, expected " + PAYLOAD.length);
            }

            File source = File.createTempFile("java8-upload", ".jar");
            FileOutputStream out = new FileOutputStream(source);
            out.write(PAYLOAD);
            out.close();
            wagon.put(source, "g/a/1.0/a-1.0.jar");

            wagon.disconnect();
            System.out.println("runtime smoke check passed on java " + System.getProperty("java.version"));
        } finally {
            server.stop(0);
        }
    }

    private static byte[] readFully(File file) throws IOException {
        InputStream in = new java.io.FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Just enough S3 to answer the three calls the check makes. */
    private static final class StubS3 implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            drain(exchange.getRequestBody());
            exchange.getResponseHeaders().add("ETag", "\"0123456789abcdef0123456789abcdef\"");
            if ("HEAD".equals(method)) {
                exchange.getResponseHeaders().add("Content-Length", String.valueOf(PAYLOAD.length));
                exchange.sendResponseHeaders(200, -1);
            } else if ("GET".equals(method)) {
                exchange.sendResponseHeaders(200, PAYLOAD.length);
                OutputStream body = exchange.getResponseBody();
                body.write(PAYLOAD);
                body.close();
            } else {
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        }

        private static void drain(InputStream in) throws IOException {
            byte[] buffer = new byte[8192];
            while (in.read(buffer) != -1) {
                // the stub does not inspect uploads; it only has to consume them
            }
        }
    }
}
