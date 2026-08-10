package com.vrudenko.kanban_board.e2e.column;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.UpdateColumnRequestDTO;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppMockMvcTest;
import jakarta.servlet.http.Cookie;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
public class ColumnLockingTest extends AbstractAppMockMvcTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    private String getColumnUrl(String boardId, String columnId) {
        return ApiPaths.BOARDS + "/" + boardId + ApiPaths.COLUMNS + "/" + columnId;
    }

    @Test
    void concurrentConflictingUpdates_firstSucceeds_secondReturnsConflict() throws Exception {
        // Arrange
        Cookie cookie = signinCookie();
        var boardId = mockPopulatedBoard.getId();
        var columnId = mockPopulatedColumn.getId();
        var url = getColumnUrl(boardId, columnId);
        var startingVersion = mockPopulatedColumn.getVersion();

        var firstUpdate =
                UpdateColumnRequestDTO.builder()
                        .name("First writer wins")
                        .version(startingVersion)
                        .build();

        var secondUpdate =
                UpdateColumnRequestDTO.builder()
                        .name("Second writer loses")
                        .version(startingVersion)
                        .build();

        // Act: first PUT with the starting version succeeds and bumps the version
        var firstResponse =
                mockMvc.perform(
                                put(url).cookie(cookie)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(firstUpdate)))
                        .andReturn();

        // Assert
        Assertions.assertThat(firstResponse.getResponse().getStatus())
                .isEqualTo(HttpStatus.OK.value());
        var firstResponseBody =
                objectMapper.readValue(
                        firstResponse.getResponse().getContentAsString(), ColumnResponseDTO.class);
        Assertions.assertThat(firstResponseBody.getVersion()).isNotEqualTo(startingVersion);

        // Act: second PUT still holding the stale starting version is rejected
        var secondResponse =
                mockMvc.perform(
                                put(url).cookie(cookie)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(secondUpdate)))
                        .andReturn();

        // Assert
        Assertions.assertThat(secondResponse.getResponse().getStatus())
                .isEqualTo(HttpStatus.CONFLICT.value());

        // Act: re-submitting the same stale PUT again (without refetching) must still be
        // rejected, never silently succeed
        var retryResponse =
                mockMvc.perform(
                                put(url).cookie(cookie)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(secondUpdate)))
                        .andReturn();

        // Assert
        Assertions.assertThat(retryResponse.getResponse().getStatus())
                .isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void update_withCurrentVersion_succeedsAndReturnsIncrementedVersion() throws Exception {
        // Arrange
        Cookie cookie = signinCookie();
        var boardId = mockPopulatedBoard.getId();
        var columnId = mockPopulatedColumn.getId();
        var url = getColumnUrl(boardId, columnId);
        var startingVersion = mockPopulatedColumn.getVersion();

        var updateDto =
                UpdateColumnRequestDTO.builder()
                        .name("Updated with current version")
                        .version(startingVersion)
                        .build();

        // Act
        var response =
                mockMvc.perform(
                                put(url).cookie(cookie)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(updateDto)))
                        .andReturn();

        // Assert
        Assertions.assertThat(response.getResponse().getStatus()).isEqualTo(HttpStatus.OK.value());
        var responseBody =
                objectMapper.readValue(
                        response.getResponse().getContentAsString(), ColumnResponseDTO.class);
        Assertions.assertThat(responseBody.getVersion()).isGreaterThan(startingVersion);
    }

    @Test
    void update_withoutVersion_returnsBadRequest() throws Exception {
        // Arrange
        Cookie cookie = signinCookie();
        var boardId = mockPopulatedBoard.getId();
        var columnId = mockPopulatedColumn.getId();
        var url = getColumnUrl(boardId, columnId);

        var updateDtoWithoutVersion =
                UpdateColumnRequestDTO.builder().name("No version here").build();

        // Act
        var response =
                mockMvc.perform(
                                put(url).cookie(cookie)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        updateDtoWithoutVersion)))
                        .andReturn();

        // Assert
        Assertions.assertThat(response.getResponse().getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());
    }
}
