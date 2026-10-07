package com.skillmasterai.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * An HTTP client that behaves like a browser: it keeps cookies, and it can read the CSRF token the
 * server set so that a caller can put it back in a header.
 *
 * <p><strong>The echoing is the caller's job because no browser does it either</strong> — which is
 * the whole reason the double-submit protects anything: a page on another origin can make the
 * browser send the cookie, but it cannot read the cookie to put it in the header.
 *
 * <p>One of these is one signed-in identity. Two of them are two browsers, which is how a test asks
 * about a user's sessions rather than about one session — see {@code WebPasswordResetIT}.
 *
 * <p>Requests are given as {@link URI}s rather than paths, so this class holds no opinion about
 * where the server is; the test base class is what knows that.
 */
public final class Browser {

    /** What {@code CookieCsrfTokenRepository} names the header and the cookie it pairs with. */
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";
    public static final String CSRF_COOKIE = "XSRF-TOKEN";

    private final HttpClient client = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            // A redirect would be followed silently, and the assertion would land on whatever it
            // landed on rather than on the response that sent it there.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** A POST carrying the current CSRF token, as a client that read the documentation would. */
    public HttpResponse<String> post(URI uri, String body) {
        return post(uri, body, csrfToken());
    }

    /** @param csrfToken null to send no CSRF header — the omission several tests are about */
    public HttpResponse<String> post(URI uri, String body, String csrfToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (csrfToken != null) {
            request.header(CSRF_HEADER, csrfToken);
        }
        return send(request.build());
    }

    public HttpResponse<String> get(URI uri) {
        return send(HttpRequest.newBuilder(uri).GET().build());
    }

    /**
     * A DELETE carrying the current CSRF token, for the one route on this plane that has no body.
     *
     * <p>Withdrawn shares are the only thing a browser deletes here, and the request is CSRF-protected
     * like every other write on the plane: it takes access away from somebody, which is not something
     * another site should be able to do with a cookie alone.
     */
    public HttpResponse<String> delete(URI uri) {
        return delete(uri, csrfToken());
    }

    /** @param csrfToken null to send no CSRF header — the omission {@code WebCsrfIT} is about */
    public HttpResponse<String> delete(URI uri, String csrfToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).DELETE();
        if (csrfToken != null) {
            request.header(CSRF_HEADER, csrfToken);
        }
        return send(request.build());
    }

    /**
     * A form-encoded write: what a browser does when it submits a page, and what the token plane
     * accepts — an OAuth endpoint reads {@code application/x-www-form-urlencoded} and nothing else.
     *
     * <p>No CSRF header, unlike {@link #post}: the token plane's writes come from a program as often
     * as from a page, and its chain has the protection off for that reason. The consent submission is
     * the exception the module doc records as still owed.
     */
    public HttpResponse<String> postForm(URI uri, String body) {
        return postForm(uri, body, null);
    }

    /**
     * @param csrfToken null to send no CSRF header — the omission the consent test is about. The
     *                  token plane has the protection on, so a form that submits without one is
     *                  refused, exactly as it would be if the page forgot to render it.
     */
    public HttpResponse<String> postForm(URI uri, String body, String csrfToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (csrfToken != null) {
            request.header(CSRF_HEADER, csrfToken);
        }
        return send(request.build());
    }

    /** The token as it currently stands, or null when the server has not set one. */
    public String csrfToken() {
        HttpCookie cookie = cookie(CSRF_COOKIE).orElse(null);
        return cookie == null ? null : cookie.getValue();
    }

    /** The cookie itself, for the assertions that are about its flags rather than its value. */
    public Optional<HttpCookie> cookie(String name) {
        List<HttpCookie> matching = jar().getCookies().stream()
                .filter(cookie -> name.equals(cookie.getName()))
                .toList();
        // The last one, because a rotated token arrives as a second Set-Cookie for the same name
        // and it is the newer value the server will expect back.
        return matching.isEmpty() ? Optional.empty() : Optional.of(matching.get(matching.size() - 1));
    }

    private CookieStore jar() {
        return ((CookieManager) client.cookieHandler().orElseThrow()).getCookieStore();
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("request to " + request.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during " + request.uri(), e);
        }
    }
}
