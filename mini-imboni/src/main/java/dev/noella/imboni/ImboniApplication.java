package dev.noella.imboni;

import dev.noella.imboni.config.ImboniProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * By default the database auto-configuration is excluded (application.yml) and in-memory stores are
 * used, so the app runs without Postgres. The "pg" profile (application-pg.yml) turns it back on
 * for PostgreSQL + pgvector.
 */
@SpringBootApplication
@EnableConfigurationProperties(ImboniProperties.class)
public class ImboniApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImboniApplication.class, args);
    }
}
