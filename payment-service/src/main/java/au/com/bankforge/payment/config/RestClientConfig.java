package au.com.bankforge.payment.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Spring configuration for REST clients used by payment-service.
 *
 * The bean name "accountRestClient" avoids clashing with the AccountServiceClient @Component bean.
 * AccountServiceClient uses @Qualifier("accountRestClient") to inject this RestClient.
 *
 * Base URL is read from services.account.base-url (application.yml).
 * In Compose: http://account-service:8080
 * In tests:   http://localhost:{test-port} (overridden by DynamicPropertySource)
 *
 * ObservationRegistry is injected so RestClient creates OTel client spans and injects
 * W3C traceparent headers into outgoing requests — enabling cross-service trace propagation.
 */
@Configuration
public class RestClientConfig {

    @Bean("accountRestClient")
    public RestClient accountRestClient(
            @Value("${services.account.base-url}") String baseUrl,
            ObservationRegistry observationRegistry) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .observationRegistry(observationRegistry)
                .build();
    }
}
