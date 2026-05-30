package com.example;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Async;

public class TransactionsFalsePositive {
    @Transactional
    public void save() {
        // Safe: Transactional without @Async
    }

    @Async
    public void send() {
        // Safe: Async without @Transactional
    }
}
