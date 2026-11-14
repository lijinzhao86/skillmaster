package com.skillmasterai.ops.imports;

/** The four states the page can render; each has one fixed sentence in the PRD. */
public enum LicenseStatus {
    OK("ok"),
    DECLARED_ONLY("declared-only"),
    MISSING("missing"),
    OTHER("other");

    private final String wire;

    LicenseStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static LicenseStatus fromWire(String value) {
        for (LicenseStatus status : values()) {
            if (status.wire.equals(value)) {
                return status;
            }
        }
        return MISSING;
    }
}
