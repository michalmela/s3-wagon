package io.github.michalmela;

import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.repository.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Since 2.30 the SDK adds a CRC32 trailer to every upload, which some S3-compatible stores reject.
 * The default is left as the SDK ships it; this is the escape hatch.
 */
class S3WagonChecksumSettingTest {

    private final Map<String, String> environment = new HashMap<>();

    @AfterEach
    void clearSystemProperties() {
        System.clearProperty("s3wagon.requestChecksumCalculation");
    }

    @Test
    void isUnsetByDefaultSoTheSdkDefaultApplies() {
        assertNull(wagon().requestChecksumCalculation());
    }

    @Test
    void readsTheSettingFromASystemProperty() {
        System.setProperty("s3wagon.requestChecksumCalculation", "when_required");

        assertEquals("when_required", wagon().requestChecksumCalculation());
    }

    @Test
    void readsTheSettingFromTheEnvironment() {
        environment.put("S3WAGON_REQUEST_CHECKSUM_CALCULATION", "when_required");

        assertEquals("when_required", wagon().requestChecksumCalculation());
    }

    @Test
    void rejectsAnUnknownValueOnConnect() {
        S3Wagon wagon = wagon();
        wagon.setRequestChecksumCalculation("sometimes");

        ConnectionException thrown = assertThrows(ConnectionException.class, () -> connect(wagon));
        assertTrue(thrown.getMessage().contains("sometimes"), thrown.getMessage());
    }

    @Test
    void buildsARealClientWithChecksumsWhenRequired() {
        S3Wagon wagon = new S3Wagon();
        wagon.setRegion("eu-central-1");
        wagon.setRequestChecksumCalculation("when_required");

        assertDoesNotThrow(() -> {
            connect(wagon);
            wagon.disconnect();
        });
    }

    private S3Wagon wagon() {
        S3Wagon wagon = new S3Wagon(new FakeS3Client());
        wagon.setEnvironment(environment::get);
        return wagon;
    }

    private static void connect(S3Wagon wagon) throws Exception {
        wagon.connect(new Repository("test", "s3p://bucket/releases/"));
    }
}
