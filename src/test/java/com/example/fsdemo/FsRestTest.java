package com.example.fsdemo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.http.HttpHeaders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FsRestTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    public void testAuthentication() throws Exception {
        this.mockMvc
                .perform(post("/login")
                .content("{\"username\":\"user1\", \"password\":\"password\"}")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")).andDo(print()).andExpect(status().isOk());
    }
}
