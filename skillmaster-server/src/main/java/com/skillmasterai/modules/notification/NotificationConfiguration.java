package com.skillmasterai.modules.notification;

import com.skillmasterai.modules.notification.internal.NotificationRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Wires M14 — the notification floor. */
@Configuration(proxyBeanMethods = false)
public class NotificationConfiguration {

    @Bean
    NotificationRepository notificationRepository(JdbcClient jdbc) {
        return new NotificationRepository(jdbc);
    }

    @Bean
    NotificationService notificationService(NotificationRepository repository) {
        return new NotificationService(repository);
    }
}
