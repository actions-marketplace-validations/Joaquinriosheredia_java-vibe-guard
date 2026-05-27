package com.example;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Future;

/**
 * True-positive fixture: every listener below contains a detectable hazard and MUST be flagged.
 */
@Component
public class KafkaRebalanceHazardTruePositive {

    private final RestTemplate restTemplate;

    public KafkaRebalanceHazardTruePositive(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // TRUE POSITIVE: no groupId attribute at all → CRITICAL (rebalance on every restart)
    @KafkaListener(topics = "orders")
    public void handleOrderNoGroup(String message) {
        System.out.println(message);
    }

    // TRUE POSITIVE: groupId explicitly empty string → CRITICAL
    @KafkaListener(topics = "payments", groupId = "")
    public void handlePaymentEmptyGroup(String message) {
        System.out.println(message);
    }

    // TRUE POSITIVE: multi-line annotation without groupId → CRITICAL
    @KafkaListener(
        topics = "invoices",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleInvoiceMultiLine(String message) {
        System.out.println(message);
    }

    // TRUE POSITIVE: valid groupId BUT Thread.sleep inside body → CRITICAL (blocking)
    @KafkaListener(topics = "notifications", groupId = "notification-group")
    public void handleNotificationWithSleep(String message) throws InterruptedException {
        Thread.sleep(2000); // BUG: stalls listener thread, risks exceeding max.poll.interval.ms
    }

    // TRUE POSITIVE: valid groupId BUT RestTemplate call inside body → CRITICAL (blocking)
    @KafkaListener(topics = "events", groupId = "event-processor")
    public void handleEventWithRestTemplate(String message) {
        restTemplate.postForEntity("http://downstream.example.com/events", message, Void.class); // BUG
    }

    // TRUE POSITIVE: valid groupId BUT RestTemplate.exchange → CRITICAL
    @KafkaListener(topics = "commands", groupId = "command-handler")
    public void handleCommandWithExchange(String message) {
        restTemplate.exchange("http://api.example.com/commands", null, null, String.class); // BUG
    }

    // TRUE POSITIVE: valid groupId BUT WebClient.block() → CRITICAL
    @KafkaListener(topics = "items", groupId = "item-processor")
    public void handleItemWithBlock(String message, WebClient webClient) {
        String result = webClient.post()
            .uri("http://enrichment.example.com/items")
            .retrieve()
            .bodyToMono(String.class)
            .block(); // BUG: forces synchronous wait inside listener thread
        System.out.println(result);
    }

    // TRUE POSITIVE: valid groupId BUT Files.readAllBytes → CRITICAL
    @KafkaListener(topics = "reports", groupId = "report-handler")
    public void handleReportWithFileRead(String filePath) throws Exception {
        byte[] data = Files.readAllBytes(Path.of(filePath)); // BUG: file I/O blocks listener
        System.out.println(data.length);
    }

    // TRUE POSITIVE: valid groupId BUT Future.get() → CRITICAL
    @KafkaListener(topics = "tasks", groupId = "task-executor")
    public void handleTaskWithFutureGet(String message, Future<String> taskFuture) throws Exception {
        String result = taskFuture.get(); // BUG: blocks until future completes
        System.out.println(result);
    }

    // TRUE POSITIVE: no groupId AND blocking call → two CRITICAL issues
    @KafkaListener(topics = "alerts")
    public void handleAlertNoGroupAndBlocking(String message) throws InterruptedException {
        Thread.sleep(500); // BUG: missing groupId + blocking
    }
}
