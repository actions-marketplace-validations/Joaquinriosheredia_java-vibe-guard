package com.example;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * True-positive fixture: all methods below hold a DB connection open during
 * external I/O and MUST be flagged.
 */
@Service
public class ConnectionPoolStarvationTruePositive {

    private final RestTemplate restTemplate;

    public ConnectionPoolStarvationTruePositive(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // TRUE POSITIVE: @Transactional + Thread.sleep → CRITICAL
    // sleep() holds the connection for the full sleep duration
    @Transactional
    public void sleepInTransaction() throws InterruptedException {
        Thread.sleep(1000); // BUG: connection pinned for 1 second
    }

    // TRUE POSITIVE: @Transactional + RestTemplate HTTP call → CRITICAL
    // HTTP call can take seconds; connection is held until the call returns
    @Transactional
    public String httpCallInTransaction() {
        return restTemplate.getForObject("http://external-api.example.com/data", String.class); // BUG
    }

    // TRUE POSITIVE: @Transactional + RestTemplate.postForEntity → CRITICAL
    @Transactional
    public void postInTransaction(Object body) {
        restTemplate.postForEntity("http://api.example.com/create", body, Void.class); // BUG
    }

    // TRUE POSITIVE: @Transactional + RestTemplate.exchange → CRITICAL
    @Transactional
    public void exchangeInTransaction() {
        restTemplate.exchange("http://api.example.com", null, null, String.class); // BUG
    }

    // TRUE POSITIVE: @Transactional + WebClient.block() → CRITICAL
    // block() forces synchronous wait on a reactive call — connection held during network I/O
    @Transactional
    public String webClientBlockInTransaction(WebClient webClient) {
        return webClient.get()
            .uri("http://external.example.com/resource")
            .retrieve()
            .bodyToMono(String.class)
            .block(); // BUG: carrier and DB connection pinned
    }

    // TRUE POSITIVE: @Transactional + Files.readAllBytes → CRITICAL
    @Transactional
    public byte[] fileReadInTransaction() throws Exception {
        return Files.readAllBytes(Path.of("/tmp/large-report.bin")); // BUG
    }

    // TRUE POSITIVE: @Transactional + Files.readAllLines → CRITICAL
    @Transactional
    public void readLinesInTransaction() throws Exception {
        Files.readAllLines(Path.of("/tmp/data.csv")); // BUG
    }

    // TRUE POSITIVE: @Transactional + Files.readString → CRITICAL
    @Transactional
    public String readStringInTransaction() throws Exception {
        return Files.readString(Path.of("/tmp/config.json")); // BUG
    }

    // TRUE POSITIVE: @Transactional + Future.get() → CRITICAL
    @Transactional
    public String futureGetInTransaction(Future<String> future) throws Exception {
        return future.get(); // BUG: blocks until task completes, connection held
    }

    // TRUE POSITIVE: @Transactional + CompletableFuture.get() → CRITICAL
    @Transactional
    public String completableFutureGetInTransaction(CompletableFuture<String> completableFuture) throws Exception {
        return completableFuture.get(); // BUG
    }

    // TRUE POSITIVE: @Transactional + direct JDBC Statement → CRITICAL
    @Transactional
    public void directJdbcInTransaction(Statement statement) throws Exception {
        statement.executeUpdate("INSERT INTO log VALUES ('entry')"); // BUG: nested JDBC call
    }

    // TRUE POSITIVE: @Transactional + PreparedStatement → CRITICAL
    @Transactional
    public void preparedStatementInTransaction(PreparedStatement pstmt) throws Exception {
        pstmt.executeQuery(); // BUG
    }

    // TRUE POSITIVE: class-level @Transactional propagates to all methods
    // (tested via inline tests, not in this fixture — fixture uses method-level @Tx)
}
