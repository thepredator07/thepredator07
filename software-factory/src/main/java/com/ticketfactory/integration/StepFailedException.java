package com.ticketfactory.integration;

/** A pipeline step failed in a way that may succeed on retry (sandbox did not start, API error, ...). */
public class StepFailedException extends RuntimeException {

    public StepFailedException(String message) {
        super(message);
    }

    public StepFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
