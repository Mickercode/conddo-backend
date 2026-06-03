package io.conddo.core.signup;

import java.util.List;

/**
 * A {@link WebsiteTypeResolver} verdict. Embedded in the Studio job brief at
 * intake time so the developer sees what to build and why.
 *
 * @param type        the chosen website type
 * @param sections    the default section layout for that type (matches the
 *                    section keys the Studio builder + vertical configs use)
 * @param reasoning   a one-line, human-readable explanation — shown in the brief
 */
public record WebsiteTypeRecommendation(WebsiteType type, List<String> sections, String reasoning) {
}
