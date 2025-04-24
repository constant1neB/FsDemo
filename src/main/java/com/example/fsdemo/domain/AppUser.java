package com.example.fsdemo.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

@Entity
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false, updatable = false)
    private Long id;

    @NotBlank(message = "Username cannot be blank")
    @Size(min = 3, max = 20, message = "Username size must be between 3 and 20 characters")
    @Pattern(regexp = "^\\w+$", message = "Username can only contain letters, numbers, and underscores")
    @Column(nullable = false, unique = true)
    private String username;

    @NotBlank(message = "Password cannot be blank")
    @Column(nullable = false)
    private String password;

    @NotBlank(message = "Role cannot be blank")
    @Column(nullable = false)
    private String role;

    @NotBlank(message = "Email cannot be blank")
    @Email(message = "Email must be a well-formed email address")
    @Column(unique = true)
    private String email;

    @Column(nullable = false)
    private boolean verified = false;

    @Column(unique = true, length = 64)
    private String verificationTokenHash;

    @Column
    private Instant verificationTokenExpiryDate;

    // --- Constructors ---
    public AppUser() {
    }

    public AppUser(String username, String password, String role, String email) {
        this.username = username;
        this.password = password;
        this.role = role;
        this.email = email;
        this.verified = false;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }


    public boolean isVerified() { // Getter for boolean often starts with "is"
        return verified;
    }

    public void setVerified(boolean verified) { // Setter for verified
        this.verified = verified;
    }

    public String getVerificationTokenHash() {
        return verificationTokenHash;
    }

    public void setVerificationTokenHash(String verificationTokenHash) {
        this.verificationTokenHash = verificationTokenHash;
    }

    public Instant getVerificationTokenExpiryDate() { // Getter for expiry
        return verificationTokenExpiryDate;
    }

    public void setVerificationTokenExpiryDate(Instant verificationTokenExpiryDate) { // Setter for expiry
        this.verificationTokenExpiryDate = verificationTokenExpiryDate;
    }
}