package io.conddo.studio.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.studio.domain.Job;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiAssistantService with a mocked {@link ClaudeClient}: it parses Claude's JSON
 * (even with surrounding prose), flags {@code available=false} when Claude is
 * unavailable, and uses adaptive thinking only for the QA scan.
 */
class AiAssistantServiceTest {

    private final ClaudeClient claude = mock(ClaudeClient.class);
    private final AiAssistantService service = new AiAssistantService(claude, new ObjectMapper());

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
}
