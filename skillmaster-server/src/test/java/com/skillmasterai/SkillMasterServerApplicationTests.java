package com.skillmasterai;

import com.skillmasterai.support.CleanMigrateFlyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The context now needs a live PostgreSQL: the datasource and Flyway are real dependencies,
 * not optional ones. Requires scripts/init-test-db.sh to have been run.
 */
@SpringBootTest(properties = "skillmaster.retrieval.enabled=false")
@ActiveProfiles("test")
@Import(CleanMigrateFlyway.class)
class SkillMasterServerApplicationTests {

    @Test
    void contextLoads() {
    }
}
