package com.ticketfactory.integration;

/**
 * A step failed in a way that retrying cannot fix, such as a target repo without a check command. The pipeline fails
 * the ticket at once with this message instead of spending its retries.
 */
public class UnrecoverableStepException extends StepFailedException {

    public UnrecoverableStepException(String message) {
        super(message);
    }
}
