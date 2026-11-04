package com.skillmasterai.api;

/**
 * The body of an approval.
 *
 * @param role the rung to grant, or null to grant the one that was requested (v45, ADR 0140)
 */
record NotificationApprovalRequest(String role) {
}
