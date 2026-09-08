package com.vrudenko.kanban_board.dto;

import com.vrudenko.kanban_board.config.RandFlakeGenerator;
import com.vrudenko.kanban_board.constant.ValidationConstants;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Pins two properties {@link com.vrudenko.kanban_board.dto.annotation.BoardId}'s {@code @Pattern}
 * depends on but cannot itself express: (1) {@link ValidationConstants#MAX_BOARD_ID_LENGTH} is at
 * least as long as any id {@link RandFlakeGenerator} can ever emit, and (2) every id the generator
 * can ever emit matches {@link ValidationConstants#BOARD_ID_PATTERN}. Without this class the regex
 * and the generator could drift apart silently -- e.g. narrowing the length constant would be a
 * change with no compiler or runtime signal that the app now rejects ids it issues itself.
 */
public class BoardIdTest {
    @Test
    void maxBoardIdLength_shouldBeAtLeastGeneratorsRealCeiling() {
        // arrange
        var ceiling = Long.toString(Long.MAX_VALUE, 36);

        // act & assert
        Assertions.assertThat(ceiling.length())
                .isLessThanOrEqualTo(ValidationConstants.MAX_BOARD_ID_LENGTH);
    }

    @Test
    void boardIdPattern_shouldMatchEveryValueTheGeneratorCanEmit() {
        // arrange
        var generator = new RandFlakeGenerator();

        // act & assert
        for (int i = 0; i < 1000; i++) {
            var id = generator.generateRandflake();
            Assertions.assertThat(id).matches(ValidationConstants.BOARD_ID_PATTERN);
        }
    }
}
