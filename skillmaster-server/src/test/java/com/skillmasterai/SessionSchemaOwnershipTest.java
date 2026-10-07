package com.skillmasterai;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.CleanMigrateFlyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionDataSourceScriptDatabaseInitializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Flyway is the only thing that builds this schema, and the session tables are no exception.
 *
 * <p>Adding {@code spring-boot-starter-session-jdbc} alone arms Boot's own script initializer, which
 * creates {@code SPRING_SESSION} from the jar. That would mean two things building one schema: at
 * best they race, at worst the loser fails on tables that already exist. The switch that disarms it
 * is {@code spring.session.jdbc.initialize-schema: never}, and it is a property — nothing fails to
 * compile and nothing else in this suite would notice if it went missing.
 *
 * <p><strong>This asserts the bean's absence rather than its behaviour, deliberately.</strong> A
 * behavioural test cannot see the difference here: the test Flyway runs {@code clean()} before
 * {@code migrate()}, so a second initializer's tables are dropped and recreated without a symptom.
 * That is exactly how this went unnoticed the first time it was checked.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(CleanMigrateFlyway.class)
class SessionSchemaOwnershipTest {

    @Autowired(required = false)
    private JdbcSessionDataSourceScriptDatabaseInitializer bootSessionSchemaInitializer;

    @Test
    void bootIsNotAllowedToCreateTheSessionTables() {
        assertThat(bootSessionSchemaInitializer)
                .as("spring.session.jdbc.initialize-schema must stay `never`; V3 creates these tables")
                .isNull();
    }
}
