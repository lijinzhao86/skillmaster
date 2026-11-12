package com.skillmasterai.modules.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The two content rules: coarse classification, and secrets removed before they travel. */
class FeedbackSafetyTest {

    @Test
    void classifiesDigitRunsByLengthAndPrefix() {
        FeedbackSafety.Scan scan = FeedbackSafety.scan(
                "手机 13800138000，身份证 11010519491231002X，卡 6222021234567890，"
                        + "还有 sk-abcdefgh12345678 和 AKIAABCDEFGHIJKLMNOP");
        assertThat(scan.counts())
                .containsEntry("phone", 1)
                .containsEntry("id_number", 1)
                .containsEntry("bank_card", 1)
                .containsEntry("secret_prefix", 2);
    }

    @Test
    void aShortDigitRunIsNotAnyCategory() {
        assertThat(FeedbackSafety.scan("版本 1.2.3，端口 8080").any()).isFalse();
    }

    @Test
    void redactionTouchesSecretsOnly() {
        String redacted = FeedbackSafety.redactSecrets(
                "电话 13800138000，密钥 sk-abcdefgh12345678，卡 6222021234567890");
        assertThat(redacted)
                .contains("13800138000")
                .contains("6222021234567890")
                .contains("[已隐藏]")
                .doesNotContain("sk-abcdefgh");
    }

    @Test
    void separatedDigitsAreStillOneValue() {
        assertThat(FeedbackSafety.scan("手机 138-0013-8000，卡 6222 0212 3456 7890").counts())
                .containsEntry("phone", 1)
                .containsEntry("bank_card", 1);
    }

    @Test
    void aNewlineIsABoundaryNotASeparator() {
        assertThat(FeedbackSafety.scan("手机号 13800138000\n1. 收不到验证码").counts())
                .containsEntry("phone", 1);
        assertThat(FeedbackSafety.scan("手机号 13800138000\n123456789 条错误").counts())
                .containsEntry("phone", 1);
    }

    @Test
    void ordinaryWordsAreNotMistakenForKeys() {
        for (String word : new String[] {"disk-usage-full", "task-management", "risk-assessment"}) {
            assertThat(FeedbackSafety.redactSecrets(word)).isEqualTo(word);
            assertThat(FeedbackSafety.scan(word).any()).isFalse();
        }
    }

    @Test
    void digitsGluedToALetterAreNotAValue() {
        // The CLI's `\b` form does not see this either; the preview and the server must agree on
        // what they are looking at, or one says "nothing here" and the other refuses the document.
        assertThat(FeedbackSafety.scan("order13800138000").any()).isFalse();
    }

    @Test
    void countsCodePointsNotBytes() {
        assertThat(FeedbackSafety.codePoints("中文🙂")).isEqualTo(3);
    }
}
