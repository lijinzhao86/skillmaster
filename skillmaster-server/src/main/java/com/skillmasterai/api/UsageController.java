package com.skillmasterai.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.modules.activity.UsageHistory;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.usecase.ReadSkillUsageUseCase;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The personal usage history on the API plane (v58).
 *
 * <p>Only the caller's own rows: the account id comes from the credential and is never a request
 * parameter, so there is no value here that could name somebody else. The owner views live on
 * their own paths and answer 404 to everyone else (P3).
 */
@RestController
@RequestMapping(path = "/api/v1/usage")
class UsageController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final UsageHistory history;
    private final ReadSkillUsageUseCase skillUsage;

    UsageController(UsageHistory history, ReadSkillUsageUseCase skillUsage) {
        this.history = history;
        this.skillUsage = skillUsage;
    }

    @GetMapping(path = "/interactions", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Interactions> interactions(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String skill,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        int wantedPage = page == null ? 1 : page;
        int wantedLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (wantedPage < 1 || wantedLimit < 1 || wantedLimit > MAX_LIMIT) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "page must be at least 1 and limit between 1 and " + MAX_LIMIT);
        }
        UsageHistory.Window window = UsageWindows.parse(since, until, skill);
        UsageHistory.Page result = history.interactions(subject.userId(), wantedPage,
                wantedLimit, window);
        return ResponseEntity.ok(new Interactions(
                result.items().stream().map(Interactions::of).toList(), result.total(),
                result.page(), result.pageSize(), window.days(), window.since(), window.until(),
                window.skill(), history.hasAny(subject.userId())));
    }

    @GetMapping(path = "/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Stats> stats(@RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String skill,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        UsageHistory.Window window = UsageWindows.parse(since, until, skill);
        UsageHistory.Stats result = history.stats(subject.userId(), window);
        return ResponseEntity.ok(new Stats(window.days(), window.since(), window.until(),
                window.skill(), result.calls(), result.searches(), result.converted(),
                result.missed(), result.skills(), result.latest(), result.top().stream()
                        .map(top -> new Stats.Top(top.skill(), top.calls(), top.last()))
                        .toList()));
    }

    /** The owner's view. Anyone else gets the same 404 an unknown skill gets. */
    @GetMapping(path = "/skills/{namespace}/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SkillUsage> skill(@PathVariable String namespace, @PathVariable String name,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        UsageHistory.Window window = UsageWindows.parse(since, until, null);
        UsageHistory.OwnerUsage result = skillUsage.ownerUsage(namespace, name, subject, window)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(SkillUsage.of(namespace + "/" + name, result, window));
    }

    /** The users table alone — the same rows the owner view carries. */
    @GetMapping(path = "/skills/{namespace}/{name}/users",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Users> users(@PathVariable String namespace, @PathVariable String name,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        UsageHistory.Window window = UsageWindows.parse(since, until, null);
        UsageHistory.OwnerUsage result = skillUsage.ownerUsage(namespace, name, subject, window)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(new Users(window.days(), window.since(), window.until(),
                result.publicSkill(), SkillUsage.usersOf(result)));
    }

    /** What this person did with this skill, split by version. */
    @GetMapping(path = "/skills/{namespace}/{name}/mine",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Mine> mine(@PathVariable String namespace, @PathVariable String name,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @AuthenticationPrincipal AuthenticatedSubject subject) {
        UsageHistory.Window window = UsageWindows.parse(since, until, null);
        UsageHistory.Mine result = skillUsage.mine(namespace, name, subject, window)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(new Mine(namespace + "/" + name, window.days(), window.since(),
                window.until(), result.calls(), result.versions(), result.latest(),
                result.byVersion().stream()
                        .map(row -> new Mine.Version(row.version(), row.state(), row.calls(),
                                row.last()))
                        .toList()));
    }

    /**
     * One row. A search carries its results and the calls it led to; a direct call carries the
     * skill and version. The absent half is omitted rather than sent as null, so a client can
     * branch on the field's presence.
     */
    record Interactions(List<Item> items, long total, int page, int page_size, int window_days,
            String since, String until, String skill, boolean has_any) {

        static Item of(UsageHistory.Interaction interaction) {
            if (interaction instanceof UsageHistory.Search search) {
                return new Item("search", search.id(), search.at(), search.query(), search.total(),
                        search.returned(),
                        search.results().stream()
                                .map(result -> new Result(result.skill(), result.rank()))
                                .toList(),
                        search.invocations().stream()
                                .map(invocation -> new Invocation(invocation.skill(),
                                        invocation.version(), invocation.at(), invocation.rank()))
                                .toList(),
                        null, null);
            }
            UsageHistory.Direct direct = (UsageHistory.Direct) interaction;
            return new Item("direct", direct.id(), direct.at(), null, null, null, null, null,
                    direct.skill(), direct.version());
        }

        /**
         * @param id the row's {@code activity_event.id}; a stable key for the client, not an
         *           address (the window is 90 days and one row is never fetched on its own)
         */
        record Item(String kind, long id, String at,
                @JsonInclude(JsonInclude.Include.NON_NULL) String query,
                @JsonInclude(JsonInclude.Include.NON_NULL) Long total,
                @JsonInclude(JsonInclude.Include.NON_NULL) Integer returned,
                @JsonInclude(JsonInclude.Include.NON_NULL) List<Result> results,
                @JsonInclude(JsonInclude.Include.NON_NULL) List<Invocation> invocations,
                @JsonInclude(JsonInclude.Include.NON_NULL) String skill,
                @JsonInclude(JsonInclude.Include.NON_NULL) String version) {
        }

        record Result(String skill, int rank) {
        }

        record Invocation(String skill, String version, String at, int rank) {
        }
    }

    record Stats(int window_days, String since, String until, String skill, long calls,
            long searches, long converted, long missed, long skills, String latest,
            List<Top> top) {

        record Top(String skill, long calls, String last) {
        }
    }

    /** The owner's aggregate and users table. Totals are strings: a bucket or an exact count. */
    record SkillUsage(String skill, int window_days, String since, String until,
            @com.fasterxml.jackson.annotation.JsonProperty("public") boolean publicSkill,
            Totals totals, List<User> users) {

        static SkillUsage of(String address, UsageHistory.OwnerUsage usage,
                UsageHistory.Window window) {
            String calls = usage.publicSkill()
                    ? SkillMetaResponse.callsLabel(usage.callsFloor())
                    : String.valueOf(usage.calls());
            String accounts = usage.publicSkill()
                    ? SkillMetaResponse.accountsLabel(usage.accountsFloor())
                    : String.valueOf(usage.accounts());
            return new SkillUsage(address, window.days(), window.since(), window.until(),
                    usage.publicSkill(),
                    new Totals(calls, accounts, usage.joins(), usage.last()), usersOf(usage));
        }

        static List<User> usersOf(UsageHistory.OwnerUsage usage) {
            return usage.users().stream()
                    .map(user -> new User(user.who(), user.owner(), user.role(), user.invoke(),
                            user.submitted(), user.last()))
                    .toList();
        }

        record Totals(String calls, String accounts, int joins, String last_used) {
        }

        record User(String who, boolean owner, String role, long invoke, long submitted,
                String last) {
        }
    }

    record Users(int window_days, String since, String until,
            @com.fasterxml.jackson.annotation.JsonProperty("public") boolean publicSkill,
            List<SkillUsage.User> users) {
    }

    record Mine(String skill, int window_days, String since, String until, long calls,
            int versions, String latest, List<Version> by_version) {

        record Version(String version, String state, long calls, String last) {
        }
    }
}
