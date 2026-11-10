package com.skillmasterai.modules.activity;

/**
 * The values {@code activity_event.kind} takes (v58).
 *
 * <p>Constants rather than an enum: the column is an open string, and a new kind is a fact the
 * readers can ignore, not a schema change. The list is the contract; adding an entry here is the
 * whole change.
 */
public final class ActivityKinds {

    public static final String SEARCH = "search";
    public static final String BROWSE = "browse";
    public static final String SKILL_VIEW = "skill_view";
    public static final String VERSION_LIST = "version_list";
    public static final String VERSION_VIEW = "version_view";
    public static final String FILE_LIST = "file_list";
    public static final String FILE_READ = "file_read";
    public static final String INVOKE = "invoke";
    public static final String DOWNLOAD = "download";
    public static final String LOGIN = "login";
    public static final String LOGOUT = "logout";

    private ActivityKinds() {
    }
}
