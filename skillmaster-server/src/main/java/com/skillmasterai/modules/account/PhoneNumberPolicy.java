package com.skillmasterai.modules.account;

import com.skillmasterai.common.Text;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What a phone number is allowed to be.
 *
 * <p>Mainland mobile numbers: eleven digits, starting with 1, second digit 3 to 9. Narrow on
 * purpose. Accepting a wider shape would mean storing values that no SMS provider will deliver to,
 * which turns "you typed it wrong" into "the code never arrives" — a much worse thing to debug,
 * for a user who cannot see either the column or the provider's error.
 *
 * <p>Widening this later is a one-line change; discovering that a stored number is undeliverable is
 * not, because by then it is somebody's login.
 */
public final class PhoneNumberPolicy {

    private static final Pattern SHAPE = Pattern.compile("^1[3-9]\\d{9}$");

    private PhoneNumberPolicy() {
    }

    /** @return an issue code naming what is wrong, or empty when the number is acceptable */
    public static Optional<String> problemWith(String phone) {
        if (Text.isBlank(phone)) {
            return Optional.of("required");
        }
        return SHAPE.matcher(phone).matches() ? Optional.empty() : Optional.of("invalid_format");
    }
}
