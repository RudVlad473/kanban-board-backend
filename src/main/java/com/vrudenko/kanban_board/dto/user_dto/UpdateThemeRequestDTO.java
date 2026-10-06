package com.vrudenko.kanban_board.dto.user_dto;

import com.vrudenko.kanban_board.entity.ThemePreference;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * Replace a user's theme preference as a whole value. Deliberately departs from the
 * Update*RequestDTO shape of docs/CODE_STYLE.md rule 6 in two respects, both intentional.
 *
 * Decisions:
 *
 * - No @JsonInclude(JsonInclude.Include.NON_NULL): that annotation marks a
 *   partial update, and a PUT to the theme route always replaces the whole
 *   single-scalar resource, so there is no partial-update semantic to express.
 * - No @NotNull Long version: rule 6's version field guards @Version-annotated
 *   entities against a stale concurrent write, and
 *   com.vrudenko.kanban_board.entity.UserEntity carries no @Version field. A theme
 *   write is last-write-wins by design: rejecting a user's own preference toggle with a 409
 *   because they changed it on another session first would be a worse outcome than applying it.
 */
@Getter
@Setter
@Builder
@EqualsAndHashCode
public class UpdateThemeRequestDTO {
    @NotNull private ThemePreference theme;
}
