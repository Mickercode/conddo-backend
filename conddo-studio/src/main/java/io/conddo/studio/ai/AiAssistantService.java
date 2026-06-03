package io.conddo.studio.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.studio.domain.DesignStandard;
import io.conddo.studio.domain.Job;
import io.conddo.studio.dsl.DesignStandardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The AI assistant (§8): Claude-backed copy generation, colour palettes, and QA
 * scans for the website-build workflow. The system prompt is layered — a fixed
 * identity, the anti-"AI-slurp" copy rules (included in <b>every</b> prompt per
 * the §20 AI rules), the vertical tone, and per-section instructions.
 *
 * <p>Every method is fail-safe: if Claude is unconfigured, times out, errors, or
 * returns unparseable output, the result carries {@code available = false} with
 * empty content — it never throws. Claude's raw text is always parsed
 * server-side, never returned verbatim.
 */
@Service
public class AiAssistantService {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantService.class);

    /** Layer 1 — always present. */
    private static final String SYSTEM_IDENTITY = """
            You are the AI assistant inside Conddo Studio, the internal website build tool for
            Conddo.io. Conddo.io builds professional websites for Nigerian SMEs. Every output
            must meet the Conddo.io design and copy standard.""";

    /** Layer 2 — always present (the anti-AI-slurp rules; §20 requires this in every prompt). */
    private static final String COPY_RULES = """
            Write in plain, direct English. Never use these words: seamless, robust, leverage,
            scalable solutions, cutting-edge, empower, revolutionize, transform, innovative,
            state-of-the-art, best-in-class. Short sentences. Active voice. Specific details.
            Lead with the customer's outcome, not the business description.""";

    /** Layer 3 — vertical tone, keyed by the brief's vertical. */
    private static final Map<String, String> VERTICAL_TONES = Map.of(
            "pharmacy", "Tone: Trustworthy, clinical but approachable. Patients need to trust this "
                    + "pharmacy immediately. Emphasise: genuine medications, licensed pharmacist, "
                    + "community service, convenience.",
            "fashion", "Tone: Warm, personal, aspirational. This is a craft business with individual "
                    + "attention. Emphasise: custom made, made for you, attention to detail.",
            "logistics", "Tone: Efficient, direct, reliable. Speed and reliability are the selling "
                    + "points. Emphasise: on-time, tracked, dependable.",
            "professional_services", "Tone: Authoritative, credible, results-focused. The client needs "
                    + "to trust the expertise. Emphasise: experience, results, professionalism.");

    private static final String DEFAULT_TONE = "Tone: Professional and approachable.";

    private final ClaudeClient claude;
    private final ObjectMapper objectMapper;
    private final DesignStandardService designStandards;

    public AiAssistantService(ClaudeClient claude, ObjectMapper objectMapper,
                              DesignStandardService designStandards) {
        this.claude = claude;
        this.objectMapper = objectMapper;
        this.designStandards = designStandards;
    }

    // ── COPY GENERATOR ────────────────────────────────────────────────────────

    /** Generates copy for one website section (HERO / SERVICES / ABOUT / …) from the brief. */
    public CopyResult generateSectionCopy(Map<String, Object> brief, String section) {
        String sec = section == null ? "" : section.trim().toUpperCase();
        String system = layered(toneFor(brief),
                standardsBlock("COPY_PATTERN", verticalOf(brief)),
                sectionInstructions(sec));
        String user = buildCopyPrompt(brief, sec);

        Map<String, Object> copy = parse(claude.complete(system, user, 800, false));
        return new CopyResult(copy != null, sec, copy == null ? Map.of() : copy);
    }

    // ── COLOUR PALETTE ────────────────────────────────────────────────────────

    /** Generates a WCAG-AA-accessible palette from a primary hex colour (no vertical scoping). */
    public PaletteResult generatePalette(String primaryHex) {
        return generatePalette(primaryHex, null);
    }

    /**
     * Generates a WCAG-AA-accessible palette from a primary hex colour, optionally
     * grounded in the vertical's curated palette standards from the Design Standard
     * Library (§8). When {@code vertical} is set, the prompt includes any active
     * {@code PALETTE} standards for that vertical (plus globals) so Claude tilts
     * toward the production team's house style.
     */
    public PaletteResult generatePalette(String primaryHex, String vertical) {
        String system = layered(standardsBlock("PALETTE", vertical));
        String user = """
                Generate a complete, accessible colour palette for a professional website using %s
                as the primary colour.

                Requirements:
                - All text/background combinations must pass WCAG AA (min 4.5:1 contrast for normal text)
                - Background should be clean white or near-white
                - The palette should feel professional and trustworthy

                Return JSON only:
                {"primary": string, "primaryHover": string, "primaryLight": string, "primaryBg": string,
                 "background": string, "surface": string, "textPrimary": string, "textSecondary": string,
                 "border": string}""".formatted(primaryHex == null ? "#7C5CBF" : primaryHex);

        Map<String, Object> palette = parse(claude.complete(system, user, 400, false));
        return new PaletteResult(palette != null, palette == null ? Map.of() : palette);
    }

    // ── IMAGE RANKER ──────────────────────────────────────────────────────────

    /**
     * Rates a list of candidate images for use on a {@code vertical} website's
     * {@code sectionType}, returning them sorted by score desc. Each call uses
     * Claude vision; a failed call yields a zero-score "AI unavailable" entry —
     * the request itself still succeeds.
     */
    public RankResult rankImages(List<String> imageUrls, String vertical, String sectionType) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return new RankResult(true, List.of());
        }
        String system = SYSTEM_IDENTITY + "\n\n" + COPY_RULES;
        String ranker = """
                Rate this image for use on a %s business website in the %s section.

                Score 1-10 where:
                10 = perfect, professional, clearly shows the business
                5  = acceptable but not ideal
                1  = blurry, irrelevant, or poor quality

                Return JSON only:
                {"score": number, "reason": string, "recommendation": "RECOMMENDED|ACCEPTABLE|REJECT"}""".formatted(
                vertical == null ? "general" : vertical,
                sectionType == null ? "hero" : sectionType);

        List<RankedImage> ranked = new ArrayList<>();
        int succeeded = 0;
        for (String url : imageUrls) {
            Map<String, Object> parsed = parse(claude.completeWithImage(system, ranker, url, 250));
            if (parsed != null) {
                succeeded++;
                Object scoreVal = parsed.getOrDefault("score", 0);
                int score = scoreVal instanceof Number n ? n.intValue() : 0;
                ranked.add(new RankedImage(url, score,
                        String.valueOf(parsed.getOrDefault("reason", "")),
                        String.valueOf(parsed.getOrDefault("recommendation", "ACCEPTABLE"))));
            } else {
                ranked.add(new RankedImage(url, 0, "AI unavailable", "REJECT"));
            }
        }
        ranked.sort(Comparator.comparingInt(RankedImage::score).reversed());
        return new RankResult(succeeded > 0, ranked);
    }

    // ── QA SCANNER ────────────────────────────────────────────────────────────

    /**
     * Reviews a submitted job for QA issues, suggestions, and positives. Uses
     * adaptive thinking (a genuinely complex review). For now it reasons over the
     * brief + the developer's submission notes + the studio URL; scanning the
     * rendered site HTML lands when conddo-sites exposes a content snapshot.
     */
    public QaScanResult scanSubmission(Job job) {
        Map<String, Object> brief = job.getBrief() == null ? Map.of() : job.getBrief();
        String vertical = verticalOf(brief);
        // QA reads every relevant standard so it can flag drift from any axis.
        String system = layered(
                standardsBlock("COPY_PATTERN", vertical),
                standardsBlock("LAYOUT", vertical),
                standardsBlock("TYPOGRAPHY", vertical));
        String user = """
                Review this Conddo.io website submission for %s (%s vertical).

                Business brief:
                %s

                Studio URL: %s

                Identify:
                1. ISSUES (must fix): anything that would fail QA
                2. SUGGESTIONS (optional improvements)
                3. POSITIVES: what was done well

                Return JSON only:
                {"issues": [{"section": string, "description": string}],
                 "suggestions": [{"section": string, "description": string}],
                 "positives": [string],
                 "overallQuality": "PASS|BORDERLINE|FAIL"}""".formatted(
                str(brief, "businessName", job.getTitle()),
                str(brief, "vertical", "general"),
                briefSummary(brief),
                job.getStudioUrl() == null ? "(not provided)" : job.getStudioUrl());

        Map<String, Object> scan = parse(claude.complete(system, user, 2000, true));
        return new QaScanResult(scan != null, scan == null ? Map.of() : scan);
    }

    // ── prompt helpers ─────────────────────────────────────────────────────────

    /**
     * Assemble the system prompt's static layers ({@link #SYSTEM_IDENTITY},
     * {@link #COPY_RULES}) and any caller-supplied dynamic layers, dropping the
     * empty ones so a vertical without DSL entries doesn't get a "Standards
     * reference:" header followed by nothing.
     */
    private static String layered(String... dynamicLayers) {
        StringBuilder sb = new StringBuilder(SYSTEM_IDENTITY).append("\n\n").append(COPY_RULES);
        for (String layer : dynamicLayers) {
            if (layer != null && !layer.isBlank()) {
                sb.append("\n\n").append(layer);
            }
        }
        return sb.toString();
    }

    private String toneFor(Map<String, Object> brief) {
        return VERTICAL_TONES.getOrDefault(verticalOf(brief), DEFAULT_TONE);
    }

    private static String verticalOf(Map<String, Object> brief) {
        return str(brief == null ? Map.of() : brief, "vertical", "")
                .toLowerCase()
                .replace('-', '_');
    }

    /**
     * Format active Design Standard Library entries of a {@code kind} for inclusion
     * in a Claude prompt. Returns an empty string when nothing applies — callers
     * append it conditionally via {@link #layered}.
     */
    private String standardsBlock(String kind, String vertical) {
        List<DesignStandard> standards;
        try {
            standards = designStandards.forVertical(kind, vertical);
        } catch (RuntimeException ex) {
            // Same fail-safe contract as the AI itself — never let DSL retrieval break a generation.
            log.warn("Could not load {} design standards for vertical={}: {}", kind, vertical, ex.getMessage());
            return "";
        }
        if (standards.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Standards reference (")
                .append(kind.toLowerCase().replace('_', ' '))
                .append(", curated by the production team — follow these unless the brief overrides):\n");
        for (DesignStandard standard : standards) {
            sb.append("- ").append(standard.getName());
            if (standard.getDescription() != null && !standard.getDescription().isBlank()) {
                sb.append(" — ").append(standard.getDescription());
            }
            if (!standard.getContent().isEmpty()) {
                try {
                    sb.append("\n    content: ").append(objectMapper.writeValueAsString(standard.getContent()));
                } catch (Exception ignore) {
                    // If the content can't be serialised, omit it — the name + description still help.
                }
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static String sectionInstructions(String section) {
        return switch (section) {
            case "HERO" -> """
                    Hero section: Headline leads with the customer's outcome (one strong statement);
                    subheadline adds specific context (max 15 words); CTA is a specific action verb
                    ('Book a consultation', not 'Contact us').
                    Return JSON: {"headline": string, "subheadline": string, "ctaText": string}""";
            case "SERVICES" -> """
                    Services section: a 2-sentence description per service (first sentence = what it is,
                    second = the benefit). Use the services list from the brief.
                    Return JSON: {"services": [{"name": string, "description": string}]}""";
            case "ABOUT" -> """
                    About section: write from the business description; 3 sentences max; the third says
                    why to choose this business.
                    Return JSON: {"aboutText": string}""";
            default -> "Return JSON with the relevant copy fields for the " + section + " section.";
        };
    }

    private String buildCopyPrompt(Map<String, Object> brief, String section) {
        return "Write the %s section copy for this business.\n\n%s".formatted(section, briefSummary(brief));
    }

    private String briefSummary(Map<String, Object> brief) {
        StringBuilder sb = new StringBuilder();
        appendIf(sb, "Business name", brief.get("businessName"));
        appendIf(sb, "Vertical", brief.get("vertical"));
        appendIf(sb, "Description", brief.get("description"));
        appendIf(sb, "Services", brief.get("services"));
        appendIf(sb, "Location", brief.get("location"));
        appendIf(sb, "Contact", brief.get("contactDetails"));
        return sb.isEmpty() ? "(no brief details provided)" : sb.toString().trim();
    }

    private static void appendIf(StringBuilder sb, String label, Object value) {
        if (value != null && !value.toString().isBlank()) {
            sb.append(label).append(": ").append(value).append('\n');
        }
    }

    private static String str(Map<String, Object> map, String key, String fallback) {
        Object v = map.get(key);
        return v == null || v.toString().isBlank() ? fallback : v.toString();
    }

    /** Leniently extracts the JSON object from Claude's text. Returns null if absent/unparseable. */
    private Map<String, Object> parse(Optional<String> text) {
        if (text.isEmpty()) {
            return null;
        }
        String raw = text.get();
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warn("Claude output had no JSON object");
            return null;
        }
        try {
            return objectMapper.readValue(raw.substring(start, end + 1),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception ex) {
            log.warn("Could not parse Claude JSON: {}", ex.getMessage());
            return null;
        }
    }

    // ── result records ──────────────────────────────────────────────────────────

    /** Copy for one section. {@code available=false} when the assistant couldn't produce it. */
    public record CopyResult(boolean available, String section, Map<String, Object> copy) {
    }

    /** A generated colour palette. */
    public record PaletteResult(boolean available, Map<String, Object> palette) {
    }

    /** A QA scan: issues / suggestions / positives / overallQuality (under {@code scan}). */
    public record QaScanResult(boolean available, Map<String, Object> scan) {
    }

    /** A scored image candidate for a website section. */
    public record RankedImage(String url, int score, String reason, String recommendation) {
    }

    /** Image-ranking result, sorted by score desc. {@code available=false} if every call failed. */
    public record RankResult(boolean available, List<RankedImage> ranked) {
    }
}
