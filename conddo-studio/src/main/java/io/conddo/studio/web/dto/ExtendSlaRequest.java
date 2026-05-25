package io.conddo.studio.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Extend a job's SLA by a number of hours, with a reason. */
public record ExtendSlaRequest(@NotNull @Positive Integer hours, String reason) {
}
