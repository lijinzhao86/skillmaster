package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.version.Caller;

/**
 * Builds the value M7 authorizes against, from the two facts a request already carries.
 *
 * <p>Every use case that touches a skill needs one, and the two halves come from two modules — the
 * user id from M3, the namespace from M4. §2.5 says the use-case layer is where such facts are
 * allowed to meet, so this is the meeting, in one place rather than eight.
 *
 * <p>Not a method on {@code NamespaceService}, tempting as that is: it would make M4 depend on M3
 * for a type it has no other use for, to build a value M7 owns. Three modules arranged around a
 * one-line helper is the shape this is avoiding.
 */
final class Callers {

    private Callers() {
    }

    static Caller of(NamespaceService namespaces, AuthenticatedSubject subject) {
        return new Caller(subject.userId(), namespaces.personalNamespaceOf(subject.userId()).id());
    }
}
