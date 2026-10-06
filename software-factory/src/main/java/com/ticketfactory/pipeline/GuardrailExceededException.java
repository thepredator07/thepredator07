package com.ticketfactory.pipeline;

/** A per-ticket limit (cost, turns, time) was hit. Never retried: the ticket fails immediately. */
public class GuardrailExceededException extends RuntimeException {

    /** Which limit: {@code cost}, {@code turns}, {@code timeout} or {@code budget} (nothing left before a run). */
    private final String guardrail;

    public GuardrailExceededException(String guardrail, String message) {
        super("guardrail: " + message);
        this.guardrail = guardrail;
    }

    public String guardrail() {
        return guardrail;
    }
}
