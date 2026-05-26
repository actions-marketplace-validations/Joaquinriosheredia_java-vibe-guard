package com.example;

import jakarta.annotation.PostConstruct;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

// All .block() calls in this file are in safe contexts — none should be flagged.

@RestController
public class ReactorBlockingFalsePositive {

    private String cachedConfig;

    // SAFE: @PostConstruct runs once during startup, blocking is acceptable
    @PostConstruct
    public void initialize() {
        cachedConfig = Mono.just("config-value").block();
    }

    // SAFE: @Test method — assertions need a concrete value
    @org.junit.jupiter.api.Test
    public void testReactiveChain() {
        String result = Mono.just("expected").block();
        assert result != null;
    }

    // SAFE: main() is a startup entry point, not a request handler
    public static void main(String[] args) {
        String value = Mono.just("startup").block();
    }

    // SAFE: variable named "block" is not a reactive method call (no leading dot before "block")
    public void processQueue() {
        boolean block = false;
        int blockSize = 100;
        if (block) {
            System.out.println("blocking mode active, blockSize=" + blockSize);
        }
    }

    // SAFE: returns Mono without blocking — comment below must not trigger a false positive
    public Mono<String> getUser(String id) {
        // deliberately avoided .block() here — returning the Mono directly
        return Mono.just("user-" + id);
    }
}
