package io.conddo.studio.web.dto;

import java.util.Map;

/** Approve a submission. {@code checklist} = {itemId: {passed, note}}; {@code positiveNotes} optional. */
public record QaApproveRequest(Map<String, Object> checklist, String positiveNotes) {
}
