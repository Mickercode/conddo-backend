package io.conddo.api.web.dto;

/** GET/PUT body for a customer's internal notes (§11.3). {@code notes} may be null/blank to clear. */
public record NotesBody(String notes) {
}
