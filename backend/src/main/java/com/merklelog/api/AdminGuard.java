package com.merklelog.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Locks database writes on the public deployment behind a presenter key.
 *
 * <p>The site is public, so strangers can browse, prove entries and run the in-memory tamper
 * comparison, but they must not be able to rewrite the stored log, reseed it or fill the database
 * before the review. Requests that write are registered against this interceptor in
 * {@link WebConfig}; each must carry the header {@code X-Admin-Key} equal to {@code app.admin-key}.
 *
 * <p>When no key is configured (local development), nothing is locked. The comparison is
 * constant-time ({@link MessageDigest#isEqual}), so response timing does not leak how many leading
 * characters of a guess were right — the same reason {@code Hashing.equal} is constant-time.
 */
@Component
public class AdminGuard implements HandlerInterceptor {

    public static final String HEADER = "X-Admin-Key";

    private final byte[] key;

    public AdminGuard(@Value("${app.admin-key:}") String key) {
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    /** True when writes require a key, i.e. on the public deployment. */
    public boolean required() {
        return key.length > 0;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!required() || "OPTIONS".equals(request.getMethod())) {
            return true;
        }
        String supplied = request.getHeader(HEADER);
        if (supplied != null && MessageDigest.isEqual(key, supplied.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"status\":401,\"title\":\"Unauthorized\","
                + "\"detail\":\"This action writes to the database and needs the presenter key.\"}");
        return false;
    }
}
