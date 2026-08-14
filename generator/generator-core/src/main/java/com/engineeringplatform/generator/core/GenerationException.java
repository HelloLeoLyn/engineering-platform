package com.engineeringplatform.generator.core;

/**
 * Generation-time failure (V02-WORK-004).
 * Thrown during planning/assembly — before any workspace write (fail at the right stage).
 */
public class GenerationException extends RuntimeException {

    public GenerationException(String message) {
        super(message);
    }

    public GenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
