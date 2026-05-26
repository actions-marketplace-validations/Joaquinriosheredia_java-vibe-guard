package com.example;

import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

// BUG: each method below blocks the Tomcat thread pool carrier thread.
// Under 200 concurrent requests this will exhaust the pool and cause timeouts.

@RestController
public class ReactorBlockingTruePositive {

    private final UserService userService;

    public ReactorBlockingTruePositive(UserService userService) {
        this.userService = userService;
    }

    // VIBE-002: .block() in @RestController endpoint
    @GetMapping("/users/first")
    public String getFirstUser() {
        return userService.findFirst().block(); // BUG: pins thread for full I/O duration
    }

    // VIBE-002: .blockFirst() in @RestController method
    @GetMapping("/items")
    public String getFirstItem() {
        Flux<String> items = userService.findAll();
        return items.blockFirst(); // BUG: same pin, different operator
    }

    // VIBE-002: .blockLast() in @RestController method
    @GetMapping("/items/last")
    public String getLastItem() {
        return userService.findAll().blockLast(); // BUG
    }

    // VIBE-002: .toFuture().get() — reactive chain escaped to blocking Future
    @GetMapping("/users/sync")
    public String getUserSync() throws Exception {
        return userService.findFirst().toFuture().get(); // BUG: worst pattern — blocks + wraps exception
    }

    interface UserService {
        Mono<String> findFirst();
        Flux<String> findAll();
    }
}
