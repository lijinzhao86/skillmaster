package com.skillmasterai.modules.audit;

import com.skillmasterai.modules.audit.internal.PgAuditLog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Wires M10. */
@Configuration(proxyBeanMethods = false)
public class AuditConfiguration {

    /* **`@Primary` because both beans are the same class.** The counts edge is instantiated as
       `PgAuditLog` too, so once it is on the context a plain `AuditLog` injection would have two
       candidates; the write edge is the natural default, and the read edge is only ever asked for
       by its own interface. */
    @Bean
    @Primary
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
