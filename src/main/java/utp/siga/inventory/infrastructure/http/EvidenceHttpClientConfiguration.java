package utp.siga.inventory.infrastructure.http;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class EvidenceHttpClientConfiguration {
    @Bean("evidenceRestClient")
    RestClient evidenceRestClient(RestClient.Builder builder,
            @Value("${inventory.evidence.base-url}") String baseUrl,
            @Value("${inventory.evidence.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${inventory.evidence.read-timeout-ms}") long readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(positiveDuration(connectTimeoutMs, "connect timeout"));
        requestFactory.setReadTimeout(positiveDuration(readTimeoutMs, "read timeout"));
        return builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    private static Duration positiveDuration(long milliseconds, String setting) {
        if (milliseconds <= 0) {
            throw new IllegalArgumentException("Evidence " + setting + " must be positive");
        }
        return Duration.ofMillis(milliseconds);
    }
}
