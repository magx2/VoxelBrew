package dev.voxelbrew.engine.persistence;

import java.io.IOException;

public final class DocumentFormatException extends IOException {
    private static final long serialVersionUID = 1L;
    public DocumentFormatException(String message) { super(message); }
    public DocumentFormatException(String message, Throwable cause) { super(message, cause); }
}
