package com.example.fsdemo.web;

import com.example.fsdemo.domain.AccountCredentials;
import com.example.fsdemo.service.JwtService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoginController {
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    public LoginController(JwtService jwtService, AuthenticationManager authenticationManager) {
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
    }

    //Generate token and send it in the response auth header
    @PostMapping("/login")
    public ResponseEntity<?> getToken(@RequestBody AccountCredentials creds) {
        UsernamePasswordAuthenticationToken credentials = new UsernamePasswordAuthenticationToken(creds.username(), creds.password());
        Authentication authentication = authenticationManager.authenticate(credentials);

        //Generate token
        String jwts = jwtService.getToken(authentication.getName());

        //Respond with the generated token
        return ResponseEntity.ok()
                .header(HttpHeaders.AUTHORIZATION, "Bearer" + jwts)
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Authorization")
                .build();
    }

}
