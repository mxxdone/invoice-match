package com.invoicematch.core.payment.adapter;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(PaymentExportProperties.class)
public class PaymentExportConfiguration {

    @Bean
    public HttpClient paymentExportHttpClient(PaymentExportProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.relay().connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
