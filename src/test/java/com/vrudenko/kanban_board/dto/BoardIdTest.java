package com.vrudenko.kanban_board.dto;

import com.vrudenko.kanban_board.config.RandFlakeGenerator;
import com.vrudenko.kanban_board.constant.ValidationConstants;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Pins that ValidationConstants.MAX_BOARD_ID_LENGTH covers every id
 * RandFlakeGenerator can emit and that each matches ValidationConstants.BOARD_ID_PATTERN.
 *
 * Narrowing either side would make the app reject ids it issues itself, with no compiler or
 * runtime signal.
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
