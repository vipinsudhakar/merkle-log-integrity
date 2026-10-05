package com.merklelog.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cross-origin access and the presenter-key lock on database writes.
 *
 * <p>In production the frontend is served by this same application, so CORS is not needed there;
 * it stays for setups where the frontend runs on another origin ({@code APP_CORS_ALLOWED_ORIGINS}).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;
    private final AdminGuard adminGuard;

    public WebConfig(@Value("${app.cors.allowed-origins}") String[] allowedOrigins, AdminGuard adminGuard) {
        this.allowedOrigins = allowedOrigins;
        this.adminGuard = adminGuard;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT")
                .allowedHeaders("Content-Type", AdminGuard.HEADER);
    }

    /**
     * Every endpoint that writes to the database. Path patterns cannot distinguish methods, so the
     * GET endpoints that share these paths are let through by {@link WriteMethodsOnly}.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new WriteMethodsOnly(adminGuard))
                .addPathPatterns(
                        "/api/datasets",                     // POST: create a dataset
                        "/api/admin/**",                     // POST: reseed
                        "/api/datasets/*/entries/*",         // PUT: rewrite an entry (the attacker)
                        "/api/datasets/*/entries/*/restore", // POST: put an entry back from its seed
                        "/api/datasets/*/anchors");          // POST: anchor a super-root
    }

    /** Applies the guard to POST/PUT/DELETE only; reads on the same paths stay public. */
    private record WriteMethodsOnly(AdminGuard guard) implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Object handler) throws Exception {
            return switch (request.getMethod()) {
                case "POST", "PUT", "DELETE", "PATCH" -> guard.preHandle(request, response, handler);
                default -> true;
            };
        }
    }
}
