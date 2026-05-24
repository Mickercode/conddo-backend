package io.conddo.api.web.dto;

import io.conddo.core.service.BookingService.Link;

/** The shareable self-book link (§11.5): slug, on/off state, and the public URL. */
public record LinkResponse(String slug, boolean enabled, String url) {

    public static LinkResponse from(Link link) {
        return new LinkResponse(link.slug(), link.enabled(), "conddo.io/book/" + link.slug());
    }
}
