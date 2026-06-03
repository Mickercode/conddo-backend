package io.conddo.studio.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request an AI colour palette from a primary hex colour (§8), e.g. {@code "#7C5CBF"}.
 * Optional {@code vertical} (e.g. {@code "pharmacy"}) grounds the palette in the
 * Design Standard Library's curated PALETTE entries for that vertical.
 */
public record PaletteRequest(@NotBlank String primaryHex, String vertical) {
}
