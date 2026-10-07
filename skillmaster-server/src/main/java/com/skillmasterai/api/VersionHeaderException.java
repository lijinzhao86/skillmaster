package com.skillmasterai.api;

/**
 * The {@code X-Skill-Version} header cannot be used: it is not a version, or it contradicts the
 * version the address already carries.
 *
 * <p>400, and <strong>deliberately not the 404 an unreadable address suffix gets</strong>. The
 * asymmetry is about what the two things are. A path may be a URL somebody pasted from somewhere
 * else, and §4.1 gives every address that resolves to nothing one single answer so that the answer
 * reveals nothing; an unreadable suffix is one of those. A header is a parameter the caller computed
 * and chose to send — saying it is malformed reveals nothing either, and answering 404 would send the
 * caller looking for a skill that is sitting right there.
 *
 * <p>Two cases, one answer, and the message says which. A header that is not a version at all is a
 * client bug. A header that disagrees with the address is also a client bug, and the more dangerous
 * one: resolving it by precedence would silently serve bytes that differ from the ones the URL names,
 * which is exactly the drift ADR 0012 exists to prevent. Saying so is the only honest answer.
 */
public class VersionHeaderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public VersionHeaderException(String message) {
        super(message);
    }
}
