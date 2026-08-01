package com.vrudenko.kanban_board.event;

import com.vrudenko.kanban_board.AbstractAppTest;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.UpdateTaskRequestDTO;
import com.vrudenko.kanban_board.service.ColumnService;
import com.vrudenko.kanban_board.service.TaskService;
import com.vrudenko.kanban_board.support.RecordingActivityEventListener;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * After-commit publication proof for every {@link ActivityEvent} this application publishes.
 * Extended across Plan 02's three tasks: task lifecycle events (this task), board/column creation
 * events, and the negative-path proofs (rollback suppression, non-null field invariant).
 */
@SpringBootTest
public class ActivityEventPublicationTest extends AbstractAppTest {
    @Autowired RecordingActivityEventListener recorder;

    @Autowired ColumnService columnService;

    @Autowired TaskService taskService;

    @Nested
    class AddTaskByColumnIdTest {
        @Test
        void shouldPublishTaskCreatedEvent_whenTaskCreated() {
            // arrange
            recorder.clear();
            var userId = getOwningUser().getId();
            var columnId = mockPopulatedColumn.getId();
            var title = dataFactory.getRandomWord(ValidationConstants.MIN_TASK_TITLE_LENGTH + 2);

            // act
            var task =
                    columnService.addTaskByColumnId(
                            userId, columnId, SaveTaskRequestDTO.builder().title(title).build());

            // assert
            Assertions.assertThat(recorder.getRecorded()).hasSize(1);
            var event = recorder.getRecorded().getFirst();
            Assertions.assertThat(event).isInstanceOf(TaskCreatedEvent.class);
            var taskCreatedEvent = (TaskCreatedEvent) event;
            Assertions.assertThat(taskCreatedEvent.taskId()).isEqualTo(task.getId());
            Assertions.assertThat(taskCreatedEvent.columnId()).isEqualTo(columnId);
            Assertions.assertThat(taskCreatedEvent.boardId()).isEqualTo(mockPopulatedBoard.getId());
            Assertions.assertThat(taskCreatedEvent.userId()).isEqualTo(userId);
        }
    }

    @Nested
    class DeleteByIdTest {
        @Test
        void shouldPublishTaskDeletedEvent_whenTaskDeleted() {
            // arrange
            var userId = getOwningUser().getId();
            var columnId = mockPopulatedColumn.getId();
            var title = dataFactory.getRandomWord(ValidationConstants.MIN_TASK_TITLE_LENGTH + 2);
            var task =
                    columnService.addTaskByColumnId(
                            userId, columnId, SaveTaskRequestDTO.builder().title(title).build());
            recorder.clear();

            // act
            taskService.deleteById(userId, task.getId());

            // assert
            Assertions.assertThat(recorder.getRecorded()).hasSize(1);
            var event = recorder.getRecorded().getFirst();
            Assertions.assertThat(event).isInstanceOf(TaskDeletedEvent.class);
            var taskDeletedEvent = (TaskDeletedEvent) event;
            Assertions.assertThat(taskDeletedEvent.taskId()).isEqualTo(task.getId());
            Assertions.assertThat(taskDeletedEvent.columnId()).isEqualTo(columnId);
            Assertions.assertThat(taskDeletedEvent.boardId())
                    .isNotNull()
                    .isEqualTo(mockPopulatedBoard.getId());
            Assertions.assertThat(taskDeletedEvent.userId()).isEqualTo(userId);
        }
    }

    @Nested
    class UpdateByIdTest {
        @Test
        void shouldPublishNothing_whenTaskUpdated() {
            // arrange
            recorder.clear();
            var userId = getOwningUser().getId();
            var taskId = mockPopulatedTask.getId();
            var newTitle = dataFactory.getRandomWord(ValidationConstants.MIN_TASK_TITLE_LENGTH + 2);

            // act
            taskService.updateById(
                    userId,
                    taskId,
                    UpdateTaskRequestDTO.builder()
                            .title(newTitle)
                            .version(mockPopulatedTask.getVersion())
                            .build());

            // assert
            Assertions.assertThat(recorder.getRecorded()).isEmpty();
        }
    }
}
