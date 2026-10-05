package com.vrudenko.kanban_board.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.vrudenko.kanban_board.config.EventIdGenerator;
import com.vrudenko.kanban_board.dto.subtask_dto.SaveSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.UpdateSubtaskRequestDTO;
import com.vrudenko.kanban_board.entity.SubtaskEntity;
import com.vrudenko.kanban_board.entity.TaskEntity;
import com.vrudenko.kanban_board.event.SubtaskCreatedEvent;
import com.vrudenko.kanban_board.event.SubtaskDeletedEvent;
import com.vrudenko.kanban_board.event.SubtaskUpdatedEvent;
import com.vrudenko.kanban_board.mapper.SubtaskMapper;
import com.vrudenko.kanban_board.repository.SubtaskRepository;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
public class SubtaskService {
    @Autowired private SubtaskRepository subtaskRepository;

    @Autowired private SubtaskMapper subtaskMapper;

    @Autowired private OwnershipVerifierService ownershipVerifierService;

    @Autowired private EntityManager entityManager;

    @Autowired private ApplicationEventPublisher eventPublisher;

    @Autowired private EventIdGenerator eventIdGenerator;

    /**
     * Create the subtask under an ownership-verified {@code task} and publish {@code
     * SubtaskCreatedEvent} after commit.
     *
     * <p>{@code @Transactional} is declared here so the after-commit publish does not depend on the
     * caller; see {@link TaskService#save}. The user and board ids are walked from the verified
     * {@code task}, never a raw path variable: {@link TaskService#addSubtaskByTaskId} verified
     * ownership before handing it over (docs/CODE_STYLE.md rule 2).
     */
    @Transactional
    SubtaskResponseDTO save(TaskEntity task, SaveSubtaskRequestDTO dto) {
        var subtask = subtaskMapper.fromSaveSubtaskRequestDTO(dto);

        subtask.setIsCompleted(false);
        subtask.setTask(task);

        subtaskRepository.save(subtask);

        eventPublisher.publishEvent(
                new SubtaskCreatedEvent(
                        eventIdGenerator.generate(),
                        task.getColumn().getBoard().getUser().getId(),
                        task.getColumn().getBoard().getId(),
                        task.getId(),
                        subtask.getId(),
                        Instant.now()));

        return subtaskMapper.toSubtaskResponseDTO(subtask);
    }

    @Transactional
    public List<SubtaskResponseDTO> findAllByTaskId(String userId, String taskId) {
        var pair = ownershipVerifierService.verifyOwnershipOfTask(userId, taskId);

        return subtaskMapper.toSubtaskResponseDTOList(
                subtaskRepository.findAllByTaskId(pair.getSecond().getId()));
    }

    @Transactional
    SubtaskEntity findById(String userId, String taskId) {
        var pair = ownershipVerifierService.verifyOwnershipOfSubtask(userId, taskId);

        return pair.getSecond();
    }

    /**
     * Update the subtask if the caller's version matches, rejecting a stale write.
     *
     * <p>The explicit version check is required in addition to {@code @Version}: this
     * load-then-save flow runs inside one transaction, so Hibernate's dirty-check lock (which fires
     * on the UPDATE) cannot model "client read version N, another client wrote N+1, reject this
     * write" across separate HTTP requests. Comparing {@code dto.getVersion()} to the just-loaded
     * entity's version before any mutation turns that race into a rejected request instead of a
     * silent overwrite.
     */
    @Transactional
    public SubtaskResponseDTO updateById(
            String userId, String taskId, UpdateSubtaskRequestDTO dto) {
        var subtask = findById(userId, taskId);

        // dto.getVersion() is read only for this precondition and never assigned onto `subtask`:
        // the persisted version comes from Hibernate's own @Version increment.
        if (!subtask.getVersion().equals(dto.getVersion())) {
            throw new OptimisticLockingFailureException(
                    "Subtask was modified by another request, please refetch.");
        }

        if (Optional.ofNullable(dto.getTitle()).isPresent()) {
            subtask.setTitle(dto.getTitle());
        }

        if (Optional.ofNullable(dto.getIsCompleted()).isPresent()) {
            subtask.setIsCompleted(dto.getIsCompleted());
        }

        subtaskRepository.save(subtask);

        // Hibernate bumps the in-memory @Version only when the UPDATE runs, normally at commit.
        // Flush so the response DTO carries the new version, not the stale pre-update one.
        entityManager.flush();

        // Published only after the version guard, so a rejected update publishes nothing.
        // isCompleted is read back from the managed entity, never echoed from the DTO, so a
        // title-only update still reports the real completion state.
        eventPublisher.publishEvent(
                new SubtaskUpdatedEvent(
                        eventIdGenerator.generate(),
                        subtask.getTask().getColumn().getBoard().getUser().getId(),
                        subtask.getTask().getColumn().getBoard().getId(),
                        subtask.getTask().getId(),
                        subtask.getId(),
                        subtask.getIsCompleted(),
                        Instant.now()));

        return subtaskMapper.toSubtaskResponseDTO(subtask);
    }

    /**
     * Delete the subtask and publish {@code SubtaskDeletedEvent}.
     *
     * <p>The ids are captured into locals BEFORE the delete runs (see {@link
     * TaskService#deleteById}): afterwards nothing is left to derive {@code taskId}/{@code boardId}
     * from. The verified subtask's task/column/board chain is reachable via {@code @ManyToOne}
     * associations (EAGER by JPA default).
     */
    @Transactional
    public void deleteById(String userId, String subtaskId) {
        var pair = ownershipVerifierService.verifyOwnershipOfSubtask(userId, subtaskId);
        var subtask = pair.getSecond();

        var deletedSubtaskId = subtask.getId();
        var deletedTaskId = subtask.getTask().getId();
        var deletedBoardId = subtask.getTask().getColumn().getBoard().getId();

        subtaskRepository.deleteById(deletedSubtaskId);

        eventPublisher.publishEvent(
                new SubtaskDeletedEvent(
                        eventIdGenerator.generate(),
                        userId,
                        deletedBoardId,
                        deletedTaskId,
                        deletedSubtaskId,
                        Instant.now()));
    }

    /**
     * Delete every subtask of a task without publishing {@code SubtaskDeletedEvent}.
     *
     * <p>This cascade fires from {@link TaskService#deleteById}, whose own {@code TaskDeletedEvent}
     * is the event a caller sees. See {@link ColumnService#deleteAllByBoardId} for the reasoning
     * shared by every cascade path.
     */
    void deleteAllByTaskId(String userId, String subtaskId) {
        var pair = ownershipVerifierService.verifyOwnershipOfTask(userId, subtaskId);

        subtaskRepository.deleteAllByTaskId(pair.getSecond().getId());
    }

    /**
     * Batch variant for callers that already verified ownership of the parent task(s), such as a
     * column-level bulk delete. Publishes no {@code SubtaskDeletedEvent}, like {@link
     * #deleteAllByTaskId}.
     */
    void deleteAllByTaskIds(List<String> taskIds) {
        if (taskIds.isEmpty()) {
            return;
        }

        subtaskRepository.deleteAllByTaskIdIn(taskIds);
    }
}
