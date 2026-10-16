package com.skillmasterai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.skillmasterai.SkillMasterServerApplication;
import com.skillmasterai.modules.account.SessionPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.session.autoconfigure.SessionProperties;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * T11 · a pair of rulers that contradicts itself stops the application.
 *
 * <p><strong>The whole application, started the way a deployment starts it.</strong> A unit test on
 * {@link com.skillmasterai.modules.account.SessionPolicy} would prove the record refuses the pair;
 * what accepting #9 asks for is that the refusal reaches a deployment — that the number in a
 * property file cannot disable one of the two rulers without anybody noticing. So this boots the
 * real context and asserts it does not come up.
 *
 * <p><strong>Both numbers are set explicitly, and the idle one is set to what the property file
 * already says.</strong> Overriding only the ceiling would leave the test resting on the default
 * being longer than an hour, which is a fact about {@code application.yml} rather than about the
 * rule — and the rule is what is being tested.
 *
 * <p><strong>They are passed as command-line arguments, and that is not stylistic.</strong>
 * {@code application.yml} declares both properties, and a builder's {@code properties(...)} are
 * <em>default</em> properties — the bottom of Boot's precedence order, below config data — so that
 * form cannot override a value the file sets. Command-line arguments are the top of it, and they are
 * also what a deployment would actually use.
 *
 * <p>It reads the failure by walking the causes rather than by asserting on the outermost one: what
 * the framework wraps a binding failure in is its business, and a test that pinned that shape would
 * fail on a Spring upgrade for a reason that has nothing to do with sessions.
 */
class SessionPolicyStartupIT {

    @Test
    void theContextRefusesToStartWhenTheAbsoluteCeilingIsShorterThanTheIdleWindow() {
        Throwable thrown = catchThrowable(() -> new SpringApplicationBuilder(
                        SkillMasterServerApplication.class)
                .profiles("test")
                // The port is not because one would be taken — the binding fails before a connector
                // is bound — but so that a future change letting the context get further cannot turn
                // this test into a race with a dev server.
                .run("--server.port=0",
                        "--skillmaster.sessions.idle=24h",
                        "--skillmaster.sessions.absolute=PT1H")
                .close());

        assertThat(thrown)
                .as("a ceiling below the idle window must stop the application, not disable a ruler")
                .isNotNull();
        assertThat(says(thrown, "absolute ceiling"))
                .as("the refusal has to say which pair is wrong; got: %s", chain(thrown))
                .isTrue();
    }

    /**
     * The idle window has one authority, and both readers get it from there.
     *
     * <p>{@code spring.session.timeout} is the value the framework actually expires a session by;
     * {@code skillmaster.sessions.idle} is the one this module validates against its ceiling. They
     * are the same fact, so {@code application.yml} writes the second as a reference to the first
     * rather than repeating the number — and this is the test that says the reference resolves.
     * Changing one property has to move both, or the two rulers would be numbered by two files.
     */
    @Test
    void theFrameworksIdleTimeoutIsTheSamePropertyAsThisModules() {
        try (ConfigurableApplicationContext context = boot("--skillmaster.sessions.idle=PT12H")) {
            assertThat(context.getBean(SkillmasterProperties.class).sessions().idle())
                    .isEqualTo(Duration.ofHours(12));
            assertThat(context.getBean(SessionProperties.class).getTimeout())
                    .as("spring.session.timeout must follow skillmaster.sessions.idle, not a second"
                            + " number in application.yml")
                    .isEqualTo(Duration.ofHours(12));
        }
    }

    /**
     * The two numbers this deployment actually ships, pinned where retuning them would be noticed.
     *
     * <p><strong>Nothing else in the suite asserts them.</strong> T1–T4 write {@code creation_time}
     * and {@code last_access_time} themselves, so their arrangements hold for any pair of durations,
     * and the test above overrides both properties and only proves they agree with each other. Retune
     * {@code application.yml} to a seven-day idle window and a thirty-day ceiling and every case
     * stays green — which would be this version silently not delivering what it promises.
     */
    @Test
    void theDeploymentShipsTheTwoRulersTheDesignSpecifies() {
        try (ConfigurableApplicationContext context = boot()) {
            SessionPolicy policy = context.getBean(SessionPolicy.class);
            assertThat(policy.idle()).as("the idle ruler (ADR 0043)").isEqualTo(Duration.ofHours(24));
            assertThat(policy.absolute()).as("the absolute ceiling (ADR 0043)")
                    .isEqualTo(Duration.ofDays(7));
            // And the framework is timed by the same property, so the ruler Spring Session enforces
            // is the one this module validated against its ceiling.
            assertThat(context.getBean(SessionProperties.class).getTimeout())
                    .isEqualTo(Duration.ofHours(24));
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
