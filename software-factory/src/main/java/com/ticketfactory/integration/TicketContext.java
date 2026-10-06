package com.ticketfactory.integration;

/** What every integration gets to know about the ticket it is working on. */
public record TicketContext(long ticketId, String repo, int issueNumber, String title, String body,
                            String branchName) {
}
