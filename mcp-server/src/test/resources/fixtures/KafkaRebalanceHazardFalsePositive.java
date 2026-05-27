package com.example;

import jakarta.annotation.PostConstruct;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.List;

/**
 * False-positive fixture: NONE of these patterns should be flagged.
 */
@Component
public class KafkaRebalanceHazardFalsePositive {

    private final RestTemplate restTemplate;

    public KafkaRebalanceHazardFalsePositive(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // SAFE: valid non-empty groupId → no missing-groupId CRITICAL
    @KafkaListener(topics = "orders", groupId = "order-processor")
    public void handleOrder(String message) {
        System.out.println(message);
    }

    // SAFE: multi-line annotation with valid groupId → no missing-groupId CRITICAL
    @KafkaListener(
        topics = "payments",
        groupId = "payment-processor",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handlePaymentMultiLine(String message) {
        System.out.println(message);
    }

    // SAFE: groupId as Spring property reference (non-empty literal) → not flagged
    @KafkaListener(topics = "invoices", groupId = "${kafka.consumer.group-id}")
    public void handleInvoiceWithProperty(String message) {
        System.out.println(message);
    }

    // SAFE: RestTemplate call OUTSIDE any @KafkaListener method → no tx context
    public String fetchExternalData(String url) {
        return restTemplate.getForObject(url, String.class);
    }

    // SAFE: Thread.sleep outside @KafkaListener → not flagged
    public void scheduledCleanup() throws InterruptedException {
        Thread.sleep(1000);
    }

    // SAFE: @Test annotation excludes the method even with @KafkaListener and blocking call
    @org.junit.jupiter.api.Test
    @KafkaListener(topics = "test-topic")
    public void testListener() throws InterruptedException {
        Thread.sleep(100);
    }

    // SAFE: @PostConstruct excluded even with blocking call
    @PostConstruct
    public void init() throws InterruptedException {
        Thread.sleep(10);
    }

    // SAFE: comment mentioning groupId inside a valid listener body → codeOnly() strips it
    @KafkaListener(topics = "notifications", groupId = "notification-group")
    public void handleNotification(String message) {
        // avoid groupId = "" — always set an explicit consumer group name
        System.out.println(message);
    }

    // SAFE: string literal containing a blocking pattern → codeOnly() strips string contents
    @KafkaListener(topics = "logs", groupId = "log-aggregator")
    public void handleLog(String message) {
        String hint = "do not call Thread.sleep() or restTemplate.getForObject() inside listeners";
        System.out.println(hint);
    }

    // SAFE: reactive listener returning Mono without calling .block() → not flagged
    @KafkaListener(topics = "items", groupId = "item-processor")
    public Mono<String> handleItemReactive(String message, WebClient webClient) {
        return webClient.post()
            .uri("http://processor.example.com")
            .retrieve()
            .bodyToMono(String.class); // returns Mono — caller decides blocking vs non-blocking
    }

    // SAFE: main() excluded even with @KafkaListener-like usage
    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(100);
    }

    // SAFE: no @KafkaListener at all — Future.get() outside listener context
    public String process(java.util.concurrent.Future<String> future) throws Exception {
        return future.get();
    }
}
