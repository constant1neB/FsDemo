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
    private final String testBaseUrl = "http://localhost:8080/api/auth";
    private final String fromAddress = "noreply@test.com";

    @BeforeEach
    void setUp() {
        testUser = new AppUser("tester", "pass", "USER", "recipient@example.com");
        testUser.setId(1L);

        // Inject the 'fromAddress' using reflection
        ReflectionTestUtils.setField(emailService, "fromAddress", fromAddress);

        // Mock createMimeMessage() to return a real MimeMessage (or a mock if needed)
        // Using a real one allows checking properties set via MimeMessageHelper
        MimeMessage mimeMessage = new MimeMessage((Session) null);
        given(mailSender.createMimeMessage()).willReturn(mimeMessage);
    }

    @Test
    @DisplayName("✅ sendVerificationEmail should construct and send correct email")
    void sendVerificationEmail_Success() throws MessagingException, IOException {
        // Arrange
        doNothing().when(mailSender).send(any(MimeMessage.class));
        String expectedVerificationUrl = testBaseUrl + "/verify-email?token=" + testToken;

        // Act
        emailService.sendVerificationEmail(testUser, testToken, testBaseUrl);

        // Assert
        // Verify mailSender.send was called once with the captured MimeMessage
        then(mailSender).should().send(mimeMessageCaptor.capture());
        MimeMessage sentMessage = mimeMessageCaptor.getValue();

        // Verify email properties
        assertThat(sentMessage.getAllRecipients()).hasSize(1);
        assertThat(sentMessage.getAllRecipients()[0]).hasToString(testUser.getEmail());
        assertThat(sentMessage.getSubject()).isEqualTo("Please Verify Your Email Address");
        assertThat(sentMessage.getFrom()[0]).hasToString(fromAddress);

        // Check content (basic check for key elements)
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

        // Act
        // Since it's @Async, the exception is caught by the AsyncUncaughtExceptionHandler
        // We test that send was called, and assume the handler logs it (can't easily verify log output here)
        emailService.sendVerificationEmail(testUser, testToken, testBaseUrl);

        // Assert
        // Verify send was attempted
        then(mailSender).should().send(any(MimeMessage.class));
        // We expect the exception to be logged by the async handler, not rethrown here.
    }

    @Test
    @DisplayName("❌ sendVerificationEmail should log error if message setup fails (Simulated MailException)")
    void sendVerificationEmail_FailureSetupSimulatedAsMailException() {
        // Arrange
        // Simulate a setup failure by throwing an unchecked MailException during send
        doThrow(new MailSendException("Simulated setup failure: Invalid address"))
                .when(mailSender).send(any(MimeMessage.class));

        // Act
        emailService.sendVerificationEmail(testUser, testToken, testBaseUrl);

        // Assert
        then(mailSender).should().send(any(MimeMessage.class));
        // Logging is handled by AsyncUncaughtExceptionHandler, verification stops here.
    }
}