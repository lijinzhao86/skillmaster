package com.skillmasterai.usecase.sync;

/** One rule hit, named so the log says which rule and which file rather than "scan failed". */
public record SecurityFinding(String rule, String label, String path) {
}
