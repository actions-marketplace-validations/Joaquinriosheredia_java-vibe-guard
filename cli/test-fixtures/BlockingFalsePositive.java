package com.example;

import org.springframework.scheduling.annotation.Scheduled;
import java.util.Optional;

public class BlockingFalsePositive {
    @Scheduled(fixedDelay = 1000)
    public void runJob() {
        Optional<String> optional = Optional.of("hello");
        // Safe: This is Optional.get() which is not blocking (it throws exception if empty, but doesn't block thread execution pool)
        String value = optional.get(); 
        System.out.println(value);
    }
    
    // Comment explaining Blocking calls:
    // Avoid future.get() inside Scheduled tasks
}
