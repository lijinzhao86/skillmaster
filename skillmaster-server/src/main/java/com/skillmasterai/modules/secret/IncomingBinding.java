package com.skillmasterai.modules.secret;

/**
 * One variable this user receives from somebody else's credential, through a live share.
 *
 * <p><strong>Why this is a type of its own rather than a {@link CredentialBinding}.</strong> A
 * binding says "this credential reaches this skill through this variable"; it does not say whose
 * credential it is, and for a grantee that is the whole question — the skill settings pane shows
 * 「共享自 hexu」 beside the variable, and nobody can decide whether to use such a variable without
 * knowing who is lending it. {@code ownerUserId} is the one field that makes the row legible; the
 * handle is resolved by the use case, because M16 may not read M1.
 *
 * <p>It carries the credential's name, which is the owner's label for it. That is deliberate: the
 * grantee is already receiving the value itself, and a row that said 「来自 hexu 的凭据」 without
 * saying which one would be unusable the moment somebody shares two.
 *
 * <p>There is no value here and there never will be — this type is a listing row.
 *
 * <p><b>It carries the owner's usage as well.</b> The skill pane draws five columns for every row,
 * and the usage column is one of them — an empty cell there reads as "nothing was measured" rather
 * than "somebody else's credential". The grantee already holds the value; knowing how close the
 * day's quota is tells them why a run may stop working, and revealing it costs nothing that the
 * value itself has not already cost.
 *
 * @param credentialId the owner's credential; the grantee cannot address it directly
 * @param ownerUserId  who lent it; the use case turns this into a handle
 * @param perDay       the credential's daily lease ceiling
 * @param used         leases taken today, counted across everybody the credential serves
 */
public record IncomingBinding(String credentialId, String name, String ownerUserId, String skillId,
        String envName, int perDay, int used) {
}
