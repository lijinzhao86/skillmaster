package com.skillmasterai.usecase;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import org.junit.jupiter.api.Test;

/** The server-side write checks the console and direct API clients also have to pass. */
class SecretValidationTest {

    @Test
    void ordinaryEnvironmentNamesAreAccepted() {
        assertThatCode(() -> SecretSkills.requireEnvName("COOKIE")).doesNotThrowAnyException();
        assertThatCode(() -> SecretSkills.requireEnvName("_internal_1")).doesNotThrowAnyException();
    }

    @Test
    void aNameAPosixShellCannotUseIsRefused() {
        for (String bad : new String[] {"1COOKIE", "BAD-NAME", "BAD NAME", "BAD.NAME", "",
                null}) {
            assertThatThrownBy(() -> SecretSkills.requireEnvName(bad))
                    .as("env %s", bad)
                    .isInstanceOfSatisfying(RequestException.class,
                            refused -> org.assertj.core.api.Assertions.assertThat(refused.code())
                                    .isEqualTo(ErrorCode.INVALID_REQUEST));
        }
    }

    @Test
    void anEmptyValueIsRefused() {
        assertThatThrownBy(() -> SecretSkills.requireNonEmptyValue(""))
                .isInstanceOfSatisfying(RequestException.class,
                        refused -> org.assertj.core.api.Assertions.assertThat(refused.code())
                                .isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> SecretSkills.requireNonEmptyValue(null))
                .isInstanceOf(RequestException.class);
    }
}
