package com.skillmasterai.modules.secret;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import java.util.List;

/**
 * A value above the 32 KiB an environment variable can carry — 400 with both numbers in the detail.
 *
 * <p>The message names no bytes from the value. The caller already knows what it sent; what it does
 * not know is the ceiling, which is the one fact the detail adds.
 */
public class SecretValueTooLargeException extends RequestException {

    private static final long serialVersionUID = 1L;

    public SecretValueTooLargeException(long actualBytes, long limitBytes) {
        super(ErrorCode.CREDENTIAL_VALUE_TOO_LARGE,
                "the value is too large; a credential may be at most " + limitBytes + " bytes",
                List.of(new ApiError.Detail("value", "too_large", actualBytes, limitBytes)));
    }
}
