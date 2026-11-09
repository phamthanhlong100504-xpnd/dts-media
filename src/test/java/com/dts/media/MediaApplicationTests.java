package com.dts.media;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring Boot smoke test against a disposable PostgreSQL, never the Media
 * database configured in the application's environment or application.yml.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
        "spring.flyway.validate-on-migrate=true",
        "spring.flyway.baseline-on-migrate=false"
})
@Testcontainers
class MediaApplicationTests {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void isolatedPostgres(DynamicPropertyRegistry properties) {
        // Both Hibernate/JDBC and Flyway must resolve to the same disposable DB.
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("spring.flyway.url", postgres::getJdbcUrl);
        properties.add("spring.flyway.user", postgres::getUsername);
        properties.add("spring.flyway.password", postgres::getPassword);
        properties.add("spring.flyway.locations", () -> "classpath:db/migration");
        properties.add("spring.flyway.target", () -> "7");
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Test
    void contextLoads() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            // Ensure a future config regression cannot send this test to live Media DB.
            String expected = postgres.getJdbcUrl().split("\\?", 2)[0];
            assertTrue(connection.getMetaData().getURL().startsWith(expected),
                    "Media context must use its disposable Testcontainers database");
        }
        assertNotNull(flyway.info().current(), "Media Flyway migration must be installed in test DB");
        assertEquals("7", flyway.info().current().getVersion().getVersion());
    }
}
