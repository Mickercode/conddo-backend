package io.conddo.studio.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.studio.domain.DesignStandard;
import io.conddo.studio.domain.Job;
import io.conddo.studio.dsl.DesignStandardService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiAssistantService with a mocked {@link ClaudeClient}: it parses Claude's JSON
 * (even with surrounding prose), flags {@code available=false} when Claude is
 * unavailable, uses adaptive thinking only for the QA scan, and injects active
 * Design Standard Library entries into the system prompt when they exist.
 */
class AiAssistantServiceTest {

    private final ClaudeClient claude = mock(ClaudeClient.class);
    private final DesignStandardService designStandards = mock(DesignStandardService.class);
    private final AiAssistantService service = new AiAssistantService(claude, new ObjectMapper(), designStandards);

    {
        // Default to empty DSL — existing tests don't depend on standards being present.
        when(designStandards.forVertical(anyString(), any())).thenReturn(List.of());
    }

    @Test
    void copyParsesJsonEvenWithSurroundingProse() {
        when(claude.complete(anyString(), anyString(), anyInt(), eq(false))).thenReturn(Optional.of(
                "Sure — here is the hero copy:\n{\"headline\":\"Genuine medicines, always in stock\","
                        + "\"subheadline\":\"Your trusted Lekki pharmacy\",\"ctaText\":\"Order now\"}\nHope that helps!"));

        AiAssistantService.CopyResult result = service.generateSectionCopy(
                Map.of("businessName", "MedPlus", "vertical", "pharmacy"), "hero");

        assertTrue(result.available());
        assertEquals("HERO", result.section());
        assertEquals("Genuine medicines, always in stock", result.copy().get("headline"));
    }

    @Test
    void copyUnavailableWhenClaudeReturnsNothing() {
        when(claude.complete(anyString(), anyString(), anyInt(), eq(false))).thenReturn(Optional.empty());

        AiAssistantService.CopyResult result = service.generateSectionCopy(Map.of(), "HERO");

        assertFalse(result.available());
        assertTrue(result.copy().isEmpty());
    }

    @Test
    void paletteParses() {
        when(claude.complete(anyString(), anyString(), anyInt(), eq(false)))
                .thenReturn(Optional.of("{\"primary\":\"#7C5CBF\",\"background\":\"#FFFFFF\"}"));

        AiAssistantService.PaletteResult result = service.generatePalette("#7C5CBF");

        assertTrue(result.available());
        assertEquals("#7C5CBF", result.palette().get("primary"));
    }

    @Test
    void scanUsesAdaptiveThinkingAndParses() {
        when(claude.complete(anyString(), anyString(), anyInt(), eq(true))).thenReturn(Optional.of(
                "{\"issues\":[],\"suggestions\":[],\"positives\":[\"Clean layout\"],\"overallQuality\":\"PASS\"}"));
        Job job = new Job("WB-1001", "WEBSITE_BUILD", UUID.randomUUID(), "Website Build — MedPlus",
                Map.of("businessName", "MedPlus", "vertical", "pharmacy"), "SUBMITTED", OffsetDateTime.now());

        AiAssistantService.QaScanResult result = service.scanSubmission(job);

        assertTrue(result.available());
        assertEquals("PASS", result.scan().get("overallQuality"));
        verify(claude).complete(anyString(), anyString(), anyInt(), eq(true));   // think = true
    }

    @Test
    void malformedJsonIsTreatedAsUnavailable() {
        when(claude.complete(anyString(), anyString(), anyInt(), eq(false)))
                .thenReturn(Optional.of("I could not generate that right now."));

        AiAssistantService.CopyResult result = service.generateSectionCopy(Map.of(), "HERO");

        assertFalse(result.available());
    }

    @Test
    void rankImagesSortsByScoreAndDropsToZeroOnFailure() {
        // Two different vision responses for two different URLs.
        when(claude.completeWithImage(anyString(), anyString(), eq("https://a/img.png"), anyInt()))
                .thenReturn(Optional.of(
                        "{\"score\":4,\"reason\":\"Blurry\",\"recommendation\":\"ACCEPTABLE\"}"));
        when(claude.completeWithImage(anyString(), anyString(), eq("https://b/img.png"), anyInt()))
                .thenReturn(Optional.of(
                        "{\"score\":9,\"reason\":\"Crisp brand shot\",\"recommendation\":\"RECOMMENDED\"}"));
        // One that fails — should still appear, last, score 0.
        when(claude.completeWithImage(anyString(), anyString(), eq("https://c/img.png"), anyInt()))
                .thenReturn(Optional.empty());

        AiAssistantService.RankResult result = service.rankImages(
                List.of("https://a/img.png", "https://b/img.png", "https://c/img.png"),
                "pharmacy", "hero");

        assertTrue(result.available());   // at least one succeeded
        assertEquals(3, result.ranked().size());
        assertEquals("https://b/img.png", result.ranked().get(0).url());   // highest
        assertEquals(9, result.ranked().get(0).score());
        assertEquals("https://c/img.png", result.ranked().get(2).url());   // failed → last
        assertEquals(0, result.ranked().get(2).score());
        assertEquals("REJECT", result.ranked().get(2).recommendation());
    }

