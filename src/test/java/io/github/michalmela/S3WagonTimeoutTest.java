package io.github.michalmela;

import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.repository.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static io.github.michalmela.S3WagonKeyTest.destination;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Maven configures timeouts through the Wagon interface. They used to be stored by AbstractWagon
 * and never read, so every build silently ran on the SDK defaults.
 */
class S3WagonTimeoutTest {

    private ServerSocket server;
    private final List<Socket> accepted = new ArrayList<>();
    private volatile boolean running = true;

    @AfterEach
    void stopServer() throws IOException {
        running = false;
        for (Socket socket : accepted) {
            socket.close();
        }
        if (server != null) {
            server.close();
        }
    }

    /**
     * The server accepts the connection and then says nothing, so only a read timeout can end this.
     * Without one the SDK would wait on its own default, far longer than this test allows.
     */
    @Test
    @Timeout(30)
    void appliesTheReadTimeout() throws Exception {
        int port = startSilentServer();

        S3Wagon wagon = new S3Wagon();
        wagon.setEndpoint("http://127.0.0.1:" + port);
        wagon.setPathStyleAccess(true);
        wagon.setRegion("eu-central-1");
        wagon.setReadTimeout(300);
        wagon.setTimeout(300);
        wagon.connect(new Repository("test", "s3p://bucket/releases/"));

        try {
            assertThrows(TransferFailedException.class,
                    () -> wagon.get("g/a/1.0/a-1.0.jar", destination()));
        } finally {
            wagon.disconnect();
        }
    }

    private int startSilentServer() throws IOException {
        server = new ServerSocket(0);
        Thread thread = new Thread(() -> {
            while (running) {
                try {
                    accepted.add(server.accept());
                } catch (IOException e) {
                    return;
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        return server.getLocalPort();
    }
}
