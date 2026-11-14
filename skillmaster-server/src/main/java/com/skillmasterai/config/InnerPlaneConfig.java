package com.skillmasterai.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The inner plane's two guards: it only answers on a private port, and only with its token.
 *
 * <p><strong>Two connectors, not one.</strong> The public connector binds loopback and is reached
 * only through nginx; the inner connector binds the instance's private address. A request's local
 * port therefore says which plane it arrived on: {@code /inner/**} on the public connector is a
 * 404, and anything else on the inner connector is a 404 — neither address advertises the other
 * plane.
 *
 * <p>Blank token or blank address means the plane is off: no connector is added and every
 * {@code /inner} request is a 404, the same "an unconfigured endpoint should not say it exists"
 * rule the old trigger followed.
 */
@Configuration(proxyBeanMethods = false)
public class InnerPlaneConfig {

    static final String HEADER = "X-Inner-Token";

    /**
     * The write path the inner endpoint uses. It used to be created by {@code SyncConfig}, which
     * is gone; without this bean the controller cannot be constructed and the server does not
     * start.
     */
    @Bean
    com.skillmasterai.usecase.sync.SyncDraftWriter syncDraftWriter(
            com.skillmasterai.usecase.SubmitSkillUseCase submit,
            com.skillmasterai.usecase.DraftRecorder drafts,
            com.skillmasterai.modules.skill.SkillMetadataService metadata,
            com.skillmasterai.modules.version.SkillVersionService versions,
            com.skillmasterai.modules.skill.SourceService sources) {
        return new com.skillmasterai.usecase.sync.SyncDraftWriter(submit, drafts, metadata, versions,
                sources);
    }

    @Bean
    TomcatServletWebServerFactory tomcatFactory(SkillmasterProperties properties) {
        SkillmasterProperties.Inner inner = properties.inner();
        if (inner.address() == null || inner.address().isBlank()) {
            return new TomcatServletWebServerFactory();
        }
        return new TomcatServletWebServerFactory() {
            @Override
            public org.springframework.boot.web.server.WebServer getWebServer(
                    org.springframework.boot.web.servlet.ServletContextInitializer... initializers) {
                org.springframework.boot.web.server.WebServer server =
                        super.getWebServer(initializers);
                Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
                connector.setPort(inner.port());
                connector.setProperty("address", inner.address());
                ((org.springframework.boot.tomcat.TomcatWebServer) server).getTomcat()
                        .getService().addConnector(connector);
                return server;
            }
        };
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> innerPlaneFilter(SkillmasterProperties properties) {
        FilterRegistrationBean<OncePerRequestFilter> registration =
                new FilterRegistrationBean<>(new InnerPlaneFilter(properties.inner()));
        registration.addUrlPatterns("/*");
        // Before Spring Security's chain (-100), or the private port would answer a protected
        // path with 401 and its Bearer challenge before this filter could say 404.
        registration.setOrder(-200);
        return registration;
    }

    /** Port and token in one place, because either one alone is not a guard. */
    static final class InnerPlaneFilter extends OncePerRequestFilter {

        private final SkillmasterProperties.Inner inner;

        InnerPlaneFilter(SkillmasterProperties.Inner inner) {
            this.inner = inner;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            boolean innerAddress = inner.address() != null && !inner.address().isBlank();
            boolean innerPort = innerAddress && request.getLocalPort() == inner.port();
            boolean innerPath = request.getRequestURI().startsWith("/inner/");

            if (innerPath) {
                if (!innerAddress || inner.token() == null || inner.token().isBlank()
                        || !innerPort) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
                String presented = request.getHeader(HEADER);
                if (presented == null || !MessageDigest.isEqual(
                        inner.token().getBytes(StandardCharsets.UTF_8),
                        presented.getBytes(StandardCharsets.UTF_8))) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"error\":{\"code\":\"unauthorized\"}}");
                    return;
                }
            } else if (innerPort) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
