package com.example;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;

public class KafkaFalsePositive {
    @KafkaListener(topics = "orders", groupId = "order-group") // Safe: has groupId and RetryableTopic
    @RetryableTopic(attempts = "3")
    public void consume(String message) {
        System.out.println(message);
    }
}
