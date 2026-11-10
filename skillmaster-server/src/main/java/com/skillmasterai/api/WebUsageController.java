package com.skillmasterai.api;

import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.modules.activity.UsageHistory;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.usecase.ReadSkillUsageUseCase;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console's usage page (v58). Session-authenticated, same shapes as the API plane and the
 * same rule: the account comes from the session, never from the request.
 */
@RestController
@RequestMapping(path = "/web/usage")
class WebUsageController {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 100;

    private final UsageHistory history;
    private final ReadSkillUsageUseCase skillUsage;

    WebUsageController(UsageHistory history, ReadSkillUsageUseCase skillUsage) {
        this.history = history;
        this.skillUsage = skillUsage;
    }

    @GetMapping(path = "/interactions", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<UsageController.Interactions> interactions(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String skill, Authentication caller) {
        int wantedPage = page == null ? 1 : page;
        int wantedLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (wantedPage < 1 || wantedLimit < 1 || wantedLimit > MAX_LIMIT) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "page must be at least 1 and limit between 1 and " + MAX_LIMIT);
        }
        UsageHistory.Window window = UsageWindows.parse(since, until, skill);
        UsageHistory.Page result = history.interactions(userId(caller), wantedPage, wantedLimit,
                window);
        return ResponseEntity.ok(new UsageController.Interactions(
                result.items().stream().map(UsageController.Interactions::of).toList(),
                result.total(), result.page(), result.pageSize(), window.days(), window.since(),
                window.until(), window.skill(), history.hasAny(userId(caller))));
    }

    @GetMapping(path = "/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<UsageController.Stats> stats(@RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String skill, Authentication caller) {
        UsageHistory.Window window = UsageWindows.parse(since, until, skill);
        UsageHistory.Stats result = history.stats(userId(caller), window);
        return ResponseEntity.ok(new UsageController.Stats(window.days(), window.since(),
                window.until(), window.skill(), result.calls(), result.searches(),
                result.converted(), result.missed(), result.skills(), result.latest(),
                result.top().stream()
                        .map(top -> new UsageController.Stats.Top(top.skill(), top.calls(),
                                top.last()))
                        .toList()));
    }

    @GetMapping(path = "/skills/{namespace}/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<UsageController.SkillUsage> skill(@PathVariable String namespace,
            @PathVariable String name, @RequestParam(required = false) String since,
            @RequestParam(required = false) String until, Authentication caller) {
        UsageHistory.Window window = UsageWindows.parse(since, until, null);
        UsageHistory.OwnerUsage result = skillUsage.ownerUsage(namespace, name, subject(caller),
                        window)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(UsageController.SkillUsage.of(namespace + "/" + name, result,
                window));
    }

    @GetMapping(path = "/skills/{namespace}/{name}/users",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<UsageController.Users> users(@PathVariable String namespace,
            @PathVariable String name, @RequestParam(required = false) String since,
            @RequestParam(required = false) String until, Authentication caller) {
        UsageHistory.Window window = UsageWindows.parse(since, until, null);
        UsageHistory.OwnerUsage result = skillUsage.ownerUsage(namespace, name, subject(caller),
                        window)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(new UsageController.Users(window.days(), window.since(),
                window.until(), result.publicSkill(), UsageController.SkillUsage.usersOf(result)));
    }

    @GetMapping(path = "/skills/{namespace}/{name}/mine",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<UsageController.Mine> mine(@PathVariable String namespace,
            @PathVariable String name, @RequestParam(required = false) String since,
            @RequestParam(required = false) String until, Authentication caller) {
        UsageHistory.Window window = UsageWindows.parse(since, until, null);
        UsageHistory.Mine result = skillUsage.mine(namespace, name, subject(caller), window)
                .orElseThrow(SkillNotFoundException::new);
        return ResponseEntity.ok(new UsageController.Mine(namespace + "/" + name, window.days(),
                window.since(), window.until(), result.calls(), result.versions(),
                result.latest(), result.byVersion().stream()
                        .map(row -> new UsageController.Mine.Version(row.version(), row.state(),
                                row.calls(), row.last()))
                        .toList()));
    }

    private static AuthenticatedSubject subject(Authentication caller) {
        return AuthenticatedSubject.ofSession(caller.getName());
    }

    private static String userId(Authentication caller) {
        return subject(caller).userId();
    }
}
