package com.skillmasterai.modules.version.internal;

import com.skillmasterai.modules.blob.BlobStore;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reclaims blobs nothing references any more — the half of it that M7 owns.
 *
 * <p>Reference counting is M7's: {@code version_file} is what references a blob, and M7 owns that
 * table (§2.5). The rows being deleted belong to M6, so this class only decides <em>what</em> is
 * still in use and hands that set to {@link BlobStore#deleteUnreferenced}. One SQL statement
 * naming both modules' tables would be the rule-1 violation this split exists to avoid.
 *
 * <p><strong>In P0 this deletes nothing, and that is the correct behaviour, not an oversight.</strong>
 * Blobs are only ever orphaned by a version disappearing, and no P0 operation removes a
 * {@code skill_version} row: publishing adds one, republishing identical content is a no-op, and
 * soft delete only sets {@code deleted_at} while leaving every version — and therefore every
 * reference — in place (§3.3 point 5). The sweep is called on every version change so that the
 * transaction boundary §3.3 point 5 demands (ADR 0010 decision 4: reclamation and version change
 * in one transaction, never a cross-system reconciliation) is established now rather than
 * retrofitted, and so P2's version pruning has somewhere to land.
 *
 * <p>Worth knowing before P2 leans on this: the referenced set is every file of every version that
 * has ever been kept, so a repository with a large history passes a large set through the seam on
 * every publish. That is fine at P0's scale and is an open question for whenever version pruning
 * arrives.
 */
public final class BlobGc {

    private final JdbcClient jdbc;
    private final BlobStore blobs;

    public BlobGc(JdbcClient jdbc, BlobStore blobs) {
        this.jdbc = jdbc;
        this.blobs = blobs;
    }

    /**
     * Opens the section {@link #sweep} reasons about. Must be called <strong>before</strong> anything
     * writes {@code version_file} — see the last paragraph for why the order is not negotiable.
     *
     * <p>It takes {@code SHARE ROW EXCLUSIVE} on that table, and the mode is picked for two
     * properties. It conflicts with the {@code ROW EXCLUSIVE} an {@code INSERT} takes, so no other
     * transaction can commit a new reference inside the sweep's window — that is the hole this
     * closes. And it conflicts with <em>itself</em>, so two writers genuinely exclude each other
     * rather than both walking in.
     *
     * <p><strong>Taking it here rather than at the sweep is the point.</strong> By the time a
     * publish reaches its sweep it has already inserted into {@code version_file} and so already
     * holds {@code ROW EXCLUSIVE}. A lock requested at that moment deadlocks two publishes of
     * <em>different</em> skills: each waits on the other's insert lock, and neither can commit.
     * (The same skill cannot — {@code findOrCreate} serialises those earlier, which is why the
     * existing concurrency test never saw this.) Requested first, no transaction ever holds the
     * insert lock while waiting for this one.
     *
     * <p>Reads are unaffected: {@code SHARE ROW EXCLUSIVE} does not conflict with {@code ACCESS
     * SHARE}, so the read API never waits on a publish. What it does cost is that publishes are
     * serialised globally rather than per skill — nothing at P0's scale, and the price of a
     * reclamation that cannot disagree with itself.
     *
     * <p>Requires an active transaction, which is the caller's to provide: the annotation belongs to
     * the use case, because a transaction spanning modules is the use case's. Outside one PostgreSQL
     * refuses the statement outright ("LOCK TABLE can only be used in transaction blocks").
     */
    public void beginExclusiveWrite() {
        jdbc.sql("LOCK TABLE version_file IN SHARE ROW EXCLUSIVE MODE").update();
    }

    /**
     * Reclaims blobs nothing references any more.
     *
     * <p>Only meaningful inside a {@link #beginExclusiveWrite} section. The two statements below
     * take separate snapshots, and under READ COMMITTED a publish committing between them would make
     * its blob visible to the {@code DELETE} while its {@code version_file} row was still absent
     * from the set the {@code SELECT} saw — so the sweep would try to delete bytes a committed
     * version points at. (The foreign key would refuse that delete rather than lose the bytes, so
     * the cost would be a failed publish, not corruption. Both are worth avoiding.)
     *
     * @return how many blobs were reclaimed
     */
    public int sweep() {
        Set<String> referenced = Set.copyOf(
                jdbc.sql("SELECT DISTINCT blob_sha256 FROM version_file").query(String.class).list());
        return blobs.deleteUnreferenced(referenced);
    }
}
