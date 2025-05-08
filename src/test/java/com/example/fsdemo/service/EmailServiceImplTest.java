package com.example.fsdemo.service;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.service.impl.EmailServiceImpl;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailServiceImpl Tests")
class EmailServiceImplTest {

    @Mock
    private JavaMailSender mailSender;

    @InjectMocks
    private EmailServiceImpl emailService;

    @Captor
    private ArgumentCaptor<MimeMessage> mimeMessageCaptor;

    private AppUser testUser;
    private final String testToken = "test-verification-token-123";
    private final String testBaseUrl = "http://localhost:8080";
    private final String fromAddress = "noreply@test.com";

    @BeforeEach
    void setUp() {
        testUser = new AppUser("tester", "pass", "USER", "recipient@example.com");
        testUser.setId(1L);

        ReflectionTestUtils.setField(emailService, "fromAddress", fromAddress);

        MimeMessage mimeMessage = new MimeMessage((Session) null);
        given(mailSender.createMimeMessage()).willReturn(mimeMessage);
    }

    @Test
    @DisplayName("✅ sendVerificationEmail should construct and send correct email")
    void sendVerificationEmail_Success() throws MessagingException, IOException {
        doNothing().when(mailSender).send(any(MimeMessage.class));
        String expectedVerificationUrl = testBaseUrl + "/api/auth/verify-email?token=" + testToken;

        emailService.sendVerificationEmail(testUser, testToken, testBaseUrl);

        then(mailSender).should().send(mimeMessageCaptor.capture());
        MimeMessage sentMessage = mimeMessageCaptor.getValue();

        assertThat(sentMessage.getAllRecipients()).hasSize(1);
        assertThat(sentMessage.getAllRecipients()[0]).hasToString(testUser.getEmail());
        assertThat(sentMessage.getSubject()).isEqualTo("Please Verify Your Email Address");
        assertThat(sentMessage.getFrom()[0]).hasToString(fromAddress);

        String content = (String) sentMessage.getContent();
        assertThat(content)
                .contains("<h2>Welcome to FsDemo!</h2>")
                .contains("<a href=\"" + expectedVerificationUrl + "\">Verify Email</a>")
                .contains("This link will expire in 24 hours.");
    }

    @Test
    @DisplayName("❌ sendVerificationEmail should log error if sending fails (MailException)")
    void sendVerificationEmail_FailureMailException() {
        // Arrange
        doThrow(new MailSendException("Failed to connect to mail server"))
                .when(mailSender).send(any(MimeMessage.class));

        emailService.sendVerificationEmail(testUser, testToken, testBaseUrl);

        then(mailSender).should().send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("❌ sendVerificationEmail should log error if message setup fails (Simulated MailException)")
    void sendVerificationEmail_FailureSetupSimulatedAsMailException() {
        doThrow(new MailSendException("Simulated setup failure: Invalid address"))
                .when(mailSender).send(any(MimeMessage.class));

        emailService.sendVerificationEmail(testUser, testToken, testBaseUrl);

        then(mailSender).should().send(any(MimeMessage.class));
    }
}