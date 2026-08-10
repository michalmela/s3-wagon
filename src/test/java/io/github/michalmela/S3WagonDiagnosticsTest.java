package io.github.michalmela;

import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.repository.Repository;
import org.junit.jupiter.api.Test;

import static io.github.michalmela.S3WagonPutTest.fileContaining;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failing deploy is nearly always a configuration problem, so `mvn -X` has to be able to answer
 * "what did the wagon actually resolve?". Before this the wagon logged nothing at all.
 */
class S3WagonDiagnosticsTest {

    @Test
    void explainsWhatItResolvedOnConnect() throws Exception {
        RecordingTransferListener listener = new RecordingTransferListener();
        S3Wagon wagon = new S3Wagon(new FakeS3Client());
        wagon.addTransferListener(listener);
        wagon.setRegion("eu-central-1");
        wagon.setEndpoint("https://minio.example.com");
        wagon.setPathStyleAccess(true);

        wagon.connect(new Repository("test", "s3p://bucket/releases/"));

        String said = String.join("\n", listener.debugMessages());
        assertTrue(said.contains("bucket=bucket"), said);
        assertTrue(said.contains("prefix=releases/"), said);
        assertTrue(said.contains("region=eu-central-1"), said);
        assertTrue(said.contains("endpoint=https://minio.example.com"), said);
        assertTrue(said.contains("pathStyleAccess=true"), said);
    }

    @Test
    void namesTheCredentialSource() throws Exception {
        RecordingTransferListener listener = new RecordingTransferListener();
        S3Wagon wagon = new S3Wagon(new FakeS3Client());
        wagon.addTransferListener(listener);

        wagon.connect(new Repository("test", "s3p://bucket/releases/"));

        assertTrue(String.join("\n", listener.debugMessages()).contains("credentials=default provider chain"));
    }

    @Test
    void namesSettingsCredentialsWithoutLeakingThem() throws Exception {
        RecordingTransferListener listener = new RecordingTransferListener();
        S3Wagon wagon = new S3Wagon(new FakeS3Client());
        wagon.addTransferListener(listener);
        AuthenticationInfo authentication = new AuthenticationInfo();
        authentication.setUserName("AKIAEXAMPLE");
        authentication.setPassword("super-secret");

        wagon.connect(new Repository("test", "s3p://bucket/releases/"), authentication);

        String said = String.join("\n", listener.debugMessages());
        assertTrue(said.contains("credentials=settings.xml"), said);
        assertTrue(!said.contains("super-secret"), "the secret must never be logged: " + said);
        assertTrue(!said.contains("AKIAEXAMPLE"), "the access key must never be logged: " + said);
    }

    @Test
    void reportsTheKeyBeingTransferred() throws Exception {
        RecordingTransferListener listener = new RecordingTransferListener();
        FakeS3Client s3 = new FakeS3Client();
        s3.seed("releases/g/a/1.0/a-1.0.jar", "artifact");
        S3Wagon wagon = new S3Wagon(s3);
        wagon.addTransferListener(listener);
        wagon.connect(new Repository("test", "s3p://bucket/releases/"));

        wagon.get("g/a/1.0/a-1.0.jar", S3WagonKeyTest.destination());

        assertTrue(String.join("\n", listener.debugMessages())
                .contains("s3://bucket/releases/g/a/1.0/a-1.0.jar"));
    }

    @Test
    void reportsMultipartDetails() throws Exception {
        RecordingTransferListener listener = new RecordingTransferListener();
        FakeS3Client s3 = new FakeS3Client();
        S3Wagon wagon = new S3Wagon(s3);
        wagon.addTransferListener(listener);
        wagon.setMultipartThreshold("1048576");
        wagon.setMultipartPartSize("5242880");
        wagon.connect(new Repository("test", "s3p://bucket/releases/"));

        wagon.put(fileContaining(new byte[6 * 1024 * 1024]), "g/a/1.0/a-1.0.jar");

        assertTrue(String.join("\n", listener.debugMessages()).contains("multipart upload"));
    }
}
