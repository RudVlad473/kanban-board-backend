package com.vrudenko.kanban_board.security;

import com.vrudenko.kanban_board.controller.ResetController;
import com.vrudenko.kanban_board.service.ResetService;
import com.vrudenko.kanban_board.service.ResetTruncateService;
import com.vrudenko.kanban_board.support.containers.AbstractPostgresContainerTest;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Proves neither reset bean, nor its dedicated security chain, is registered when the
 * nonprod profile is inactive.
 *
 * Runs under the plain test profile (no @ActiveProfiles override), so it
 * participates in the pre-commit fastTest gate.
 */
@SpringBootTest
class ResetEndpointProfileGatingTest extends AbstractPostgresContainerTest {
    @Autowired private ApplicationContext applicationContext;

    @Nested
    class BeanRegistration {
        @Test
        void should_registerNoResetBeans_when_nonprodProfileIsInactive() {
            // act
            var resetControllerBeans =
                    applicationContext.getBeanNamesForType(ResetController.class);
            var resetServiceBeans = applicationContext.getBeanNamesForType(ResetService.class);
            var resetTruncateServiceBeans =
                    applicationContext.getBeanNamesForType(ResetTruncateService.class);

            // assert
            Assertions.assertThat(resetControllerBeans).isEmpty();
            Assertions.assertThat(resetServiceBeans).isEmpty();
            Assertions.assertThat(resetTruncateServiceBeans).isEmpty();
        }

        @Test
        void should_registerNoResetSecurityChain_when_nonprodProfileIsInactive() {
            // act
            var resetSecurityConfigBeans =
                    applicationContext.getBeanNamesForType(NonprodResetSecurityConfiguration.class);
            var securityFilterChainBeans =
                    applicationContext.getBeanNamesForType(SecurityFilterChain.class);

            // assert
            Assertions.assertThat(resetSecurityConfigBeans).isEmpty();
            Assertions.assertThat(securityFilterChainBeans).hasSize(1);
        }
    }
}
