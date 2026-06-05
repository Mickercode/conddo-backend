package io.conddo.api.billing;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link RequiresFeatureInterceptor} on every {@code /api/v1/**}
 * path. Scoped narrowly so {@code /auth/**}, {@code /actuator/**}, etc. stay
 * out of the interceptor chain.
 */
@Configuration
public class BillingWebConfig implements WebMvcConfigurer {

    private final RequiresFeatureInterceptor interceptor;

    public BillingWebConfig(RequiresFeatureInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/**");
    }
}
