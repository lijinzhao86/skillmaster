package com.skillmasterai.modules.skill;

import com.skillmasterai.modules.skill.internal.CategoryRepository;
import com.skillmasterai.modules.skill.internal.LibraryRepository;
import com.skillmasterai.modules.skill.internal.SkillAccessRequestRepository;
import com.skillmasterai.modules.skill.internal.SkillCatalogRepository;
import com.skillmasterai.modules.skill.internal.SkillEmbeddingTextSource;
import com.skillmasterai.modules.skill.internal.SkillGrantRepository;
import com.skillmasterai.modules.skill.internal.SkillInvitationRepository;
import com.skillmasterai.modules.skill.internal.SkillRelationshipQueries;
import com.skillmasterai.modules.skill.internal.SkillRepository;
import com.skillmasterai.modules.skill.internal.TagRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires M12 — skill metadata (ADR 0053).
 *
 * <p>Split from {@code VersionConfiguration} when the two modules came apart: a configuration
 * class that wired both would be the one place where a cross-module dependency could be introduced
 * without either module's package saying so.
 */
@Configuration(proxyBeanMethods = false)
@org.springframework.scheduling.annotation.EnableScheduling
public class SkillConfiguration {

    @Bean
    SkillRepository skillRepository(JdbcClient jdbc) {
        return new SkillRepository(jdbc);
    }

    @Bean
    SkillRelationshipQueries skillRelationshipQueries(JdbcClient jdbc) {
        return new SkillRelationshipQueries(jdbc);
    }

    @Bean
    SkillGrantRepository skillGrantRepository(JdbcClient jdbc) {
        return new SkillGrantRepository(jdbc);
    }

    @Bean
    SkillAccessRequestRepository skillAccessRequestRepository(JdbcClient jdbc) {
        return new SkillAccessRequestRepository(jdbc);
    }

    @Bean
    SkillInvitationRepository skillInvitationRepository(JdbcClient jdbc) {
        return new SkillInvitationRepository(jdbc);
    }

    /** The seam the content modules receive (ADR 0053). */
    @Bean
    SkillMetadataService skillMetadataService(SkillRepository skills,
            SkillRelationshipQueries relationships) {
        return new SkillMetadataService(skills, relationships);
    }

    /** Sharing's public face (ADR 0034). */
    @Bean
    SkillSharingService skillSharingService(SkillRepository skills, SkillGrantRepository grants) {
        return new SkillSharingService(skills, grants);
    }

    /**
     * Asking for a rung's public face (ADR 0052). Needs the grant repository as well as the request
     * one: approving writes a grant through the same upsert a direct share uses.
     */
    @Bean
    SkillAccessService skillAccessService(SkillRepository skills, SkillGrantRepository grants,
            SkillAccessRequestRepository requests, SkillRelationshipQueries relationships) {
        return new SkillAccessService(skills, grants, requests, relationships);
    }

    /** Sharing's invite half (v45, ADR 0138). */
    @Bean
    SkillInvitationService skillInvitationService(SkillRepository skills,
            SkillGrantRepository grants, SkillInvitationRepository invitations) {
        return new SkillInvitationService(skills, grants, invitations);
    }

    @Bean
    com.skillmasterai.modules.skill.internal.SourceRepository sourceRepository(JdbcClient jdbc) {
        return new com.skillmasterai.modules.skill.internal.SourceRepository(jdbc);
    }

    /** The adapters are the only place a platform is named; add one here and nowhere else. */
    @Bean
    SourceRegistry sourceRegistry(tools.jackson.databind.ObjectMapper json) {
        return new SourceRegistry(java.util.List.of(
                new OfficialSourceAdapter(),
                new GitHubSourceAdapter(json)));
    }

    @Bean
    SourceService sourceService(com.skillmasterai.modules.skill.internal.SourceRepository repository,
            SourceRegistry registry, tools.jackson.databind.ObjectMapper json) {
        return new SourceService(repository, registry, json);
    }

    @Bean
    SourceRefreshJob sourceRefreshJob(
            com.skillmasterai.modules.skill.internal.SourceRepository repository,
            SourceService sources) {
        return new SourceRefreshJob(repository, sources);
    }

    @Bean
    SkillCatalogRepository skillCatalogRepository(JdbcClient jdbc) {
        return new SkillCatalogRepository(jdbc);
    }

    @Bean
    SkillCatalogService skillCatalogService(SkillCatalogRepository repository) {
        return new SkillCatalogService(repository);
    }

    /**
     * M13's text seam, implemented here because the text is on this module's table (v19). Declared
     * unconditionally, including when retrieval is switched off: the interface has no side effects,
     * and a bean that disappears with a flag is a wiring puzzle rather than a feature.
     */
    @Bean
    com.skillmasterai.modules.retrieval.EmbeddingTextSource embeddingTextSource(JdbcClient jdbc) {
        return new SkillEmbeddingTextSource(jdbc);
    }

    /* ---- v14: library, tags and categories ---- */

    @Bean
    LibraryRepository libraryRepository(JdbcClient jdbc) {
        return new LibraryRepository(jdbc);
    }

    @Bean
    TagRepository tagRepository(JdbcClient jdbc) {
        return new TagRepository(jdbc);
    }

    @Bean
    CategoryRepository categoryRepository(JdbcClient jdbc) {
        return new CategoryRepository(jdbc);
    }

    @Bean
    SkillLibraryService skillLibraryService(LibraryRepository repository) {
        return new SkillLibraryService(repository);
    }

    @Bean
    SkillCategoryService skillCategoryService(CategoryRepository repository) {
        return new SkillCategoryService(repository);
    }

    @Bean
    SkillTagService skillTagService(TagRepository repository, SkillCategoryService categories) {
        return new SkillTagService(repository, categories);
    }
}
