package com.ticketfactory.pipeline;

/** A per-ticket limit (cost, turns, time) was hit. Never retried: the ticket fails immediately. */
public class GuardrailExceededException extends RuntimeException {

    public GuardrailExceededException(String message) {
        super("guardrail: " + message);
    }
}
