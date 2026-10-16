package com.skillmasterai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.skillmasterai.SkillMasterServerApplication;
import com.skillmasterai.modules.token.CliSignInPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * T8(c) · the login credential's own rules, met where a deployment meets them.
 *
 * <p><strong>The whole application, started the way a deployment starts it</strong> — the same
 * arrangement {@code SessionPolicyStartupIT} uses one ruler over. A unit test on
 * {@link CliSignInPolicy} proves the record refuses the combination; what 验收 #5(c) asks for is that
 * the refusal <em>reaches a deployment</em>, so that two numbers in a property file cannot disable one
 * of the rulers without anybody noticing.
 *
 * <p><strong>Both numbers are set explicitly, and the idle one is set to what the property file
 * already says.</strong> Overriding only the ceiling would leave the test resting on the default being
 * longer than the idle one, which is a fact about {@code application.yml} rather than about the rule.
 *
 * <p><strong>They are passed as command-line arguments, and that is not stylistic.</strong>
 * {@code application.yml} declares every property below, and a builder's {@code properties(...)} are
 * <em>default</em> properties — the bottom of Boot's precedence order, below config data — so that form
 * cannot override a value the file sets. Command-line arguments are the top of it.
 *
 * <p><strong>The ceiling case is the pair where the two rules disagree, and that is the whole point of
 * it.</strong> 验收 #5(c) writes the pair as "absolute shorter than idle", and a deployment that sets
 * that does fail to start — but not here: {@code SkillmasterProperties.sessions} is M1's own
 * {@code SessionPolicy}, built while the properties record is bound, and it refuses
 * {@code absolute < idle} before any bean is created. {@link CliSignInPolicy} is a bean, constructed
 * later, so a boot with the plan's literal pair fails with M1's sentence and this record's constructor
 * is never reached — a test written that way would go green without once exercising the rule it names.
 * Equality is the input that separates them: {@code SessionPolicy} allows it, and this record refuses
 * it, because a ceiling equal to the idle window binds the slide on the very first use. So equality is
 * what this case boots with, and the refusal is asserted <em>by name</em> — the sentence has to be this
 * credential's, not the session's.
 *
 * <p>It reads the failure by walking the causes rather than by asserting on the outermost one: what
 * the framework wraps a binding failure in is its business, and a test that pinned that shape would
 * fail on a Spring upgrade for a reason that has nothing to do with logins.
 */
class CliSignInPolicyStartupIT {

    @Test
    void theApplicationRefusesToStartWhenTheCeilingIsNotLongerThanTheIdleRuler() {
        Throwable thrown = catchThrowable(() -> new SpringApplicationBuilder(
                        SkillMasterServerApplication.class)
                .profiles("test")
                // Not because one would be taken — the failure lands before a connector is bound — but
                // so that a future change letting the context get further cannot turn this into a race
                // with a dev server.
                .run("--server.port=0",
                        "--skillmaster.sessions.idle=24h",
                        "--skillmaster.sessions.absolute=24h")
                .close());

        assertThat(thrown)
                .as("a ceiling no longer than the idle ruler must stop the application, not disable a"
                        + " ruler silently")
                .isNotNull();
        assertThat(says(thrown, "the login credential's absolute ceiling"))
                .as("the refusal has to be this credential's, and not the session's rule answering"
                        + " first; got: %s", chain(thrown))
                .isTrue();
    }

    /**
     * The third number, which belongs to this credential alone.
     *
     * <p><strong>{@code skillmaster.tokens.activity-window} is read by exactly one bean</strong>
     * ({@link TokenSecurityConfig#cliSignInPolicy}), so unlike the pair above there is nothing for this
     * refusal to be confused with — which is also why the case can use the honest arrangement: the
     * idle ruler left at the value the deployment ships, and only the window moved past a tenth of it.
     *
     * <p>Three hours against the default day is well past the tenth the record documents (a window of
     * 2h24m would be the boundary), and it is a configuration somebody could plausibly reach for: the
     * window is how early the idle ruler may fire, so shortening the idle ruler to an hour while
     * leaving the window at this value is the mistake the rule exists for.
     */
    @Test
    void theApplicationRefusesToStartWhenTheSlideWindowIsMoreThanATenthOfTheIdleRuler() {
        Throwable thrown = catchThrowable(() -> new SpringApplicationBuilder(
                        SkillMasterServerApplication.class)
                .profiles("test")
                .run("--server.port=0", "--skillmaster.tokens.activity-window=PT3H")
                .close());

        assertThat(thrown)
                .as("a slide window above a tenth of the idle ruler must stop the application")
                .isNotNull();
        assertThat(says(thrown, "the login credential's slide window"))
                .as("the refusal has to name the window; got: %s", chain(thrown))
                .isTrue();
    }

    /**
     * The control, and the one pin on the third number.
     *
     * <p><strong>Two things, and the second is why it is here rather than in a file of its own.</strong>
     * It shows the application does come up once the numbers are consistent, so neither refusal above
     * can be passing on a typo in the arguments passed to it; and it records what this deployment
     * actually ships. The first two are M1's numbers and are pinned in
     * {@code SessionPolicyStartupIT.theDeploymentShipsTheTwoRulersTheDesignSpecifies} — what nothing
     * else asserts is {@code activity-window}, which is this credential's own and is read back here
     * from the bean the request path uses.
     */
    @Test
    void theDeploymentBootsAndShipsTheThreeNumbersTheDesignNames() {
        try (ConfigurableApplicationContext context = boot()) {
            CliSignInPolicy policy = context.getBean(CliSignInPolicy.class);
            assertThat(policy.idle()).as("the idle ruler, which is the session's (v4 Q1)")
                    .isEqualTo(Duration.ofHours(24));
            assertThat(policy.absolute()).as("the ceiling, which is the session's too")
                    .isEqualTo(Duration.ofDays(7));
            assertThat(policy.slideWindow()).as("the sampling window, which is this credential's alone")
                    .isEqualTo(Duration.ofMinutes(5));
        }
    }

    /** Starts the real application, the way a deployment does, and hands back the running context. */
    private static ConfigurableApplicationContext boot(String... args) {
        String[] all = {"--server.port=0"};
        String[] withPort = new String[1 + args.length];
        System.arraycopy(all, 0, withPort, 0, 1);
        System.arraycopy(args, 0, withPort, 1, args.length);
        return new SpringApplicationBuilder(SkillMasterServerApplication.class)
                .profiles("test")
                .run(withPort);
    }

    /** True when any exception in the chain carries this text — the message, wherever it was wrapped. */
    private static boolean says(Throwable thrown, String text) {
        return chain(thrown).contains(text);
    }

    private static String chain(Throwable thrown) {
        StringBuilder all = new StringBuilder();
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            all.append(current.getClass().getSimpleName()).append(": ").append(current.getMessage())
                    .append('\n');
        }
        return all.toString();
    }
}