    @Test
    void rankImagesEmptyListIsAvailableNoCalls() {
        AiAssistantService.RankResult result = service.rankImages(List.of(), "pharmacy", "hero");
        assertTrue(result.available());
        assertTrue(result.ranked().isEmpty());
    }

    @Test
    void copyPromptIncludesActiveCopyPatternStandards() {
        when(designStandards.forVertical(eq("COPY_PATTERN"), eq("pharmacy"))).thenReturn(List.of(
                standard("Pharmacy brand voice", "Clinical but human",
                        Map.of("avoid", List.of("miracle", "guaranteed cure")))));
        when(claude.complete(anyString(), anyString(), anyInt(), eq(false)))
                .thenReturn(Optional.of("{\"headline\":\"x\"}"));

        service.generateSectionCopy(Map.of("vertical", "pharmacy"), "hero");

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(claude).complete(systemPrompt.capture(), anyString(), anyInt(), eq(false));
        String prompt = systemPrompt.getValue();
        assertTrue(prompt.contains("Standards reference (copy pattern"),
                "DSL header should appear when standards exist");
        assertTrue(prompt.contains("Pharmacy brand voice"), "standard name should be quoted");
        assertTrue(prompt.contains("Clinical but human"), "description should be quoted");
        assertTrue(prompt.contains("miracle"), "content payload should be in the prompt");
    }

    @Test
    void copyPromptOmitsStandardsHeaderWhenLibraryIsEmpty() {
        when(designStandards.forVertical(eq("COPY_PATTERN"), eq("fashion"))).thenReturn(List.of());
        when(claude.complete(anyString(), anyString(), anyInt(), eq(false)))
                .thenReturn(Optional.of("{\"headline\":\"x\"}"));

        service.generateSectionCopy(Map.of("vertical", "fashion"), "hero");

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(claude).complete(systemPrompt.capture(), anyString(), anyInt(), eq(false));
        // No empty "Standards reference:" header dangling without content.
        assertFalse(systemPrompt.getValue().contains("Standards reference"),
                "empty DSL must not leave a header in the prompt");
    }

    @Test
    void paletteWithVerticalConsultsPaletteStandards() {
        when(designStandards.forVertical(eq("PALETTE"), eq("pharmacy"))).thenReturn(List.of(
                standard("Pharmacy — calming greens", "Trustworthy, clinical",
                        Map.of("primary", "#22C55E", "background", "#F0FDF4"))));
        when(claude.complete(anyString(), anyString(), anyInt(), eq(false)))
                .thenReturn(Optional.of("{\"primary\":\"#22C55E\"}"));

        AiAssistantService.PaletteResult result = service.generatePalette("#22C55E", "pharmacy");

        assertTrue(result.available());
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(claude).complete(systemPrompt.capture(), anyString(), anyInt(), eq(false));
        assertTrue(systemPrompt.getValue().contains("calming greens"),
                "palette standards should be injected when vertical is set");
    }

    @Test
    void paletteWithoutVerticalQueriesGlobalsOnly() {
        when(designStandards.forVertical(eq("PALETTE"), eq(null))).thenReturn(List.of());
        when(claude.complete(anyString(), anyString(), anyInt(), eq(false)))
                .thenReturn(Optional.of("{\"primary\":\"#7C5CBF\"}"));

        service.generatePalette("#7C5CBF");

        // Verifies the single-arg overload still works (delegates to the two-arg with null vertical).
        verify(designStandards).forVertical(eq("PALETTE"), eq(null));
    }

    // ----- helpers ------------------------------------------------------------

    private static DesignStandard standard(String name, String description, Map<String, Object> content) {
        return new DesignStandard("pharmacy", "PALETTE", name, description, content);
    }
}
