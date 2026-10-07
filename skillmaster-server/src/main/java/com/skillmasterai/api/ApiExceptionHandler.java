package com.skillmasterai.api;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.modules.account.AccountRequestException;
import com.skillmasterai.modules.account.ThrottledException;
import com.skillmasterai.modules.account.VerificationCodeException;
import com.skillmasterai.modules.ingest.IngestException;
import com.skillmasterai.modules.search.InvalidSearchRequestException;
import com.skillmasterai.modules.version.SkillDeletedException;
import com.skillmasterai.modules.version.VersionNameTakenException;
import com.skillmasterai.modules.version.NotPermittedException;
import com.skillmasterai.modules.version.VersionStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Turns exceptions into the one error envelope of §4.1.
 *
 * <p>The point of doing this centrally is that a client should never have to handle two shapes.
 * Spring's own errors — an unsupported method, an unreadable body — would otherwise come back as
 * RFC 9457 {@code problem+json} while ours come back as {@code {"error": …}}, and a client would
 * need to know which is which before it could read a status code.
 *
 * <p>What is <em>not</em> done here is the 404-for-a-private-skill decision. That is a domain
 * conclusion reached in M4, and by the time control arrives here the answer is already a plain
 * "not found" — see {@link SkillsController}. An exception handler is the wrong place for it,
 * because by then the information needed to decide is gone.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** A rejected upload. Carries every problem found, so one round trip is enough to fix it. */
    @ExceptionHandler(IngestException.class)
    ResponseEntity<ApiError> onIngestException(IngestException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.INVALID_UPLOAD, e.getMessage(), e.details()));
    }

    /**
     * An address that resolves to nothing — §4.1's one answer to four different failures.
     *
     * <p>A code rather than a bare status because clients switch on {@code code}: an empty 404 body
     * tells a client only that something went wrong, and §4.1's envelope exists so that every failure
     * has the same shape. That all four share {@code skill_not_found} is the point — see
     * {@link SkillNotFoundException}.
     */
    @ExceptionHandler(SkillNotFoundException.class)
    ResponseEntity<ApiError> onSkillNotFoundException(SkillNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(ErrorCode.SKILL_NOT_FOUND, e.getMessage()));
    }

    /**
     * §4.1's other 404: the address resolved to a version, and its manifest has no such file.
     *
     * <p>A distinct code from {@code skill_not_found} because it is a distinct answer — the skill
     * and the version both exist and the caller can read them. See
     * {@link FileNotInManifestException}.
     */
    @ExceptionHandler(FileNotInManifestException.class)
    ResponseEntity<ApiError> onFileNotInManifestException(FileNotInManifestException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(ErrorCode.FILE_NOT_FOUND, e.getMessage()));
    }

    /**
     * A publish to the name of a soft-deleted skill (§4.3 gives restoring its own endpoint).
     *
     * <p>400 rather than 409: the request is well-formed and the conflict is real, but the only
     * answer the API offers is "restore it first", which the message says. A distinct status would
     * invite clients to branch on a condition that has exactly one recovery.
     */
    @ExceptionHandler(SkillDeletedException.class)
    ResponseEntity<ApiError> onSkillDeletedException(SkillDeletedException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.INVALID_REQUEST, e.getMessage()));
    }

    /**
     * Publishing or discarding a version that is not in a state where the operation means anything
     * (ADR 0031): publishing one that was discarded, discarding one that is not a draft.
     *
     * <p>400 with the reason in the message, matching the soft-deleted case above, and for the same
     * shape of reason — the request is well-formed, the thing is right there, and the caller can fix
     * it once they know which state it is in. A 404 would send the author looking for a version they
     * are looking at. Nothing is disclosed by saying so: the only caller who reaches here is the one
     * who may already read the version.
     */
    /**
     * The caller may read this skill and may not do this to it (ADR 0034).
     *
     * <p><strong>The first 403 in this system that is about who is asking, and it is allowed to be
     * one because a 404 here would be false.</strong> Everywhere else, "not yours" and "not there"
     * are one answer, because any distinction confirms the skill exists. Here it has already been
     * confirmed — the skill is in the listing the caller just read, or they fetched its body — so
     * answering "no skill at that address" would send them to check an address that is right, and
     * nothing is disclosed by saying what is actually wrong.
     *
     * <p><strong>No {@code WWW-Authenticate} challenge, unlike a scope failure.</strong> That header
     * tells a client to go and obtain a token with more scope, which is the wrong instruction here:
     * the token carries {@code skills:write} already, and the refusal comes from this resource rather
     * than from anything the client could authorize its way past. Sending it would produce a loop —
     * re-authorize, get the same 403.
     */
    @ExceptionHandler(NotPermittedException.class)
    ResponseEntity<ApiError> onNotPermitted(NotPermittedException e) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                .body(ApiError.of(ErrorCode.FORBIDDEN, e.getMessage()));
    }

    @ExceptionHandler(VersionStateException.class)
    ResponseEntity<ApiError> onVersionStateException(VersionStateException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.INVALID_REQUEST, e.getMessage()));
    }

    /**
     * A version name the skill already has, with different content (ADR 0033).
     *
     * <p>400 rather than a silent second name, and with a code of its own rather than
     * {@code invalid_request}: the fix is specific — the author increments the version in their
     * {@code SKILL.md} — and a submission that fails this way looks exactly like one that worked
     * unless the answer says which it was. Nothing is disclosed: the caller is the author, and the
     * version is their own.
     */
    @ExceptionHandler(VersionNameTakenException.class)
    ResponseEntity<ApiError> onVersionNameTaken(VersionNameTakenException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.VERSION_ALREADY_EXISTS, e.getMessage()));
    }

    /**
     * The {@code X-Skill-Version} header is unusable — not a version, or contradicting the address.
     *
     * <p>400 in both cases. See {@link VersionHeaderException} for why this is not the 404 an
     * unreadable address suffix gets.
     */
    @ExceptionHandler(VersionHeaderException.class)
    ResponseEntity<ApiError> onVersionHeader(VersionHeaderException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.INVALID_REQUEST, e.getMessage()));
    }

    /**
     * A query this API does not perform: a limit below one, or a cursor it cannot read.
     *
     * <p>A 400 rather than a repaired request. A cursor that is silently ignored restarts the
     * listing from the beginning, which a client paginating a large result set sees as an infinite
     * loop — and it has no way to tell that from a listing that happens to shrink.
     */
    @ExceptionHandler(InvalidSearchRequestException.class)
    ResponseEntity<ApiError> onInvalidSearchRequest(InvalidSearchRequestException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.INVALID_REQUEST, e.getMessage()));
    }

    /*
     * The four below are the browser plane's, and they are handled in this advice rather than one
     * scoped to WebAccountController because none of their types can be raised anywhere else: an
     * AccountRequestException comes from M1's own checks, and the other three from the use cases
     * that call them. A second advice would have to restate the catch-all to keep its plane's
     * framework errors away from the first one, which is more machinery for less certainty.
     */

    /**
     * A field the client can fix: a username that is taken, a password that is too short.
     *
     * <p>A 400 with the field named, rather than a code per failure — §4.1 gives field-level 400s a
     * shape, and a client that renders errors next to inputs needs to know which input.
     *
     * <p>The message carries no value from the request. {@link AccountRequestException} builds it
     * from the field name alone, so a rejected phone number cannot reach a log line through here.
     */
    @ExceptionHandler(AccountRequestException.class)
    ResponseEntity<ApiError> onAccountRequestException(AccountRequestException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.INVALID_REQUEST, "the request was refused",
                        e.field(), e.issue()));
    }

    /**
     * An SMS code that was not accepted — wrong, expired, used, or guessed at too many times.
     *
     * <p>One answer for all of them, and the reason it failed is written to the log instead of the
     * body. Which of the four it was is what an attacker would use to decide whether to keep
     * guessing; the legitimate caller asks for a new code in every case.
     */
    @ExceptionHandler(VerificationCodeException.class)
    ResponseEntity<ApiError> onVerificationCodeException(VerificationCodeException e) {
        log.debug("verification code refused: {}", e.detail());
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.VERIFICATION_CODE_INVALID,
                        "The code is not valid. Request a new one."));
    }

    /**
     * The caller has spent its budget — of SMS sends, or of login attempts.
     *
     * <p>{@code Retry-After} is required rather than polite: without it the only thing a client can
     * do with a 429 is retry, which is the behaviour the limit exists to stop.
     */
    @ExceptionHandler(ThrottledException.class)
    ResponseEntity<ApiError> onThrottledException(ThrottledException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
                .body(ApiError.of(ErrorCode.TOO_MANY_REQUESTS, "Too many requests."));
    }

    /** A login that did not authenticate. See {@link InvalidCredentialsException}. */
    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ApiError> onInvalidCredentialsException(InvalidCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of(ErrorCode.INVALID_CREDENTIALS, e.getMessage()));
    }

    /**
     * A query parameter that does not fit its type — {@code limit=abc}, say.
     *
     * <p>Handled by name rather than left to the catch-all, which looks redundant and is not:
     * Spring 7's {@code MethodArgumentTypeMismatchException} no longer implements
     * {@link ErrorResponse}, so the catch-all would answer 500 for what is plainly a bad request.
     * That was measured, not assumed — the first version of the search endpoint returned
     * {@code internal_error} for {@code limit=abc}.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> onTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.INVALID_REQUEST,
                        "the '" + e.getName() + "' parameter is not a valid value"));
    }

    /**
     * The body exceeded the multipart ceiling, so it was refused before it was ever buffered.
     *
     * <p>Handled explicitly because the container's own answer is a bare 413 and the caller's next
     * question is always "how big may it be" — the ceiling is not the skill limit, and a caller who
     * reads 413 as "my skill is too big" would go and shrink the wrong thing.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> onMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiError.of(ErrorCode.INVALID_UPLOAD,
                        "the request body is too large to be accepted", "file", "body_too_large"));
    }

    /** The request had no {@code file} part at all — the most common mistake against this API. */
    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ApiError> onMissingPart(MissingServletRequestPartException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.INVALID_UPLOAD,
                        "the request is missing the '" + e.getRequestPartName() + "' part",
                        e.getRequestPartName(), "missing"));
    }

    /**
     * Everything else.
     *
     * <p>Spring's own web exceptions implement {@link ErrorResponse} and know their status; those
     * keep it, and only the body is re-shaped. Their messages are replaced with one of ours —
     * framework messages describe the framework's view of the request and have a habit of naming
     * internals, whereas the status is the part a client should act on.
     *
     * <p>Anything that is not one of those is a bug on this side: it is logged with its stack trace
     * and answered as a 500 whose body says nothing about what broke, because the caller can do
     * nothing with that and it is exactly the kind of detail that should not leave the process.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> onAnythingElse(Exception e) {
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.resolve(errorResponse.getStatusCode().value());
            if (status == null) {
                log.error("exception carried an unknown status {}", errorResponse.getStatusCode(), e);
                return internalError();
            }
            log.debug("request rejected with {}", status, e);
            // The framework's headers are kept: a 405's Allow is required by RFC 9110, and
            // rebuilding the response without it told a client its method was wrong without saying
            // which one would work.
            return ResponseEntity.status(status)
                    .headers(errorResponse.getHeaders())
                    .body(ApiError.of(codeFor(status), "the request could not be handled"));
        }

        log.error("unhandled exception", e);
        return internalError();
    }

    private static ResponseEntity<ApiError> internalError() {
        return ResponseEntity.internalServerError()
                .body(ApiError.of(ErrorCode.INTERNAL_ERROR, "an unexpected error occurred"));
    }

    /**
     * The code §4.1 pairs with the status, not one code for the whole 4xx band.
     *
     * <p>Every 404 is {@code skill_not_found}, including one Spring raised for a path nothing is
     * mapped to — §4.1 gives the answer "this address names nothing" one code, and a client that
     * switches on {@code code} should never have to reconcile it against the status it arrived
     * with. Reporting a 404 as {@code invalid_request} did exactly that: the table assigns that
     * code to 400 and nothing else.
     *
     * <p>A route Spring does not know answers the same code, which is deliberate rather than an
     * oversight: a client should not have to know which side of the router decided that an address
     * names nothing.
     */
    private static ErrorCode codeFor(HttpStatus status) {
        if (status == HttpStatus.NOT_FOUND) {
            return ErrorCode.SKILL_NOT_FOUND;
        }
        return status.is4xxClientError() ? ErrorCode.INVALID_REQUEST : ErrorCode.INTERNAL_ERROR;
    }
}
