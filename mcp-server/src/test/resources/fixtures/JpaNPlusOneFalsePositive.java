package com.example;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * False-positive fixture: NONE of these patterns should be flagged.
 * Every .save / .findById / .delete here is in a safe context.
 */
@Service
public class JpaNPlusOneFalsePositive {

    private final OrderRepository orderRepository;

    public JpaNPlusOneFalsePositive(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    // SAFE: saveAll is a batch operation — one SQL statement
    public void persistBatch(List<Order> orders) {
        orderRepository.saveAll(orders);
    }

    // SAFE: findAllById is a batch operation — one IN(...) query
    public List<Order> loadBatch(List<Long> ids) {
        return orderRepository.findAllById(ids);
    }

    // SAFE: deleteAllInBatch — one bulk DELETE
    public void deleteBatch(List<Order> orders) {
        orderRepository.deleteAllInBatch(orders);
    }

    // SAFE: deleteAllById — one bulk DELETE
    public void deleteByIds(List<Long> ids) {
        orderRepository.deleteAllById(ids);
    }

    // SAFE: repository call with no iteration — single query, no loop
    public Order fetchOne(Long id) {
        return orderRepository.findById(id);
    }

    // SAFE: @PostConstruct — startup context, not a request handler
    @PostConstruct
    public void preload() {
        List<Long> ids = List.of(1L, 2L, 3L);
        orderRepository.findAllById(ids);
    }

    // SAFE: main() — entry point, not service logic
    public static void main(String[] args) {
        System.out.println("started");
    }

    // SAFE: @Test — assertions need concrete values
    @org.junit.jupiter.api.Test
    public void testFindById() {
        for (Long id : List.of(1L, 2L, 3L)) {
            orderRepository.findById(id); // inside @Test, never flagged
        }
    }

    // SAFE: comment with findById must not be matched
    public void documentedMethod() {
        // orderRepository.findById(id) would cause N+1 — use findAllById instead
        orderRepository.findAllById(List.of(1L, 2L));
    }

    // SAFE: "findById" inside a string literal must not be matched
    public void logWarning() {
        String msg = "avoid orderRepository.findById(id) inside loops";
        System.out.println(msg);
    }

    // SAFE: loop over plain strings — variable does not look like a repository
    public void transformItems(List<String> items) {
        for (String item : items) {
            String result = item.toLowerCase();
        }
    }

    // SAFE: stream over already-materialized list — no JPA access inside lambda
    public List<String> getNames(List<Order> orders) {
        return orders.stream()
            .map(o -> o.name())
            .toList();
    }

    // SAFE: non-repo variable with similar method name (not Repository/Repo suffix)
    public void transform(List<String> items) {
        for (String item : items) {
            String out = mapper.findById(item); // 'mapper' has no Repo suffix
        }
    }

    interface OrderRepository {
        Order findById(Long id);
        List<Order> saveAll(List<Order> o);
        List<Order> findAllById(List<Long> ids);
        void deleteAllInBatch(List<Order> orders);
        void deleteAllById(List<Long> ids);
    }
    static class Order { public String name() { return ""; } }
    static Object mapper = new Object() { public String findById(String s) { return s; } };
}
