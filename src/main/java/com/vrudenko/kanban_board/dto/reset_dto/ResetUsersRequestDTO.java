package com.vrudenko.kanban_board.dto.reset_dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * Request body for the nonprod targeted-user-delete reset mode (quick task 260829-ii3). An empty
 * {@code userIds} list is a validation error (400), never a no-op and never a full-reset sentinel
 * -- the unconditional full reset has its own, separate {@code fullReset=true} route.
 */
@Getter
@Setter
@Builder
@EqualsAndHashCode
public class ResetUsersRequestDTO {
    @NotEmpty(message = "userIds must not be empty") private List<String> userIds;
}
