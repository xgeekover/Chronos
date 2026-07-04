package io.chronos.api.adapter;

/** Thrown by adapters for connection/validation/collection failures (§6). */
public class AdapterException extends Exception {

    public AdapterException(String message) {
        super(message);
    }

    public AdapterException(String message, Throwable cause) {
        super(message, cause);
    }
}
