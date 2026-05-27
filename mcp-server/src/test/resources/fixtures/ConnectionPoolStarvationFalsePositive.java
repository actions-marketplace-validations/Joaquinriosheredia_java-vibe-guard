package com.example;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import java.util.List;

/**
 * False-positive fixture: NONE of these patterns should be flagged.
 */
@Service
public class ConnectionPoolStarvationFalsePositive {

    private final UserRepository userRepository;

    public ConnectionPoolStarvationFalsePositive(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // SAFE: RestTemplate call outside @Transactional — no connection held
    public String fetchData(RestTemplate restTemplate) {
        return restTemplate.getForObject("http://example.com/data", String.class);
    }

    // SAFE: @Transactional(readOnly=true) without external I/O — read-only queries only
    @Transactional(readOnly = true)
    public User findUser(Long id) {
        return userRepository.findById(id);
    }

    // SAFE: @Transactional(readOnly=true) batch fetch — no blocking external call
    @Transactional(readOnly = true)
    public List<User> findAll() {
        return userRepository.findAll();
    }

    // SAFE: repository.save inside @Transactional — standard JPA op, no external I/O
    @Transactional
    public void saveUser(User user) {
        userRepository.save(user);
    }

    // SAFE: multiple repository ops inside @Transactional — no external I/O
    @Transactional
    public void transfer(Long fromId, Long toId) {
        User from = userRepository.findById(fromId);
        User to   = userRepository.findById(toId);
        userRepository.save(from);
        userRepository.save(to);
    }

    // SAFE: comment with "restTemplate.getForObject()" — stripped by codeOnly()
    @Transactional
    public void documentedMethod() {
        // avoid restTemplate.getForObject() inside @Transactional — use a non-tx wrapper
        userRepository.save(new User());
    }

    // SAFE: string literal with blocking pattern — stripped by codeOnly()
    @Transactional
    public void logWarning() {
        String hint = "do not call restTemplate.exchange() or Thread.sleep() inside @Transactional";
        userRepository.save(new User());
    }

    // SAFE: @Test with Thread.sleep and @Transactional — @Test excludes the method
    @org.junit.jupiter.api.Test
    @Transactional
    public void testSleep() throws InterruptedException {
        Thread.sleep(100);
    }

    // SAFE: @PostConstruct — excluded even with @Transactional
    @PostConstruct
    @Transactional
    public void init() {
        userRepository.findAll();
    }

    // SAFE: main() — excluded even with @Transactional
    @Transactional
    public static void main(String[] args) {
        System.out.println("started");
    }

    // SAFE: no @Transactional at all — Thread.sleep not flagged without tx context
    public void sleepOutsideTransaction() throws InterruptedException {
        Thread.sleep(500);
    }

    // SAFE: processLocally() — not a recognized blocking pattern
    @Transactional
    public void localProcessing(List<User> users) {
        users.forEach(u -> processLocally(u));
    }

    private void processLocally(User u) {}

    interface UserRepository {
        User findById(Long id);
        List<User> findAll();
        void save(User u);
    }

    static class User {}
}
