package com.skillmasterai.api;

import com.skillmasterai.modules.gateway.StartupBody;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one anonymous document left on the discovery channel (ADR 0089, ADR 0093).
 *
 * <p>{@code skillmaster startup} fetches this and prints it. The route is unauthenticated on purpose
 * and it is not an oversight to be tightened later: the CLI asks for it <em>before</em> it knows
 * whether the caller has a credential, and the text itself says where to sign in. The security
 * configuration permits {@code /gateway/**} explicitly, so default-deny stays intact everywhere
 * else.
 *
 * <p>The bytes come from {@link StartupBody}, which is a document on the classpath rather than a
 * row in the database. The routes this controller replaced — the skill body, the three file
 * aliases and the two discovery indexes — are gone with the gateway skill.
 */
@RestController
class StartupController {

    private final StartupBody body;

    StartupController(StartupBody body) {
        this.body = body;
    }

    @GetMapping(StartupBody.PATH)
    ResponseEntity<byte[]> startup() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaTypes.MARKDOWN)
                .body(body.bytes());
    }
}
