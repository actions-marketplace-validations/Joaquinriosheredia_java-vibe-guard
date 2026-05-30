package com.example;

import org.springframework.kafka.annotation.KafkaListener;

public class KafkaTruePositive {
    @KafkaListener(topics = "orders") // BUG: missing groupId
    public void consume(String message) {
        System.out.println(message);
    }
}
