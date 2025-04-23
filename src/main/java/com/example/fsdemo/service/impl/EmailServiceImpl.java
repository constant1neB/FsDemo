package com.example.fsdemo.service.impl;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.service.EmailService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class EmailServiceImpl implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailServiceImpl.class);

    private final JavaMailSender mailSender;

    @Value("${spring.mail.properties.mail.from:noreply@example.com}")
    private String fromAddress;

    public EmailServiceImpl(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    @Async // Run in a background thread from AsyncConfig
    public void sendVerificationEmail(AppUser user, String token, String baseUrl) {
        String verificationUrl = baseUrl + "/verify-email?token=" + token;
        String subject = "Please Verify Your Email Address";
        String body = "<html><body>" +
                "<h2>Welcome to FsDemo!</h2>" +
                "<p>Please click the link below to verify your email address:</p>" +
                "<p><a href=\"" + verificationUrl + "\">Verify Email</a></p>" +
                "<p>If you didn't register for an account, please ignore this email.</p>" +
                "<p>This link will expire in 24 hours.</p>" + // Adjust if expiry changes
                "</body></html>";

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, "utf-8");

        try {
            helper.setTo(user.getEmail());
            helper.setSubject(subject);
            helper.setText(body, true); // true indicates HTML content
            helper.setFrom(fromAddress); // Set the sender address

            mailSender.send(message);
            log.info("Verification email sent successfully to {}", user.getEmail());

        } catch (MessagingException | MailException e) {
            log.error("Failed to send verification email to {}: {}", user.getEmail(), e.getMessage(), e);
            // Consider adding retry logic or notifying admins
        }
    }
}