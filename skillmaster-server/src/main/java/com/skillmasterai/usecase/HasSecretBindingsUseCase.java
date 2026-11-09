package com.skillmasterai.usecase;

import com.skillmasterai.modules.secret.SecretService;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The composition the metadata response needs: which environment variables a lease for this caller
 * and this skill would actually fill, and therefore which of the author's declared ones are missing.
 *
 * <p>It lives in the use-case layer because the caller has already been authorized by the metadata
 * read, and because the alternative — giving M12 a dependency on M16 — would make the two modules
 * inseparable. The controller calls this after it has a {@code SkillDetail}, so there is no second
 * access check here.
 *
 * <p>Own bindings and live shared bindings both count: the question is what <em>this caller</em>
 * would receive, not who owns the value.
 *
 * <p><strong>Names, not a boolean.</strong> The metadata answer already had to say "anything at
 * all?" for the run path to decide whether to lease; v52's P2 asks a second question of the same
 * query — the declaration prompt has to name the variable the user is missing — so the answer is the
 * set, and the boolean is {@code !isEmpty()}.
 */
@Component
public class HasSecretBindingsUseCase {

    private final SecretService secrets;

    public HasSecretBindingsUseCase(SecretService secrets) {
        this.secrets = secrets;
    }

    /**
     * @param sharesAreValid whether M12 answered that this caller holds a grant on the skill rather
     *                       than merely being able to see it. A share granted to somebody whose
     *                       grant was later withdrawn no longer counts, which is why the metadata
     *                       answer and the lease answer are derived from the same rule.
     */
    @Transactional(readOnly = true)
    public Set<String> boundEnvNames(String userId, String skillId, boolean sharesAreValid) {
        return secrets.boundEnvNames(userId, skillId, sharesAreValid);
    }
}
