package au.com.bankforge.payment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Spring configuration for REST clients used by payment-service.
 *
 * The bean name "accountRestClient" avoids clashing with the AccountServiceClient @Component bean.
 * AccountServiceClient uses @Qualifier("accountRestClient") to inject this RestClient.
 *
 * Base URL is read from services.account.base-url (application.yml).
 * In Compose: http://account-service:8080
 * In tests:   http://localhost:{test-port} (overridden by DynamicPropertySource)
 */
@Configuration
public class RestClientConfig {

    @Bean("accountRestClient")
    public RestClient accountRestClient(
            @Value("${services.account.base-url}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
