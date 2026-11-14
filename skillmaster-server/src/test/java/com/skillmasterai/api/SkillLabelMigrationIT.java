package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;

/**
 * v66 migration contract (test-plan T1, T18): 13 shelves, the old 10 retired, and a reversible
 * mapping that still names every old word — including which old word each skill had.
 */
@Sql("/sql/truncate-business-tables.sql")
class SkillLabelMigrationIT extends AbstractOrganizeIT {

    private static final String[] NEW_KEYS = {
            "office-efficiency", "content-creation", "dev-programming", "data-analysis",
            "design-media", "ai-agent", "knowledge-management", "business-ops", "education",
            "professional", "it-ops-security", "life-service", "ai-collaboration"};

    @Test
    void theThirteenShelvesReplaceTheOldTenAndTheMappingIsComplete() {
        assertThat(count("SELECT count(*) FROM skill_category WHERE state = 'enabled'"))
                .isEqualTo(13);
        assertThat(count("SELECT count(*) FROM skill_category WHERE state = 'retired'"))
                .isEqualTo(10);
        assertThat(count("SELECT count(*) FROM skill_category_mapping")).isEqualTo(10);
        assertThat(keyOf("doc")).isEqualTo("office-efficiency");
        assertThat(keyOf("office")).isEqualTo("office-efficiency");
        assertThat(keyOf("ops")).isEqualTo("it-ops-security");
        assertThat(count("SELECT count(*) FROM skill_category_mapping"
                + " WHERE old_state = 'stopped' AND old_key = 'ops'")).isEqualTo(1);
    }

    @Test
    void noSkillStillPointsAtAnOldShelf() {
        assertThat(count("""
                SELECT count(*) FROM skill s JOIN skill_category_mapping m
                  ON m.old_key = s.category_key
                """)).isZero();
    }

    /**
     * The rollback's correctness (audit finding): the key-level mapping is many-to-one, so the
     * snapshot is the only thing that can put `doc` and `office` back on the skills that had them.
     * The second statement must clear **all 13** new shelves, not only the six that are new rather
     * than renamed: a skill categorised after the migration has no snapshot row, and the DELETE
     * that follows would stop on its foreign key.
     */
    @Test
    void theRollbackRestoresEachSkillsOwnOldShelfAndClearsLaterAssignments() {
        String wasDoc = publicSkill("was-doc");
        String wasOffice = publicSkill("was-office");
        String afterMigration = publicSkill("after-migration");
        jdbc.sql("UPDATE skill SET category_key = 'office-efficiency' WHERE id IN (:a, :b)")
                .param("a", wasDoc).param("b", wasOffice).update();
        jdbc.sql("UPDATE skill SET category_key = 'dev-programming' WHERE id = :id")
                .param("id", afterMigration).update();
        jdbc.sql("""
                INSERT INTO skill_category_assignment_snapshot (skill_id, old_key, migrated_at)
                VALUES (:doc, 'doc', CAST(:at AS timestamptz)),
                       (:office, 'office', CAST(:at AS timestamptz))
                """).param("doc", wasDoc).param("office", wasOffice).param("at", AT).update();

        // Rollback statement 1: the snapshot restores each skill's own old key.
        jdbc.sql("""
                UPDATE skill s SET category_key = snap.old_key
                  FROM skill_category_assignment_snapshot snap WHERE s.id = snap.skill_id
                """).update();
        // Rollback statement 2: everything still on a new shelf has no old word to return to.
        jdbc.sql("UPDATE skill SET category_key = NULL WHERE category_key = ANY(:keys)")
                .param("keys", NEW_KEYS).update();

        assertThat(jdbc.sql("SELECT category_key FROM skill WHERE id = :id")
                .param("id", wasDoc).query(String.class).single()).isEqualTo("doc");
        assertThat(jdbc.sql("SELECT category_key FROM skill WHERE id = :id")
                .param("id", wasOffice).query(String.class).single()).isEqualTo("office");
        assertThat(jdbc.sql("SELECT category_key FROM skill WHERE id = :id")
                .param("id", afterMigration).query(String.class).optional()).isEmpty();
        // Nothing points at a new shelf any more, which is what lets the DELETE succeed.
        assertThat(jdbc.sql("SELECT count(*) FROM skill WHERE category_key = ANY(:keys)")
                .param("keys", NEW_KEYS).query(Long.class).single()).isZero();
    }

    private String keyOf(String oldKey) {
        return jdbc.sql("SELECT new_key FROM skill_category_mapping WHERE old_key = :key")
                .param("key", oldKey).query(String.class).single();
    }
}
