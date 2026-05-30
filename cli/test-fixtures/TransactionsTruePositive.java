package com.example;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Async;

public class TransactionsTruePositive {
    @Transactional
    @Async
    public void saveAndSend() {
        // BUG: @Transactional + @Async on same method boundary
    }
}
