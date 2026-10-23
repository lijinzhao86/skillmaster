package com.skillmasterai.api;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.usecase.LibraryListUseCase;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console's "我的 skill" listing (v14).
 *
 * <p><strong>The complete library, not the consumable one.</strong> It includes drafts, offline
 * entries and the author's deleted list; the CLI's default view is the narrower one. The address
 * says which by {@code show}, and the deleted list is its own mode: tag filters are removed from
 * the address and ignored here, so a filter the address still carries cannot select nothing
 * (TD §传输契约).
 *
 * <p>The three counters are always sent, even when the filters match nothing: they are how the page
 * tells its three empty states apart.
 */
@RestController
@RequestMapping(path = WebLibraryController.BASE)
class WebLibraryController {

    static final String BASE = "/web/library";

    private final LibraryListUseCase library;

    WebLibraryController(LibraryListUseCase library) {
        this.library = library;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.WebLibrary> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> tag,
            @RequestParam(name = "my-tag", required = false) List<String> myTag,
            @RequestParam(name = "public-tag", required = false) List<String> publicTag,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String show,
            @RequestParam(required = false) Integer p,
            Authentication caller) {
        LibraryListUseCase.Result result = library.web(q, tag, myTag, publicTag, sort, show, p,
                AuthenticatedSubject.ofSession(caller.getName()));
        int page = p == null || p < 1 ? 1 : p;
        return ResponseEntity.ok(OrganizationResponses.WebLibrary.of(result, page));
    }
}
