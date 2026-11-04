package dev.voxelbrew.engine.feature;

import dev.voxelbrew.engine.geometry.Solid;
import java.util.Map;

/**
 * Trusted persistent new-body operation, loaded through ServiceLoader. API 1 publishes one
 * independent body named "body"; later built-in operations may consume that output. Codecs must be
 * deterministic and must reject unsupported payload versions. No native work belongs in the codecs.
 * The provider closes intermediate solids; ownership of the returned solid transfers to the engine.
 * Evaluation must not retain it or share it between calls.
 */
public interface FeatureProvider {
    int API_VERSION = 1;

    FeatureDescriptor descriptor();

    byte[] encode(Map<String, Double> parameters);

    Map<String, Double> decode(int payloadVersion, byte[] payload);

    Solid evaluate(int payloadVersion, byte[] payload);
}
