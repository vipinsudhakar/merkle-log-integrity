package com.merklelog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the REST API that serves the visualiser.
 *
 * <p>Only {@code api} and {@code persistence} use Spring. The integrity engine ({@code core},
 * {@code chunking}) and the benchmark have no Spring imports and are called from here as plain
 * Java, so what the API shows is exactly what the tested engine computes.
 */
@SpringBootApplication
public class MerkleLogApplication {

    public static void main(String[] args) {
        SpringApplication.run(MerkleLogApplication.class, args);
    }
}
