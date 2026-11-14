package com.skillmasterai.ops.jobs;

/**
 * The server answered "no" to a submission: the archive was invalid, or the address already
 * belongs to another origin. The code travels into {@code import_entry.result}'s note.
 */
public class SubmissionRejected extends RuntimeException {

    public SubmissionRejected(String code) {
        super(code);
    }
}
