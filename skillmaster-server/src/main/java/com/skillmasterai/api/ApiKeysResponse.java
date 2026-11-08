package com.skillmasterai.api;

import java.util.List;

/**
 * The caller's keys, plus the facts the screen needs to draw the form that makes one.
 *
 * <p>Wrapped in an object for the reason the error envelope is: a top-level array cannot grow a
 * sibling — and this one grew one on its first day.
 *
 * @param policy what this deployment does about expiry. <strong>The console cannot guess it</strong>:
 *               the maximum and the default are product constants, but whether 「不过期」 is offered
 *               is a deployment decision, and a client that assumed it away would hide a choice the
 *               server accepts
 */
record ApiKeysResponse(List<ApiKeyResponse> keys, Policy policy) {

    ApiKeysResponse {
        keys = List.copyOf(keys);
    }

    /**
     * @param allowNoExpiry whether a person may choose a key with no date (ADR 0171 §决定 3)
     * @param defaultDays   the date a request that names nothing gets, in days (90; ADR 0171 §决定 1)
     * @param lifetimeDays  the longest this product will issue, in days (365; ADR 0171 §决定 2)
     * @param latestDay     the last day this product issues, as the console's date input wants it
     *                      (`YYYY-MM-DD`, Shanghai; ADR 0171 §决定 2)
     */
    record Policy(boolean allowNoExpiry, long defaultDays, long lifetimeDays, String latestDay) {
    }
}
