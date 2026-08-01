package com.vrudenko.kanban_board.e2e.task;

import static io.restassured.RestAssured.given;

import com.vrudenko.kanban_board.AbstractAppE2ETest;
import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.task_dto.MoveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.event.TaskMovedEvent;
import com.vrudenko.kanban_board.support.RecordingActivityEventListener;
import io.restassured.http.ContentType;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class TaskMoveE2ETest extends AbstractAppE2ETest {

    @Autowired private RecordingActivityEventListener recorder;

    private String getMoveUrl(String taskId) {
        return ApiPaths.TASKS + "/" + taskId + ApiPaths.MOVE;
    }

    private String getColumnTasksUrl(String boardId, String columnId) {
        return ApiPaths.BOARDS + "/" + boardId + ApiPaths.COLUMNS + "/" + columnId + ApiPaths.TASKS;
    }

    @Test
    void move_toColumnOnSameBoard_succeedsAndAnnouncesTaskMovedEvent() {
        // arrange
        recorder.clear();
        Pair<String, String> cookie = signin();
        var boardId = mockPopulatedBoard.getId();
        var sourceColumnId = mockPopulatedColumn.getId();
        var targetColumnId = mockColumns.getFirst().getId();
        var taskId = mockPopulatedTask.getId();
        var startingVersion = mockPopulatedTask.getVersion();

        var moveDto =
                MoveTaskRequestDTO.builder()
                        .targetColumnId(targetColumnId)
                        .version(startingVersion)
                        .build();

        // act
        var response =
                given().cookie(cookie.getFirst(), cookie.getSecond())
                        .contentType(ContentType.JSON)
                        .body(moveDto)
                        .when()
                        .patch(getMoveUrl(taskId))
                        .then()
                        .extract();

        // assert
        Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        var responseBody = response.as(TaskResponseDTO.class);
        Assertions.assertThat(responseBody.getVersion()).isGreaterThan(startingVersion);

        // act
        var targetColumnTasks =
                given().cookie(cookie.getFirst(), cookie.getSecond())
                        .when()
                        .get(getColumnTasksUrl(boardId, targetColumnId))
                        .then()
                        .extract()
                        .as(TaskResponseDTO[].class);
        var sourceColumnTasks =
                given().cookie(cookie.getFirst(), cookie.getSecond())
                        .when()
                        .get(getColumnTasksUrl(boardId, sourceColumnId))
                        .then()
                        .extract()
                        .as(TaskResponseDTO[].class);

        // assert
        Assertions.assertThat(targetColumnTasks)
                .extracting(TaskResponseDTO::getId)
                .contains(taskId);
        Assertions.assertThat(sourceColumnTasks)
                .extracting(TaskResponseDTO::getId)
                .doesNotContain(taskId);

        // assert — exactly one TaskMovedEvent, server-derived fields, non-null id/timestamp
        List<TaskMovedEvent> movedEvents =
                recorder.getRecorded().stream()
                        .filter(TaskMovedEvent.class::isInstance)
                        .map(TaskMovedEvent.class::cast)
                        .toList();

        Assertions.assertThat(movedEvents).hasSize(1);
        var event = movedEvents.getFirst();
        Assertions.assertThat(event.taskId()).isEqualTo(taskId);
        Assertions.assertThat(event.sourceColumnId()).isEqualTo(sourceColumnId);
        Assertions.assertThat(event.targetColumnId()).isEqualTo(targetColumnId);
        Assertions.assertThat(event.boardId()).isEqualTo(boardId);
        Assertions.assertThat(event.userId()).isEqualTo(getOwningUser().getId());
        Assertions.assertThat(event.eventId()).isNotNull();
        Assertions.assertThat(event.timestamp()).isNotNull();
    }
}
