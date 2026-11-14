package ro.sasps.monitoring;

import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;

public final class SampleHistory implements Consumer<Sample> {
    private final int capacity;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    public SampleHistory(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("History capacity must be positive");
        }
        this.capacity = capacity;
    }

    @Override
    public synchronized void accept(Sample sample) {
        if (samples.size() == capacity) {
            samples.removeFirst();
        }
        samples.addLast(sample);
    }

    public synchronized Sample latest() {
        return samples.peekLast();
    }

    public synchronized List<Sample> snapshot() {
        return List.copyOf(samples);
    }

    public int capacity() {
        return capacity;
    }
}
