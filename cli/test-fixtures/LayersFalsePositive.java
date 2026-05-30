package com.example;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

@RestController
public class LayersFalsePositive {
    // Comment with Repository word:
    // This is safe because we use UserService, not UserRepository
    private final UserService userService;
    
    public LayersFalsePositive(UserService userService) {
        this.userService = userService;
    }
    
    @GetMapping("/users")
    public Object getUsers() {
        // Safe: local variable with similar name but not a field/constructor repository dependency
        String fakeRepositoryName = "mockRepository"; 
        return userService.getAll();
    }
}
