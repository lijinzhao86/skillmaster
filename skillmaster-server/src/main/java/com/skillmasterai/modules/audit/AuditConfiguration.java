package com.skillmasterai.modules.audit;

import com.skillmasterai.modules.audit.internal.PgAuditLog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Wires M10. */
@Configuration(proxyBeanMethods = false)
public class AuditConfiguration {

    @Bean
    AuditLog auditLog(JdbcClient jdbc, ObjectMapper objectMapper) {
        return new PgAuditLog(jdbc, objectMapper);
    }

    @Bean
    AuditCounts auditCounts(JdbcClient jdbc, ObjectMapper objectMapper) {
        // A second instance of the same class: one bean per published edge keeps injection
        // unambiguous, exactly as M15 wires PgUsage.
        return new PgAuditLog(jdbc, objectMapper);
    }
}
