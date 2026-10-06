package com.vrudenko.kanban_board.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.reset_dto.ResetUsersRequestDTO;
import com.vrudenko.kanban_board.exception.AppAccessDeniedException;
import com.vrudenko.kanban_board.service.ResetService;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reset nonprod's Postgres and Kafka activity-log state to zero rows via ResetService,
 * behind a nonprod-only, shared-secret-authenticated endpoint.
 *
 * ?fullReset=true selects the unconditional full reset (reset). Absent, or any
 * other value, selects the targeted delete (deleteUsers), which then requires a
 * userIds body. Both routes call verifyResetToken first, so neither route's security
 * check can drift from the other's.
 *
 * Decisions:
 *
 * Two independent controls, not one. @Profile("nonprod") means this bean does not
 * exist at all in a context where the nonprod profile is inactive -- regardless of the
 * token check below. The shared-secret header is a second, independent control: even if a deploy
 * script ever set SPRING_PROFILES_ACTIVE to include nonprod in production by
 * mistake (a real copy-paste failure mode, not a hypothetical), a caller would still need the
 * correct token. Neither control is treated as sufficient alone.
 *
 * Constant-time comparison. The supplied and configured tokens are compared via
 * MessageDigest.isEqual, never String.equals -- a variable-time comparison on a shared
 * secret leaks the matching prefix length across repeated requests (ASVS V6).
 *
 * No oracle on header presence. suppliedToken is bound with required =
 * false: a request carrying no header at all reaches the same mismatch path (and therefore the
 * same 403 response) as a request carrying a wrong value. Binding it as required would instead make
 * Spring answer 400 for an absent header and 403 for a wrong one, handing a probe a free
 * distinguisher.
 */
@Profile("nonprod")
@RestController
@RequestMapping(ApiPaths.RESET)
@Validated
public class ResetController {
    public static final String RESET_TOKEN_HEADER = "X-Reset-Token";

    private static final int MIN_TOKEN_LENGTH = 32;

    @Autowired private ResetService resetService;

    // No default, deliberately: a nonprod context with no APP_RESET_TOKEN fails fast at startup
    // instead of running with a blank secret that could match a blank supplied token.
    // planner-discipline-allow: app.reset.token
    @Value("${app.reset.token}")
    private String configuredToken;

    /**
     * Reject a configured token that is null, blank, or shorter than MIN_TOKEN_LENGTH:
     * without this guard an effectively-empty secret could compare equal to a blank supplied token
     * via MessageDigest.isEqual.
     */
    @PostConstruct
    void validateConfiguredToken() {
        if (configuredToken == null
                || configuredToken.isBlank()
                || configuredToken.length() < MIN_TOKEN_LENGTH) {
            throw new IllegalStateException(
                    "app.reset.token must be configured to a non-blank value at least "
                            + MIN_TOKEN_LENGTH
                            + " characters long.");
        }
    }

    @PostMapping(params = "fullReset=true")
    public ResponseEntity<Void> reset(
            @RequestHeader(name = RESET_TOKEN_HEADER, required = false) String suppliedToken) {
        verifyResetToken(suppliedToken);

        resetService.resetAll();

        return ResponseEntity.noContent().build();
    }

    // params = "fullReset!=true" matches BOTH a request with no fullReset parameter and one with
    // any value other than exactly "true": Spring's negated-equality condition means "not
    // present-and-equal", not "present and different".
    @PostMapping(params = "fullReset!=true")
    public ResponseEntity<Void> deleteUsers(
            @RequestHeader(name = RESET_TOKEN_HEADER, required = false) String suppliedToken,
            @Valid @RequestBody ResetUsersRequestDTO dto) {
        verifyResetToken(suppliedToken);

        resetService.deleteUsers(dto.getUserIds());

        return ResponseEntity.noContent().build();
    }

    private void verifyResetToken(String suppliedToken) {
        if (suppliedToken == null
                || suppliedToken.isBlank()
                || !matchesConfiguredToken(suppliedToken)) {
            throw new AppAccessDeniedException("nonprod reset endpoint");
        }
    }

    private boolean matchesConfiguredToken(String suppliedToken) {
        return MessageDigest.isEqual(
                suppliedToken.getBytes(StandardCharsets.UTF_8),
                configuredToken.getBytes(StandardCharsets.UTF_8));
    }
}
