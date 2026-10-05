package com.parvez.android.config;

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DocumentTrafficConfiguration {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> deferUploadContinueResponse() {
        // Let security and upload admission reject headers before asking clients to send PDF bytes.
        return factory -> factory.addConnectorCustomizers(connector -> connector.setProperty("continueResponseTiming", "onRead"));
    }
}
