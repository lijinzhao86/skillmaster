package com.skillmasterai.usecase.sync;

/** What listing a container's direct children produced. */
public sealed interface ChildrenResult permits ChildrenOk, FetchFailed {
}
