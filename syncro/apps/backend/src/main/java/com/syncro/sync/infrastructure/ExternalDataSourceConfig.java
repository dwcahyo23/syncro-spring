package com.syncro.sync.infrastructure;

import com.syncro.config.SyncProperties;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Secondary {@link JdbcTemplate} for the external PostgreSQL (the reference system's
 * {@code sch_ot.mow_mtn_appm} table). Only created when {@code syncro.sync.enabled=true}
 * — the app boots without an external DB.
 *
 * <p>The {@code DataSource} is built inline and deliberately NOT registered as a Spring
 * bean: defining a user {@code DataSource} bean would make Spring Boot's
 * {@code DataSourceAutoConfiguration} back off, silently dropping the auto-configured
 * primary datasource that JPA and Flyway depend on. The {@code JdbcTemplate} is the only
 * bean exposed; the read-only external connection is private to it.
 */
@Configuration
@ConditionalOnProperty(prefix = "syncro.sync", name = "enabled", havingValue = "true")
public class ExternalDataSourceConfig {

  @Bean
  JdbcTemplate syncJdbcTemplate(SyncProperties properties) {
    var ds = properties.datasource();
    var dataSource = DataSourceBuilder.create()
        .url(ds.url())
        .username(ds.username())
        .password(ds.password())
        .driverClassName("org.postgresql.Driver")
        .build();
    return new JdbcTemplate(dataSource);
  }
}