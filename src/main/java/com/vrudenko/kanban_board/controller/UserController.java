package com.vrudenko.kanban_board.controller;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.user_dto.UpdateThemeRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.UserResponseDTO;
import com.vrudenko.kanban_board.security.CurrentUserId;
import com.vrudenko.kanban_board.service.UserService;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serve the caller's own user-scoped preferences, kept off {@code AuthenticationController}, the
 * one controller deliberately requiring no authentication.
 *
 * <p>Neither route takes a user id from the path or the body; identity always comes from the
 * session. This is the whole IDOR mitigation for this controller and it is structural: {@link
 * UserService} is the identity root with no ownership chain above it, so there is nothing to chain
 * a check from and no place in the route for another user's id.
 */
@RestController
@RequestMapping(ApiPaths.USERS)
@Validated
@PreAuthorize("isAuthenticated()")
public class UserController {
    @Autowired private UserService userService;

    @GetMapping(ApiPaths.ME + ApiPaths.THEME)
    public ResponseEntity<UserResponseDTO> getTheme(@CurrentUserId String userId) {
        return ResponseEntity.ok(userService.findThemeByUserId(userId));
    }

    @PutMapping(ApiPaths.ME + ApiPaths.THEME)
    public ResponseEntity<UserResponseDTO> updateTheme(
            @CurrentUserId String userId, @Valid @RequestBody UpdateThemeRequestDTO dto) {
        return ResponseEntity.ok(userService.updateTheme(userId, dto));
    }
}
