package io.conddo.studio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Conddo Studio / Jobs Board — a standalone internal service (Infrastructure
 * §12/§13). Separate from the main platform: its own datasource (studio/jobs
 * schemas), its own HMAC {@code STUDIO_JWT}, internal staff only, no tenant RLS.
 */
@SpringBootApplication
public class StudioApplication {

    public static void main(String[] args) {
        SpringApplication.run(StudioApplication.class, args);
    }
}
