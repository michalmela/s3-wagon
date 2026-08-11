package io.github.michalmela;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3WagonTest {

    @Test
    void recognizesGenericS3NotFoundResponses() {
        S3Exception exception = (S3Exception) S3Exception.builder().statusCode(404).build();

        assertTrue(S3Wagon.isNotFound(exception));
    }

    @Test
    void doesNotTreatOtherS3FailuresAsMissing() {
        S3Exception exception = (S3Exception) S3Exception.builder().statusCode(403).build();

        assertFalse(S3Wagon.isNotFound(exception));
    }
}
