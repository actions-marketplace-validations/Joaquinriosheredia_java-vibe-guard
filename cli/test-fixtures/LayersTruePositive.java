package com.example;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
public class LayersTruePositive {
    @Autowired
    private UserRepository userRepository; // BUG: Controller injection of repository
    
    @GetMapping("/users")
    public Object getUsers() {
        return userRepository.findAll();
    }
}
