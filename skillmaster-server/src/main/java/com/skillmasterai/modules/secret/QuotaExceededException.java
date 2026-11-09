package com.skillmasterai.modules.secret;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ConflictException;
import com.skillmasterai.common.ErrorCode;
import java.util.List;

/**
 * A credential has served its daily lease budget — 409 with the limit in the detail.
 *
 * <p>The quota is a safety valve rather than a setting, so the message has to give the person (and
 * the agent) the one way out and name it without inviting the agent to take it: raising the limit
 * is the user's decision, made with {@code skmr secret quota}. The current value travels in the
 * detail as well, because the CLI prints it.
 */
public class QuotaExceededException extends ConflictException {

    private static final long serialVersionUID = 1L;

    /**
     * @param credentialId the credential's own id, so the message carries a command that can be run
     *                     as printed. A refusal whose next step needs an extra lookup is a refusal
     *                     an agent resolves by guessing.
     */
    public QuotaExceededException(String credentialId, String credentialName, int perDayLimit) {
        super(ErrorCode.CREDENTIAL_QUOTA_EXCEEDED,
                "credential '" + credentialName + "' (" + credentialId + ") has reached its daily"
                        + " limit of " + perDayLimit + " leases; ask the user to raise it with"
                        + " `skmr secret quota " + credentialId + " --per-day <N>` — do not raise it"
                        + " yourself",
                List.of(new ApiError.Detail("per_day", "quota_exceeded")));
    }
}
