package com.example.demo.cdc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
public class DebeziumConnectorRegistrar implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(DebeziumConnectorRegistrar.class);

    private static final String CONNECTOR_NAME = "items-cdc-connector";

    @Value("${app.debezium.kafka-connect-url:}")
    private String kafkaConnectUrl;

    @Value("${spring.datasource.url:jdbc:postgresql://db:5432/mydb}")
    private String datasourceUrl;

    @Value("${spring.datasource.username:user}")
    private String dbUser;

    @Value("${spring.datasource.password:password}")
    private String dbPassword;

    // Override the DB hostname sent to the Debezium connector. Required when the Spring
    // app connects to Postgres via a host port-mapping (e.g. localhost:5432 in CI) while
    // Kafka Connect is inside Docker and must use the service name (e.g. db:5432).
    @Value("${app.debezium.db-hostname:}")
    private String debeziumDbHostname;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (kafkaConnectUrl == null || kafkaConnectUrl.isBlank()) {
            log.info("app.debezium.kafka-connect-url not set — skipping connector registration");
            return;
        }

        String statusUrl = kafkaConnectUrl + "/connectors/" + CONNECTOR_NAME;
        try {
            restTemplate.getForEntity(statusUrl, String.class);
            log.info("Debezium connector '{}' already registered — skipping", CONNECTOR_NAME);
            return;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() != HttpStatus.NOT_FOUND) {
                log.warn("Unexpected response checking connector status: {}", e.getMessage());
                return;
            }
        }

        // Parse host/port/dbname from JDBC URL (jdbc:postgresql://host:port/db)
        String[] parts   = datasourceUrl.replace("jdbc:postgresql://", "").split("/");
        String   hostPort = parts[0];
        String   dbName   = parts.length > 1 ? parts[1] : "mydb";
        String[] hp       = hostPort.split(":");
        String   host     = debeziumDbHostname.isBlank() ? hp[0] : debeziumDbHostname;
        String   port     = hp.length > 1 ? hp[1] : "5432";

        Map<String, Object> config = Map.of(
                "connector.class",    "io.debezium.connector.postgresql.PostgresConnector",
                "database.hostname",  host,
                "database.port",      port,
                "database.user",      dbUser,
                "database.password",  dbPassword,
                "database.dbname",    dbName,
                "topic.prefix",       dbName,
                "plugin.name",        "pgoutput",
                "table.include.list", "public.items",
                "snapshot.mode",      "initial"
        );

        Map<String, Object> body = Map.of("name", CONNECTOR_NAME, "config", config);
        try {
            restTemplate.postForEntity(kafkaConnectUrl + "/connectors", body, String.class);
            log.info("Debezium connector '{}' registered successfully", CONNECTOR_NAME);
        } catch (Exception e) {
            log.error("Failed to register Debezium connector: {}", e.getMessage());
        }
    }
}
