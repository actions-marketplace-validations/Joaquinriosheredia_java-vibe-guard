package com.example;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

@RestController
public class ObservabilityTruePositive {
    @GetMapping("/hello")
    public String hello() {
        // BUG: Endpoint method with no logs
        return "hello";
    }
}
