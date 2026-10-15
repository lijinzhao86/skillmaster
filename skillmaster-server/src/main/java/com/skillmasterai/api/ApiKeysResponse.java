package com.skillmasterai.api;

import java.util.List;

/**
 * The caller's keys, plus the two facts the screen needs to draw the form that makes one.
 *
 * <p>Wrapped in an object for the reason the error envelope is: a top-level array cannot grow a
 * sibling — and this one grew one on its first day.
 *
 * @param policy what this deployment does about expiry. <strong>The console cannot guess it</strong>:
 *               the design preselects 90 days, but a deployment that turned expiry off issues keys
 *               that never expire, and a form that insists on a date would send one anyway — the
 *               switch only decides the <em>default</em>, so a date in the request wins over it. The
 *               field exists so the screen can hide the question rather than answer it wrongly
 */
record ApiKeysResponse(List<ApiKeyResponse> keys, Policy policy) {

    ApiKeysResponse {
        keys = List.copyOf(keys);
    }

    /**
     * @param expires             whether keys get a date at all (ADR 0038 §决定 3)
     * @param lifetimeDays the lifetime a new key gets when nobody names one **and the longest one
     *                     this deployment will issue** (ADR 0042), in days. Sent as
     *                            days rather than as a duration because its only consumer renders a
     *                            date picker: a client computing "90 days from today" from anything
     *                            finer would have to reimplement the deployment's arithmetic
     */
    record Policy(boolean expires, long lifetimeDays, String latestDay) {
    }
}
