package io.github.michalmela;

import org.apache.maven.wagon.repository.Repository;
import org.junit.jupiter.api.Test;

import static io.github.michalmela.S3WagonKeyTest.connected;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3WagonConnectionTest {

    @Test
    void closesTheClientOnDisconnect() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        S3Wagon wagon = connected(s3, "s3p://bucket/releases/");

        wagon.disconnect();

        assertTrue(s3.isClosed());
    }

    /**
     * AbstractWagon.disconnect() calls closeConnection() unconditionally and catches only
     * ConnectionException, so an NPE here escapes raw. Callers disconnect from a finally block,
     * which means a wagon that never connected - or one being closed twice - used to throw an NPE
     * that masked whatever the real failure was.
     */
    @Test
    void toleratesDisconnectWithoutConnect() {
        assertDoesNotThrow(() -> new S3Wagon(new FakeS3Client()).disconnect());
    }

    @Test
    void toleratesRepeatedDisconnect() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        S3Wagon wagon = connected(s3, "s3p://bucket/releases/");

        wagon.disconnect();

        assertDoesNotThrow(wagon::disconnect);
    }

    @Test
    void reconnectsAfterDisconnect() throws Exception {
        FakeS3Client s3 = new FakeS3Client();
        S3Wagon wagon = connected(s3, "s3p://bucket/releases/");
        wagon.disconnect();

        assertDoesNotThrow(() -> wagon.connect(new Repository("test", "s3p://bucket/releases/")));
    }
}
