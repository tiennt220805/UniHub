package com.unihub.user.infrastructure.in.seeding;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

/**
 * The cluster runs the {@code dev} profile (Phase 7 checklist D86, Q6 (ii)) because that is what
 * switches on the account seeder. A second bean gated by {@code @Profile} would be switched on
 * there as well, unnoticed, so the seeder is pinned as the only class in {@code src/main} that is.
 */
class ProfileGatedBeansTest {

    @Test
    @DisplayName("DevStudentSeedRunner is the only class in src/main gated by @Profile")
    void theSeederIsTheOnlyProfileGatedBean() {
        var classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.unihub");

        List<String> gated =
                classes.stream()
                        .filter(javaClass -> javaClass.isAnnotatedWith(Profile.class))
                        .map(javaClass -> javaClass.getName())
                        .toList();

        assertThat(gated).containsExactly(DevStudentSeedRunner.class.getName());
    }

    @Test
    @DisplayName(
            "no @Profile on a method either: a @Bean method gated by a profile is a bean just the same")
    void noBeanMethodIsProfileGated() {
        var classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.unihub");

        var gatedMethods =
                classes.stream()
                        .flatMap(javaClass -> javaClass.getMethods().stream())
                        .filter(method -> method.isAnnotatedWith(Profile.class))
                        .map(method -> method.getFullName())
                        .toList();

        assertThat(gatedMethods).isEmpty();
    }
}
