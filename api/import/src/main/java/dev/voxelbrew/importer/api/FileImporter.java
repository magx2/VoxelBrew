package dev.voxelbrew.importer.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** Versioned import SPI. The host owns the input; providers must not retain it after returning. */
public interface FileImporter {
    int API_VERSION = 1;

    ImporterDescriptor descriptor();

    ImportResult read(InputStream input, Map<String, String> options) throws IOException;
}
