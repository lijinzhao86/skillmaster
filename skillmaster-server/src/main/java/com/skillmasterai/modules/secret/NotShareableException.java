package com.skillmasterai.modules.secret;

import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;

/**
 * The person a share names cannot receive this credential: they are not an accepted collaborator
 * of this skill (or they are the credential's own owner).
 *
 * <p>400 rather than 403 because the request named a target that fails a rule the caller can read
 * from the response; nothing is disclosed about the target beyond the fact that they are not a
 * collaborator, which the skill's own sharing screen already says.
 */
public class NotShareableException extends RequestException {

    private static final long serialVersionUID = 1L;

    public NotShareableException(String message) {
        super(ErrorCode.CREDENTIAL_NOT_SHAREABLE, message);
    }
}
