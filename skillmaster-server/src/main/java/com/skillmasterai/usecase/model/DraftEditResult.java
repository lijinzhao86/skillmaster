package com.skillmasterai.usecase.model;

import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.VersionSummary;

/**
 * What a browser edit answers with: the row, its new manifest, and the access that decided.
 *
 * <p>The manifest travels because the page redraws the tree from one answer rather than issuing a
 * second read; {@link DraftWriteResult} stays the API plane's smaller shape.
 */
public record DraftEditResult(Access access, VersionSummary version, Manifest manifest) {
}
