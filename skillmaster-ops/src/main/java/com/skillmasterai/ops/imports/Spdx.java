package com.skillmasterai.ops.imports;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The small fixed table the product names, recognised from a license file's own text.
 *
 * <p>Shared by the metadata adapter (v49, reads the repository HEAD) and the content reader (v50,
 * reads the commit being synced). One table rather than two: a license recognised by one path and
 * not the other would make the same file mean different things on the page and in the sync gate.
 */
public final class Spdx {

    /** File names that count as a license, nearest-first. */
    public static final List<String> LICENSE_NAMES =
            List.of("LICENSE", "LICENSE.MD", "LICENSE.TXT", "COPYING", "NOTICE");

    private static final Pattern COPYRIGHT = Pattern.compile("(?im)^.*copyright.*$");

    private Spdx() {
    }

    /** The SPDX id this text reads as, or {@code NOASSERTION} when the table does not recognise it. */
    public static String detect(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("apache license") && lower.contains("version 2.0")) {
            return "Apache-2.0";
        }
        if (lower.contains("gnu affero general public license")) {
            return "AGPL-3.0";
        }
        if (lower.contains("gnu general public license")) {
            return lower.contains("version 3") ? "GPL-3.0" : "GPL-2.0";
        }
        if (lower.contains("mozilla public license version 2.0")
                || lower.contains("mozilla public license, v. 2.0")) {
            return "MPL-2.0";
        }
        if (lower.contains("permission is hereby granted, free of charge")) {
            return "MIT";
        }
        if (lower.contains("permission to use, copy, modify, and/or distribute this software")) {
            return "ISC";
        }
        if (lower.contains("redistribution and use in source and binary forms")) {
            /* BSD-4-Clause adds the advertising clause. It shares the "neither the name" sentence
               with BSD-3-Clause, so checking only that would let a non-whitelisted license through
               under a whitelisted id. */
            if (lower.contains("all advertising materials")) {
                return "NOASSERTION";
            }
            return lower.contains("neither the name") ? "BSD-3-Clause" : "BSD-2-Clause";
        }
        if (lower.contains("this is free and unencumbered software released into the public domain")) {
            return "Unlicense";
        }
        if (lower.contains("cc0 1.0 universal")) {
            return "CC0-1.0";
        }
        if (lower.contains("creative commons attribution 4.0")) {
            return "CC-BY-4.0";
        }
        return "NOASSERTION";
    }

    /** The first line that mentions a copyright, or null. */
    public static String copyright(String text) {
        Matcher matcher = COPYRIGHT.matcher(text);
        return matcher.find() ? matcher.group().trim() : null;
    }
}
