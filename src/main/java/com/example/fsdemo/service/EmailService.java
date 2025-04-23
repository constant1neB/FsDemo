package com.example.fsdemo.service;

import com.example.fsdemo.domain.AppUser;

public interface EmailService {
    void sendVerificationEmail(AppUser user, String token, String baseUrl);
}