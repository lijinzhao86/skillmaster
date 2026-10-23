package com.skillmasterai.modules.skill;

import com.skillmasterai.modules.skill.internal.TagRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * M12's tag seam: both layers, the definition store, and the words a card shows (v14, ADR 0083).
 *
 * <p><strong>The two layers are not one abstraction.</strong> A public tag is an author's word on
 * a skill, identified by its normalized form; a private tag is a definition owned by one person and
 * attached to skills. They share spelling rules and nothing else, so they share {@link TagNames}
 * and are otherwise separate methods here.
 *
 * <p><strong>Spelling is decided once, here.</strong> {@link #parseName} is the only place the
 * 1–24 code point rule and the normalization run; the CLI and the browser send what a person typed.
 * {@link #parseDescription} turns a blank submission into {@code null} — the database's one way of
 * saying "no sentence" (TD Q6).
 *
 * <p>The ceilings are checked by the use cases inside their transaction, because they are cross-row
 * counts; {@link #lockDefinitionsOf} and {@link #lockTagsOf} are the serialization points.
 */
public final class SkillTagService {

    /** Per skill, counting only effective attachments (PRD §上限). */
    public static final int MAX_MY_TAGS_PER_SKILL = 20;

    /** Per skill, public tags are the author's words and a short list (PRD §公开标签). */
    public static final int MAX_PUBLIC_TAGS_PER_SKILL = 10;

    /** Per account, including definitions whose only attachments are dormant. */
    public static final int MAX_DEFINITIONS = 100;

    /** The description's limit, in code points (PRD §我的标签). */
    public static final int MAX_DESCRIPTION_CODE_POINTS = 200;

    private final TagRepository repository;
    private final SkillCategoryService categories;

    public SkillTagService(TagRepository repository, SkillCategoryService categories) {
        this.repository = repository;
        this.categories = categories;
    }

    /* ---- spelling ---- */

    /** @throws SkillRequestException field {@code name} for an empty or over-long word */
    public TagNames.Named parseName(String raw) {
        return TagNames.parse(raw);
    }

    /**
     * @return the sentence, or null when the submission was blank. Long input is refused rather
     *         than truncated: a person who wrote 201 code points should know which one was kept
     */
    public String parseDescription(String raw) {
        if (raw == null) {
            return null;
        }
        String description = raw.strip();
        if (description.isEmpty()) {
            return null;
        }
        if (description.codePointCount(0, description.length()) > MAX_DESCRIPTION_CODE_POINTS) {
            throw new SkillRequestException("description", "too_long");
        }
        return description;
    }

    /* ---- locks ---- */

    public void lockDefinitionsOf(String userId) {
        repository.lockDefinitionsOf(userId);
    }

    public void lockTagsOf(String skillId) {
        repository.lockTagsOf(skillId);
    }

    /* ---- public tags ---- */

    public List<PublicTagView> publicTagsOfSkill(String skillId) {
        return repository.publicTagsOfSkill(skillId);
    }

    public Map<String, List<PublicTagView>> publicTagsOfSkills(Collection<String> skillIds) {
        return repository.publicTagsOfSkills(skillIds);
    }

    public boolean publicTagExists(String skillId, String key) {
        return repository.publicTagExists(skillId, key);
    }

    public void attachPublic(String skillId, String key, String name, String at) {
        repository.attachPublic(skillId, key, name, at);
    }

    public boolean detachPublic(String skillId, String key) {
        return repository.detachPublic(skillId, key);
    }

    public long countPublicTags(String skillId) {
        return repository.countPublicTags(skillId);
    }

    public List<PublicTagRow> publicVocabulary(String q, int limit, List<String> afterKey) {
        return repository.publicVocabulary(q, limit, afterKey);
    }

    public List<ManagedPublicTagRow> managedPublicTags(Caller caller, String q) {
        return repository.publicTagsUsedByCreatedSkills(caller, q);
    }

    /* ---- private definitions ---- */

    public Optional<Definition> findDefinitionByName(String userId, String nameKey) {
        return repository.findDefinitionByName(userId, nameKey);
    }

    public Optional<Definition> findDefinition(String userId, String id) {
        return repository.findDefinition(userId, id);
    }

    public long countDefinitions(String userId) {
        return repository.countDefinitions(userId);
    }

    public void insertDefinition(String id, String userId, String name, String nameKey,
            String description, String at) {
        repository.insertDefinition(id, userId, name, nameKey, description, at);
    }

    public void updateName(String id, String name, String nameKey, String at) {
        repository.updateName(id, name, nameKey, at);
    }

    public void updateDescription(String id, String description, String at) {
        repository.updateDescription(id, description, at);
    }

    public boolean deleteDefinition(String userId, String id) {
        return repository.deleteDefinition(userId, id);
    }

    public List<MyTagRow> myTags(Caller caller, String q) {
        return repository.definitionsWithUsage(caller, q);
    }

    /** One definition resolved by its normalized word, or empty. */
    public List<MyTagRow> myTagByKey(Caller caller, String nameKey) {
        return repository.definitionsWithUsage(caller, null, nameKey);
    }

    /** Whether a normalized word is already a shelf's name. Shared by both tag layers. */
    public boolean categoryNameTaken(String nameKey) {
        return categories.nameTaken(nameKey);
    }

    /**
     * Refuses a word that is already taken, by this person's definitions or by a shelf.
     *
     * @param exceptId the definition being renamed, so its own word does not count against it
     * @throws SkillRequestException {@code name_taken} / {@code category_name_taken}
     */
    public void requireNameAvailable(String userId, String nameKey, String exceptId) {
        Optional<Definition> existing = repository.findDefinitionByName(userId, nameKey);
        if (existing.isPresent() && !existing.get().id().equals(exceptId)) {
            throw new SkillRequestException("name", "name_taken");
        }
        if (categoryNameTaken(nameKey)) {
            throw new SkillRequestException("name", "category_name_taken");
        }
    }

    /**
     * Refuses the 101st definition. The caller holds the user's advisory lock, so this count and the
     * insert that follows cannot interleave with another request's.
     *
     * @throws SkillRequestException {@code tag_limit_reached}
     */
    public void requireDefinitionUnderLimit(String userId) {
        if (repository.countDefinitions(userId) >= MAX_DEFINITIONS) {
            throw new SkillRequestException("name", "tag_limit_reached");
        }
    }

    /* ---- private attachments ---- */

    public List<MyTagView> myTagsOnSkill(String userId, String skillId) {
        return repository.myTagsOnSkill(userId, skillId);
    }

    public Map<String, List<MyTagView>> myTagsOfSkills(String userId, Collection<String> skillIds) {
        return repository.myTagsOfSkills(userId, skillIds);
    }

    public boolean attachmentExists(String userId, String definitionId, String skillId) {
        return repository.attachmentExists(userId, definitionId, skillId);
    }

    public void attachMy(String definitionId, String skillId, String at) {
        repository.attachMy(definitionId, skillId, at);
    }

    public boolean detachMy(String userId, String definitionId, String skillId) {
        return repository.detachMy(userId, definitionId, skillId);
    }

    public long countAttachments(String userId, String skillId) {
        return repository.countAttachments(userId, skillId);
    }

    /* ---- views ---- */

    public record PublicTagView(String key, String name) {
    }

    public record MyTagView(String id, String name, String description) {
    }

    public record CategoryView(String key, String name) {
    }

    public record CardTags(List<PublicTagView> publicTags, List<MyTagView> myTags) {
        public CardTags {
            publicTags = List.copyOf(publicTags);
            myTags = List.copyOf(myTags);
        }
    }

    /** The three groups of the information page, in the order the product shows them. */
    public record TagGroups(CategoryView category, List<PublicTagView> publicTags, MyTags myTags,
            LibraryView library) {
        public TagGroups {
            publicTags = List.copyOf(publicTags);
        }
    }

    /**
     * The information page's join/leave button, decided from one row (v14).
     *
     * <p><strong>Three facts, not one enum.</strong> {@code source} is what the row shows;
     * {@code joinable} is whether "加入" would do anything; {@code canRemove} is whether the
     * reference exists <em>and</em> no better source covers it. A joined-then-granted skill shows
     * "shared" and hides the button while the reference is still there — the CLI can still remove
     * it, but the page does not offer that.
     *
     * @param source   created / shared / joined, or null when the caller has no relationship
     * @param active   whether the library entry is effective now
     * @param canRemove displayed source is "joined"; a created or shared entry has no remove affordance
     */
    public record LibraryView(String source, boolean active, boolean joinable,
            boolean canRemove) {
    }

    /**
     * @param readable false when the private group must not be shown at all
     * @param reason   null when readable; {@code not_in_library} or {@code dormant} otherwise
     */
    public record MyTags(boolean readable, String reason, List<MyTagView> tags) {
        public MyTags {
            tags = List.copyOf(tags);
        }
    }

    public record Definition(String id, String name, String nameKey, String description) {
    }

    /**
     * @param usedIn    effective attachments: what the CLI's 用在 counts
     * @param dormantIn attachments whose library entry is not effective now. Retained, not shown in
     *                  a list, and counted separately so the delete dialog can say how many the
     *                  person cannot currently see (v14)
     */
    public record MyTagRow(String id, String name, String description, long usedIn,
            long dormantIn) {
    }

    public record PublicTagRow(String key, String name, long publicTotal) {
    }

    public record ManagedPublicTagRow(String key, String name, long usedIn) {
    }
}
