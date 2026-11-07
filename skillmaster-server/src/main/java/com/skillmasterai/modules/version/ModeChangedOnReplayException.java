package com.skillmasterai.modules.version;

/**
 * A submission's bytes are the ones an existing version already holds, but its executable bit is
 * not.
 *
 * <p>A version is identified by the digest of its content, and permissions are not part of that
 * digest (see {@link Manifest#digest()}). The same bytes therefore resolve to the existing version,
 * which already has a stored mode — and silently keeping the old one would leave the author with a
 * "fixed" script that still cannot be executed, and no message saying why.
 *
 * <p>Refused rather than absorbed, and the message names the fix: change any file as well, because
 * a version whose only difference is a permission is not a version this platform can address.
 */
public class ModeChangedOnReplayException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ModeChangedOnReplayException(String message) {
        super(message);
    }
}
