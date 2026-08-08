package com.vrudenko.kanban_board.dto.user_dto;

import com.vrudenko.kanban_board.entity.ThemePreference;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * Whole-value replacement of a user's theme preference (D-10..D-12). Deliberately does not follow
 * {@code docs/CODE_STYLE.md} rule 6's {@code Update*RequestDTO} shape in two respects, both
 * intentional, not oversights:
 *
 * <ul>
 *   <li>No {@code @JsonInclude(JsonInclude.Include.NON_NULL)} -- that annotation is what marks a
 *       DTO as a <em>partial</em> update. {@code theme} is this DTO's only field and a PUT to the
 *       theme route always replaces the whole (single-scalar) resource, so there is no
 *       partial-update semantic to express.
 *   <li>No {@code @NotNull Long version} -- rule 6's mandatory version field exists to guard
 *       {@code @Version}-annotated entities against a stale concurrent write. {@link
 *       com.vrudenko.kanban_board.entity.UserEntity} carries no {@code @Version} field, and this
 *       plan deliberately does not add one: a theme write is last-write-wins by design, since
 *       rejecting a user's own preference toggle with a 409 because they changed it on another
 *       session first would be a worse outcome than simply applying it (see plan 06-06's design
 *       rationale, T-06-29).
 * </ul>
 */
@Getter
@Setter
@Builder
@EqualsAndHashCode
public class UpdateThemeRequestDTO {
    @NotNull private ThemePreference theme;
}
