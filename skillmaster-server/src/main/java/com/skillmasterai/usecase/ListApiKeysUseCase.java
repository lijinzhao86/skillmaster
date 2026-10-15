package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.token.ApiKey;
import com.skillmasterai.modules.token.ApiKeys;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The caller's own keys, newest first — what the console's credential screen is built from.
 *
 * <p>A use case with no decisions in it, which is why it is three lines: it exists so the controller
 * has nothing to do but render, and so that the day this needs a filter ("hide revoked"), a search
 * (keys whose name matches) or a count, there is one obvious place for it that is not the HTTP layer.
 * It is also the one read here that takes no {@code @Transactional}: one statement needs no
 * transaction, and the annotation would only invite the next reader to assume there is more to it.
 */
@Component
public class ListApiKeysUseCase {

    private final ApiKeys keys;

    public ListApiKeysUseCase(ApiKeys keys) {
        this.keys = keys;
    }

    public List<ApiKey> list(AuthenticatedSubject subject) {
        return keys.listOf(subject.userId());
    }
}
