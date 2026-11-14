package sasps.monitoring;

import sasps.monitoring.model.*;
import sasps.monitoring.collectors.*;
import sasps.monitoring.store.*;


import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class SampleBus {
    private final List<Consumer<Sample>> subscribers = new ArrayList<>();

    public void subscribe(Consumer<Sample> subscriber) {
        subscribers.add(subscriber);
    }

    public void publish(Sample sample) {
        for (Consumer<Sample> subscriber : subscribers) {
            subscriber.accept(sample);
        }
    }
}
