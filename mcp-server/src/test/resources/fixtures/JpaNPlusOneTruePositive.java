package com.example;

import org.springframework.stereotype.Service;
import java.util.List;

/**
 * True-positive fixture: all methods below contain real N+1 patterns and MUST be flagged.
 */
@Service
public class JpaNPlusOneTruePositive {

    private final OrderRepository orderRepository;
    private final UserRepo userRepo;

    public JpaNPlusOneTruePositive(OrderRepository orderRepository, UserRepo userRepo) {
        this.orderRepository = orderRepository;
        this.userRepo = userRepo;
    }

    // N+1: findById inside enhanced for — one SELECT per iteration
    public void processOrders(List<Long> orderIds) {
        for (Long id : orderIds) {
            Order order = orderRepository.findById(id); // BUG: N+1
            process(order);
        }
    }

    // N+1: save inside while loop — one INSERT/UPDATE per iteration
    public void saveOrdersOneByOne(List<Order> orders) {
        int i = 0;
        while (i < orders.size()) {
            orderRepository.save(orders.get(i)); // BUG: N+1 — use saveAll instead
            i++;
        }
    }

    // N+1: deleteById inside classic for loop
    public void deleteOrders(List<Long> ids) {
        for (int i = 0; i < ids.size(); i++) {
            orderRepository.deleteById(ids.get(i)); // BUG: N+1
        }
    }

    // N+1: custom findBy* inside for loop — one query per user
    public void enrichUsers(List<String> emails) {
        for (String email : emails) {
            User u = userRepo.findByEmail(email); // BUG: N+1
            enrich(u);
        }
    }

    // N+1: findById in stream().map — one SELECT per element
    public List<Order> loadOrders(List<Long> ids) {
        return ids.stream()
            .map(id -> orderRepository.findById(id)) // BUG: N+1
            .toList();
    }

    // N+1: save in stream().forEach — one INSERT per element
    public void persistAll(List<Order> orders) {
        orders.stream()
            .forEach(o -> orderRepository.save(o)); // BUG: N+1 — use saveAll
    }

    // N+1: lazy collection access inside loop — triggers SELECT per user
    public int countAllUserOrders(List<User> users) {
        int total = 0;
        for (User user : users) {
            total += user.getOrders().size(); // BUG: N+1 lazy load
        }
        return total;
    }

    // N+1: exists check inside loop — one SELECT per element
    public void validateAll(List<Long> ids) {
        for (Long id : ids) {
            boolean exists = orderRepository.existsById(id); // BUG: N+1
        }
    }

    private void process(Object o) {}
    private void enrich(Object o) {}
    interface OrderRepository {
        Order findById(Long id);
        void save(Order o);
        void deleteById(Long id);
        boolean existsById(Long id);
        List<Order> saveAll(List<Order> o);
        List<Order> findAllById(List<Long> ids);
    }
    interface UserRepo {
        User findByEmail(String email);
    }
    static class Order {}
    static class User {
        public List<Order> getOrders() { return List.of(); }
    }
}
