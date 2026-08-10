package io.github.michalmela;

import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.events.TransferListener;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Records the transfer events a wagon fires, so tests can assert on the sequence. */
final class RecordingTransferListener implements TransferListener {

    private final List<String> events = new ArrayList<>();
    private long bytesReported;
    private Exception error;

    List<String> events() {
        return events;
    }

    long bytesReported() {
        return bytesReported;
    }

    Exception error() {
        return error;
    }

    String sequence() {
        return events.stream().collect(Collectors.joining(","));
    }

    @Override
    public void transferInitiated(TransferEvent event) {
        events.add("initiated");
    }

    @Override
    public void transferStarted(TransferEvent event) {
        events.add("started");
    }

    @Override
    public void transferProgress(TransferEvent event, byte[] buffer, int length) {
        if (!events.contains("progress")) {
            events.add("progress");
        }
        bytesReported += length;
    }

    @Override
    public void transferCompleted(TransferEvent event) {
        events.add("completed");
    }

    @Override
    public void transferError(TransferEvent event) {
        events.add("error");
        error = event.getException();
    }

    @Override
    public void debug(String message) {
        // not interesting for these tests
    }
}
