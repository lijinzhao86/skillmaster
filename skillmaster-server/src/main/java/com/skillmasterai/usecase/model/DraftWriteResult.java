package com.skillmasterai.usecase.model;

import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.version.VersionSummary;

/**
 * What a draft write answers with: the row as it now reads, and the access that decided.
 *
 * <p>The access travels so the controller can name the skill without a second lookup — the same
 * reason the version list hands its {@code Access} on. The row is state-blind on purpose: a write
 * that just moved the row out of what this caller may read still has to echo what it changed, and
 * the write path itself is what decided the caller could do it.
 */
public record DraftWriteResult(Access access, VersionSummary version) {
}
