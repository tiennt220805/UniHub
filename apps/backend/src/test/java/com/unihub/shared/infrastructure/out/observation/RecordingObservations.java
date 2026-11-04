package com.unihub.shared.infrastructure.out.observation;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;

/** An {@link ObservationRegistry} that keeps what each finished observation carried. */
public final class RecordingObservations {

    /** What a span would be built from: the name, every value, and an error if one was given. */
    public record Recorded(String name, List<KeyValue> values, Throwable error) {
        public String value(String key) {
            return values.stream()
                    .filter(kv -> kv.getKey().equals(key))
                    .map(KeyValue::getValue)
                    .findFirst()
                    .orElse(null);
        }

        /** Everything a handler could turn into a span attribute, as one string. */
        public String everything() {
            return name + " " + values;
        }
    }

    private final List<Recorded> finished = new ArrayList<>();
    private final ObservationRegistry registry = ObservationRegistry.create();

    public RecordingObservations() {
        registry.observationConfig()
                .observationHandler(
                        new ObservationHandler<Observation.Context>() {
                            @Override
                            public boolean supportsContext(Observation.Context context) {
                                return true;
                            }

                            @Override
                            public void onStop(Observation.Context context) {
                                finished.add(
                                        new Recorded(
                                                context.getName(),
                                                new ArrayList<>(
                                                        context.getAllKeyValues().stream()
                                                                .toList()),
                                                context.getError()));
                            }
                        });
    }

    public ObservationRegistry registry() {
        return registry;
    }

    public List<Recorded> finished() {
        return finished;
    }
}
