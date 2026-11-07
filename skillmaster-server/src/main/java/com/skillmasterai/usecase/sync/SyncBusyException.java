package com.skillmasterai.usecase.sync;

/** A run is already in progress; the caller answers 409 rather than starting a second one. */
public final class SyncBusyException extends RuntimeException {

    public SyncBusyException() {
        super("a sync run is already in progress");
    }
}
