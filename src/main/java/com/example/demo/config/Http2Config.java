package com.example.demo.config;

import org.apache.coyote.http2.Http2Protocol;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enables HTTP/2 cleartext (h2c) upgrade on the embedded Tomcat connector, without TLS.
 * Matches the gRPC server's plaintext HTTP/2 (h2c) transport, so both APIs can be driven
 * by a persistent, multiplexed, connection-reusing client on equal terms.
 */
@Configuration
public class Http2Config {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> h2cCustomizer() {
        return factory -> factory.addConnectorCustomizers(
                connector -> connector.addUpgradeProtocol(new Http2Protocol()));
    }
}
