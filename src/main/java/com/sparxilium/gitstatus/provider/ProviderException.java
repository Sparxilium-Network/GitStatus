package com.sparxilium.gitstatus.provider;

/** Provider API failure with a message that is safe to show to the administrator. */
public class ProviderException extends RuntimeException {

    public ProviderException(String message) {
        super(message);
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
