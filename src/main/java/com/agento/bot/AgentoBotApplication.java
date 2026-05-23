package com.agento.bot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
public class AgentoBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentoBotApplication.class, args);
    }

    @Bean
    RestClient.Builder restClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(35));

        return RestClient.builder()
                .requestFactory(requestFactory);
    }
}
