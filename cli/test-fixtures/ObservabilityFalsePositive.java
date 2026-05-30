package com.example;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
public class ObservabilityFalsePositive {
    private static final Logger log = LoggerFactory.getLogger(ObservabilityFalsePositive.class);

    @GetMapping("/hello")
    public String hello() {
        log.info("Request received for hello"); // Safe: has structured logging
        return "hello";
    }
}
