package com.skillmasterai.api;

/**
 * A captcha to solve: the id to send back with the answer, and the image to read it from.
 *
 * <p>The image is base64 rather than a URL to a second request, because this plane answers JSON and
 * a client that has to fetch an image from somewhere else has to be told where — which is another
 * endpoint, another credential question and another chance to get the wiring wrong, in exchange for
 * about eight kilobytes.
 *
 * <p>The answer is not here, and never will be. It is not in the response, not in a header, and not
 * derivable from the id: what the server keeps is a hash of it.
 */
record CaptchaResponse(String captchaId, String image) {
}
