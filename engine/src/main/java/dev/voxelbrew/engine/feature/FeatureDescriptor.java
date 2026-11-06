package dev.voxelbrew.engine.feature;

import dev.voxelbrew.engine.model.CadDocument;
import java.util.*;

/** One persistent operation type. Payload version is independent of SPI compatibility. */
public record FeatureDescriptor(
        String providerId,
        String type,
        String name,
        int apiVersion,
        int payloadVersion,
        List<FeatureParameter> parameters) {
    public FeatureDescriptor {
        CadDocument.identifier(providerId, "provider ID");
        CadDocument.identifier(type, "feature type");
        Objects.requireNonNull(name);
        if (payloadVersion < 1) throw new IllegalArgumentException("Payload version must be positive");
        parameters = List.copyOf(parameters);
        var keys = new HashSet<String>();
        for (var p : parameters)
            if (!keys.add(p.key())) throw new IllegalArgumentException("Duplicate parameter " + p.key());
    }

    public String key() {
        return providerId + "/" + type;
    }

    @Override
    public String toString() {
        return name + " (" + key() + ")";
    }
}
