package com.skillmasterai.api;

/**
 * A User-Agent turned into something a person can read at a glance — {@code Chrome · macOS}.
 *
 * <p><strong>Coarse on purpose, and no library</strong> (ADR 0043, Q3). What the list has to answer
 * is "is one of these not me", and two words are enough for that: anything finer needs a parser with
 * a database behind it, and a wrong answer there is worse than a plain one — the design says the
 * same thing about a device-type icon, which it deliberately does not draw.
 *
 * <p><strong>Unrecognised parts are left out, and an unrecognised User-Agent is shown as it
 * arrived.</strong> That last case is the honest fallback and not a failure: a client we do not know
 * is a fact, and replacing it with "unknown" throws away the only thing that might identify it.
 *
 * <p>Lives in the API layer because this is the layer that turns stored facts into what a screen
 * shows — the session keeps the User-Agent verbatim, and nothing downstream of here needs the
 * readable shape.
 */
final class DeviceDescription {

    private static final String SEPARATOR = " · ";

    private DeviceDescription() {
    }

    /**
     * @param userAgent as stored; null or blank means the client sent none, which has no description
     *                  to give and is reported as an empty string rather than as a word we invented
     */
    static String of(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "";
        }
        String browser = browser(userAgent);
        String platform = platform(userAgent);
        if (browser.isEmpty() && platform.isEmpty()) {
            return userAgent;
        }
        if (browser.isEmpty()) {
            return platform;
        }
        if (platform.isEmpty()) {
            return browser;
        }
        return browser + SEPARATOR + platform;
    }

    /**
     * Order is the whole of the correctness here: every Chromium browser also says {@code Safari},
     * and Chrome and Edge both say {@code Chrome}, so the most specific token has to be tested
     * first. Getting this wrong does not produce an exception, it produces the wrong name.
     */
    private static String browser(String userAgent) {
        if (userAgent.contains("Edg/") || userAgent.contains("EdgA/")
                || userAgent.contains("EdgiOS/")) {
            return "Edge";
        }
        if (userAgent.contains("OPR/") || userAgent.contains("Opera")) {
            return "Opera";
        }
        if (userAgent.contains("Firefox/") || userAgent.contains("FxiOS/")) {
            return "Firefox";
        }
        // Chrome on iOS is `CriOS`, and it is the same browser under a name Safari claims.
        if (userAgent.contains("Chrome/") || userAgent.contains("CriOS/")) {
            return "Chrome";
        }
        if (userAgent.contains("Safari/")) {
            return "Safari";
        }
        return "";
    }

    private static String platform(String userAgent) {
        // The phones first: an iPad reports `Macintosh` in desktop mode, and an iPhone reports
        // `like Mac OS X` in every mode — testing macOS first would label both of them a Mac.
        if (userAgent.contains("iPhone")) {
            return "iPhone";
        }
        if (userAgent.contains("iPad")) {
            return "iPad";
        }
        if (userAgent.contains("Android")) {
            return "Android";
        }
        if (userAgent.contains("Mac OS X") || userAgent.contains("Macintosh")) {
            return "macOS";
        }
        if (userAgent.contains("Windows")) {
            return "Windows";
        }
        if (userAgent.contains("Linux")) {
            return "Linux";
        }
        return "";
    }
}
