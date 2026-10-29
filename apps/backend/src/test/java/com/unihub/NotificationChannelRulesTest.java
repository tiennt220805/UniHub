package com.unihub;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.unihub.notification.application.archfixture.ConcreteChannelUser;
import com.unihub.notification.infrastructure.out.channel.FixtureChannel;
import com.unihub.workshop.archfixture.ChannelUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The mutation of the M-39 structural rules (Phase 4 D40, AC40.1): each rule of {@link
 * ArchitectureTest} turns red on a class that breaks it, and stays green on one that does not.
 * Without this, the rules would only prove that the code is clean today, not that they can see a
 * violation. The offending classes are fixtures in the test source set, which the production
 * ArchUnit suites do not import.
 */
class NotificationChannelRulesTest {

    @Test
    @DisplayName("a class outside the notification module that names NotificationChannel is caught")
    void anOutsiderNamingTheChannelPort_isCaught() {
        var classes = new ClassFileImporter().importClasses(ChannelUser.class);

        assertThatThrownBy(
                        () -> ArchitectureTest.only_notification_depends_on_channels.check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(ChannelUser.class.getName())
                .hasMessageContaining("NotificationChannel");
    }

    @Test
    @DisplayName("the same rule accepts a concrete channel inside the notification module")
    void aChannelInsideTheModule_isAccepted() {
        var classes = new ClassFileImporter().importClasses(FixtureChannel.class);

        assertThatCode(() -> ArchitectureTest.only_notification_depends_on_channels.check(classes))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an application-layer class depending on a concrete channel class is caught")
    void dispatchingNamingAConcreteChannel_isCaught() {
        var classes =
                new ClassFileImporter()
                        .importClasses(ConcreteChannelUser.class, FixtureChannel.class);

        assertThatThrownBy(
                        () ->
                                ArchitectureTest.dispatching_depends_on_no_concrete_channel.check(
                                        classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(ConcreteChannelUser.class.getName())
                .hasMessageContaining(FixtureChannel.class.getName());
    }
}
