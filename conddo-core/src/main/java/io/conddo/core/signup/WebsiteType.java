package io.conddo.core.signup;

/**
 * The kind of website a newly activated tenant gets auto-provisioned for in
 * Studio. The {@link WebsiteTypeResolver} picks one from the tenant's vertical
 * and plan; the value is embedded in the Studio job brief so the developer knows
 * which template to reach for.
 *
 * <ul>
 *   <li>{@link #LANDING_PAGE} — single page, lead-capture-focused. Starter plan,
 *       simple verticals.</li>
 *   <li>{@link #BOOKING_FOCUSED} — calendar/availability is the centrepiece.
 *       Beauty &amp; wellness, professional services.</li>
 *   <li>{@link #ECOMMERCE} — products + cart + checkout. Pro plan + retail.</li>
 *   <li>{@link #MULTI_PAGE} — the standard multi-section small-business site.
 *       The catch-all for everything else.</li>
 * </ul>
 */
public enum WebsiteType {
    LANDING_PAGE,
    BOOKING_FOCUSED,
    ECOMMERCE,
    MULTI_PAGE
}
