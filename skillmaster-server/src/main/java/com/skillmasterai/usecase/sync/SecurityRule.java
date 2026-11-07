package com.skillmasterai.usecase.sync;

import java.util.regex.Pattern;

/** One compiled rule from {@code sync/security-rules.yml}. */
public record SecurityRule(String id, String label, Kind kind, Pattern pattern) {

    public enum Kind { TEXT, PATH, BINARY, EXECUTABLE, EXECUTABLE_BINARY }
}
