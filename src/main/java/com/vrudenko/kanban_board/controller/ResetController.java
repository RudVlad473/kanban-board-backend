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
 * Plan 08-02 (RESET-01, D-01, D-02): a nonprod-only, shared-secret-authenticated endpoint that
 * fully resets nonprod's Postgres and Kafka activity-log state to zero rows, via {@link
 * ResetService}.
 *
 * <p><b>Two independent controls, not one.</b> {@code @Profile("nonprod")} means this bean does not
 * exist at all in a context where the {@code nonprod} profile is inactive -- regardless of the
 * token check below. The shared-secret header is a second, independent control: even if a deploy
 * script ever set {@code SPRING_PROFILES_ACTIVE} to include {@code nonprod} in production by
 * mistake (a real copy-paste failure mode, not a hypothetical), a caller would still need the
 * correct token. Neither control is treated as sufficient alone.
 *
 * <p><b>Constant-time comparison.</b> The supplied and configured tokens are compared via {@link
 * MessageDigest#isEqual}, never {@code String.equals} -- a variable-time comparison on a shared
 * secret leaks the matching prefix length across repeated requests (ASVS V6).
 *
 * <p><b>No oracle on header presence.</b> {@code suppliedToken} is bound with {@code required =
 * false}: a request carrying no header at all reaches the same mismatch path (and therefore the
 * same 403 response) as a request carrying a wrong value. Binding it as required would instead make
 * Spring answer 400 for an absent header and 403 for a wrong one, handing a probe a free
 * distinguisher.
 *
 * <p><b>{@code fullReset} query-param contract (quick task 260829-ii3).</b> {@code ?fullReset=true}
 * selects the unconditional full reset ({@link #reset}, unchanged). Absent, or any other value,
 * selects the targeted delete ({@link #deleteUsers}), which then requires a {@code userIds} body.
 * Both routes call the same private {@link #verifyResetToken} helper as the very first thing they
 * do, so neither route's security check can drift from the other's.
 */
@Profile("nonprod")
@RestController
@RequestMapping(ApiPaths.RESET)
@Validated
public class ResetController {
    public static final String RESET_TOKEN_HEADER = "X-Reset-Token";

    private static final int MIN_TOKEN_LENGTH = 32;

    @Autowired private ResetService resetService;

    // No default value, deliberately: a nonprod context started with no APP_RESET_TOKEN env var
    // fails fast at startup with a resolution error, rather than silently running with a blank
    // secret that could match a blank supplied token.
    // planner-discipline-allow: app.reset.token
    @Value("${app.reset.token}")
    private String configuredToken;

    /**
     * Rejects a configured token that is null, blank, or shorter than {@link #MIN_TOKEN_LENGTH}
     * characters -- without this guard a misconfigured, effectively-empty secret could compare
     * equal to a blank supplied token via {@link MessageDigest#isEqual}.
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

    // params = "fullReset!=true" matches BOTH a request with no fullReset parameter at all and one
    // present with any value other than exactly "true" -- Spring's negated-equality params
    // condition is not "present and different", it's "not present-and-equal".
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
