package com.vrudenko.kanban_board.dto.task_dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
@EqualsAndHashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MoveTaskRequestDTO {
    @NotBlank private String targetColumnId;

    @NotNull private Long version;

    // Deliberately nullable, no @NotNull (D-04): null means "append at the end of the target
    // column", preserving the pre-existing move behaviour for clients that never send this field.
    // An over-large value is clamped server-side to the end rather than rejected; a negative
    // value is rejected by this @Min(0) before service code runs.
    @Min(0) private Integer targetPosition;
}
