-- v72: the production database holds final labels, not the machinery that produced them
-- (ADR 0197, ADR 0208).
--
-- `skill_label` becomes the set of values a skill has. Evidence, rule version and run history live
-- in the operations service's database; the read model no longer filters by source, confidence or
-- evaluated version because no such columns remain.
--
-- **The data is safe to drop.** v66 P0 shipped the vocabulary and the read model, but no writer:
-- production has no label rows and no extraction runs. A development database that inserted
-- fixtures keeps the (skill_id, definition_id) pairs, which is exactly what the final table means.

ALTER TABLE skill_label DROP CONSTRAINT skill_label_pkey;
-- The pre-v72 table also had UNIQUE (skill_id, definition_id); the new primary key replaces it.
ALTER TABLE skill_label DROP CONSTRAINT skill_label_skill_id_definition_id_key;

-- One statement per column: the schema generator recognises exactly this spelling, and a
-- migration it cannot read would silently disappear from `current/schema.md`.
ALTER TABLE skill_label DROP COLUMN id;
ALTER TABLE skill_label DROP COLUMN run_id;
ALTER TABLE skill_label DROP COLUMN source;
ALTER TABLE skill_label DROP COLUMN confidence;
ALTER TABLE skill_label DROP COLUMN evidence;
ALTER TABLE skill_label DROP COLUMN model;
ALTER TABLE skill_label DROP COLUMN model_version;
ALTER TABLE skill_label DROP COLUMN vocabulary_version;
ALTER TABLE skill_label DROP COLUMN review_state;
ALTER TABLE skill_label DROP COLUMN evaluated_version_id;
ALTER TABLE skill_label DROP COLUMN created_at;
ALTER TABLE skill_label DROP COLUMN updated_at;

ALTER TABLE skill_label ADD PRIMARY KEY (skill_id, definition_id);

DROP TABLE label_audit_sample;
DROP TABLE label_extraction_run;

ALTER TABLE skill DROP COLUMN outcome_source;
ALTER TABLE skill DROP COLUMN outcome_confidence;
ALTER TABLE skill DROP COLUMN outcome_evidence;
ALTER TABLE skill DROP COLUMN outcome_model;
ALTER TABLE skill DROP COLUMN outcome_model_version;
ALTER TABLE skill DROP COLUMN outcome_evaluated_version_id;
ALTER TABLE skill DROP COLUMN outcome_updated_at;

COMMENT ON TABLE skill_label IS
  'v72 final result: one row is "this skill has this value". Intermediate states (evidence, confidence, rule version, extraction runs) live in the operations database (ADR 0197).';
COMMENT ON COLUMN skill_label.definition_id IS
  'The vocabulary id, e.g. runtime:python. The wire key and the stored identity are the same string.';
COMMENT ON COLUMN skill.outcome IS
  'v65: one sentence saying what the reader gets. Max 200 Unicode code points (ADR 0181); never replaces description. v72 keeps the value but drops its provenance columns.';
