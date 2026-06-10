package io.conddo.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.ai.AnthropicGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pharmacy Spec v2 §12C — AI Product Assistant. Two prompts:
 * <ul>
 *   <li>{@link #productFromImage} — vision call that extracts a full
 *       product card from a packaging photo.</li>
 *   <li>{@link #description} — text call that generates a sales
 *       description + warnings from minimal seed data.</li>
 * </ul>
 *
 * <p>The model's natural inclination is to wrap JSON in markdown
 * fences; we strip them defensively before parsing.
 */
@Service
public class PharmacyAiAssistantService {

    private static final Logger log = LoggerFactory.getLogger(PharmacyAiAssistantService.class);

    private static final Pattern JSON_FENCE = Pattern.compile(
            "^```(?:json)?\\s*(.*?)\\s*```$", Pattern.DOTALL);

    private static final String DISCLAIMER =
            "Please review all fields before saving. AI suggestions are not a substitute for professional verification.";

    private static final String PRODUCT_FROM_IMAGE_PROMPT = """
            You are a Nigerian-pharmacy product-onboarding assistant.
            Examine the drug-packaging photo above and extract a structured
            product card. Return ONLY a single JSON object (no prose, no
            markdown fences) with EXACTLY these keys:

              {
                "nameGeneric": string|null,
                "nameBrand": string|null,
                "description": string|null,
                "indications": string|null,
                "dosageGuidance": string|null,
                "warnings": string|null,
                "storage": string|null,
                "nafdacNumber": string|null,
                "brand": string|null,
                "requiresPrescription": boolean,
                "suggestedCategory": "prescription"|"otc"|"supplements"|"personal-care"|"baby-care"|"first-aid"|null,
                "confidence": "high"|"medium"|"low"
              }

            Rules:
            - Set a field to null if it isn't legible in the image.
            - "confidence" reflects image clarity, NOT how confident you are
              in any single field.
            - Default "requiresPrescription" to true for antibiotics,
              controlled substances, and any drug whose packaging says
              "Pharmacist's Initials" or "POM"; false for OTC pain
              relievers, multivitamins, antacids.
            - Use plain prose for description/warnings/storage — no lists
              or markdown.
            """;

    private final AnthropicGateway gateway;
    private final ObjectMapper objectMapper;

    public PharmacyAiAssistantService(AnthropicGateway gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> productFromImage(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new IllegalArgumentException("imageUrl is required");
        }
        String raw = gateway.chatWithImage(imageUrl, PRODUCT_FROM_IMAGE_PROMPT);
        JsonNode parsed = parseJsonStrict(raw, "product-from-image");
        Map<String, Object> suggestion = new LinkedHashMap<>();
        for (String key : new String[]{"nameGeneric", "nameBrand", "description", "indications",
                "dosageGuidance", "warnings", "storage", "nafdacNumber", "brand",
                "requiresPrescription", "suggestedCategory"}) {
            suggestion.put(key, jsonOrNull(parsed.get(key)));
        }
        String confidence = parsed.path("confidence").asText("medium");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("suggestion", suggestion);
        result.put("confidence", confidence);
        result.put("note", DISCLAIMER);
        return result;
    }

    public Map<String, Object> description(String nameGeneric, String nameBrand, String indications) {
        if (nameGeneric == null || nameGeneric.isBlank()) {
            throw new IllegalArgumentException("nameGeneric is required");
        }
        String prompt = ("""
                You are a Nigerian-pharmacy product-onboarding assistant.
                Write a single professional product description plus key
                clinical warnings for the following drug. Return ONLY a JSON
                object with exactly two string keys:

                  { "description": string, "warnings": string }

                Rules:
                - description: 2–3 sentences, plain prose, no markdown.
                - warnings: contraindications, common interactions, and any
                  red-flag situations (renal impairment, pregnancy, etc.).
                - No lists, no markdown fences.

                Drug:
                """
                + "  nameGeneric: " + nameGeneric + "\n"
                + (nameBrand == null ? "" : "  nameBrand: " + nameBrand + "\n")
                + (indications == null ? "" : "  indications: " + indications + "\n"));
        String raw = gateway.chatText(prompt);
        JsonNode parsed = parseJsonStrict(raw, "description");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("description", parsed.path("description").asText(null));
        result.put("warnings", parsed.path("warnings").asText(null));
        return result;
    }

    /** Strip optional markdown fence and parse — throws if the model didn't return clean JSON. */
    private JsonNode parseJsonStrict(String raw, String which) {
        if (raw == null) {
            throw new AnthropicGateway.AnthropicUnavailableException(
                    "AI returned an empty response for " + which);
        }
        String trimmed = raw.trim();
        Matcher fence = JSON_FENCE.matcher(trimmed);
        if (fence.matches()) {
            trimmed = fence.group(1).trim();
        }
        try {
            return objectMapper.readTree(trimmed);
        } catch (JsonProcessingException ex) {
            log.warn("AI returned non-JSON for {}: {}", which,
                    trimmed.length() > 200 ? trimmed.substring(0, 200) + "…" : trimmed);
            throw new AnthropicGateway.AnthropicUnavailableException(
                    "AI returned non-JSON for " + which);
        }
    }

    /** Jackson treats missing nodes as MissingNode — convert to plain null for clean JSON. */
    private static Object jsonOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        return node.asText();
    }
}
