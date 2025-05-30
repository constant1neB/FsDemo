package com.example.fsdemo.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI fsDemoOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("FsDemo REST API")
                        .description("Demo fullstack webapp")
                        .version("1.0"));
    }
}
