package com.skillmasterai.api;

/**
 * Whether a verification-code send from this caller would be asked for a captcha.
 *
 * <p>So that a form can draw the captcha as it opens rather than after somebody has pressed a button
 * and been refused. Registration's first send from an address needs none, and no client can work out
 * which send that is — the rule is about the address and the day, and only the server holds that.
 *
 * <p>Advice rather than a promise: the answer can be stale by the time the send it was about is made.
 * The refusal still has to be handled, and it is the authority.
 */
record CaptchaRequirement(boolean required) {
}
