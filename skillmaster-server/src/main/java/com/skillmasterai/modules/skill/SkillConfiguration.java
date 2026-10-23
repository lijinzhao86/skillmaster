package com.skillmasterai.modules.skill;

import com.skillmasterai.modules.skill.internal.CategoryRepository;
import com.skillmasterai.modules.skill.internal.LibraryRepository;
import com.skillmasterai.modules.skill.internal.SkillAccessRequestRepository;
import com.skillmasterai.modules.skill.internal.SkillCatalogRepository;
import com.skillmasterai.modules.skill.internal.SkillGrantRepository;
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
public class SkillConfiguration {

    @Bean
    SkillRepository skillRepository(JdbcClient jdbc) {
        return new SkillRepository(jdbc);
    }

    @Bean
    SkillGrantRepository skillGrantRepository(JdbcClient jdbc) {
        return new SkillGrantRepository(jdbc);
    }

    @Bean
    SkillAccessRequestRepository skillAccessRequestRepository(JdbcClient jdbc) {
        return new SkillAccessRequestRepository(jdbc);
    }

    /** The seam the content modules receive (ADR 0053). */
    @Bean
    SkillMetadataService skillMetadataService(SkillRepository skills) {
        return new SkillMetadataService(skills);
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
            SkillAccessRequestRepository requests) {
        return new SkillAccessService(skills, grants, requests);
    }

    @Bean
    SkillCatalogRepository skillCatalogRepository(JdbcClient jdbc) {
        return new SkillCatalogRepository(jdbc);
    }

    @Bean
    SkillCatalogService skillCatalogService(SkillCatalogRepository repository) {
        return new SkillCatalogService(repository);
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
