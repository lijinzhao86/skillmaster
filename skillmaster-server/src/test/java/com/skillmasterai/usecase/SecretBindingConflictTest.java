package com.skillmasterai.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.modules.secret.BindingConflictException;
import org.junit.jupiter.api.Test;

/** The one-binding-per-{@code (skill, env)} rule, in the shape the use cases apply it. */
class SecretBindingConflictTest {

    @Test
    void anOwnerWithNoBindingMayAddOne() {
        assertThatCode(() -> SecretSkills.requireNoOwnBinding(false, "COOKIE"))
                .doesNotThrowAnyException();
    }

    @Test
    void anOwnerWhoAlreadyBoundThatVariableIsRefused() {
        assertThatThrownBy(() -> SecretSkills.requireNoOwnBinding(true, "COOKIE"))
                .isInstanceOfSatisfying(BindingConflictException.class, refused -> {
                    assertThat(refused.code())
                            .isEqualTo(ErrorCode.CREDENTIAL_BINDING_CONFLICT);
                    assertThat(refused.getMessage()).contains("COOKIE");
                });
    }
}
