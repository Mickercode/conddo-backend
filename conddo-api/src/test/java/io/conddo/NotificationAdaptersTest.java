package io.conddo;

import io.conddo.core.notify.BrevoSmsSender;
import io.conddo.core.notify.NotificationProperties;
import io.conddo.core.notify.ResendEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies the real notification adapters emit the exact HTTP request each
 * provider documents — without live credentials or network — by binding a
 * {@link MockRestServiceServer} to the injected {@code RestClient.Builder}.
 * Guards against the "unverified against the live API" risk noted on the senders.
 */
class NotificationAdaptersTest {

    private static NotificationProperties props(String emailFrom) {
        return new NotificationProperties(
                new NotificationProperties.Sms("brevo", "https://api.brevo.com", "sms-key-123", "Conddo"),
                new NotificationProperties.Email("resend", "https://api.resend.com", "re_test_key",
                        emailFrom, "Conddo", null));
    }

    @Test
    void resendEmailSenderPostsTheDocumentedRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ResendEmailSender sender = new ResendEmailSender(props("no-reply@conddo.io"), builder);

        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer re_test_key"))
                .andExpect(jsonPath("$.from").value("no-reply@conddo.io"))
                .andExpect(jsonPath("$.to[0]").value("amaka@biz.test"))
                .andExpect(jsonPath("$.subject").value("Your Conddo verification code"))
                .andExpect(jsonPath("$.text").value(containsString("1234")))
                .andRespond(withSuccess("{\"id\":\"e_1\"}", MediaType.APPLICATION_JSON));

        sender.send("amaka@biz.test", "Your Conddo verification code", "Your Conddo verification code is 1234.");
        server.verify();
    }

    @Test
    void brevoSmsSenderPostsTheDocumentedRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BrevoSmsSender sender = new BrevoSmsSender(props("no-reply@conddo.io"), builder);

        server.expect(requestTo("https://api.brevo.com/v3/transactionalSMS/sms"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "sms-key-123"))
                .andExpect(jsonPath("$.type").value("transactional"))
                .andExpect(jsonPath("$.sender").value("Conddo"))
                .andExpect(jsonPath("$.recipient").value("+2348030000001"))
                .andExpect(jsonPath("$.content").value("Your code is 9999"))
                .andRespond(withSuccess("{\"messageId\":1}", MediaType.APPLICATION_JSON));

        sender.send("+2348030000001", "Your code is 9999");
        server.verify();
    }
}
