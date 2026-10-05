package com.merklelog.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Health check for the host, and the settings the frontend needs to know about. */
@RestController
@RequestMapping("/api")
public class SystemController {

    private final AdminGuard adminGuard;

    public SystemController(AdminGuard adminGuard) {
        this.adminGuard = adminGuard;
    }

    /** For Render's health check: answers without touching the database. */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    /** Whether database writes need the presenter key, so the UI can ask for it up front. */
    @GetMapping("/config")
    public Map<String, Boolean> config() {
        return Map.of("adminRequired", adminGuard.required());
    }
}
