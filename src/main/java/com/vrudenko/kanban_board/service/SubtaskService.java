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
     * {@code @Transactional} here (rather than relying on the caller, {@link
     * TaskService#addSubtaskByTaskId}, already being {@code @Transactional}) makes the after-commit
     * {@code SubtaskCreatedEvent} publish guarantee self-contained — see {@link
     * TaskService#save(com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO,
     * com.vrudenko.kanban_board.entity.ColumnEntity)}'s Javadoc for the full reasoning. {@code
     * userId}/{@code boardId} are derived from the ownership-verified {@code task} parameter
     * (walked via its column/board chain), never from a raw path variable — {@link
     * TaskService#addSubtaskByTaskId} already verified ownership of {@code task} before handing it
     * to this method (docs/CODE_STYLE.md rule 2).
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
     * Explicit version check is required in addition to {@code @Version}: this load-then-save flow
     * runs entirely within one transaction, so Hibernate's own dirty-checking optimistic lock
     * (which fires on the UPDATE statement) does not by itself model the "client read at version N,
     * another client already wrote version N+1, this client's write should be rejected" scenario
     * across separate HTTP requests. Comparing the caller-supplied {@code dto.getVersion()} against
     * the just-loaded managed entity's version, before any field is mutated, is what actually
     * catches a stale read-then-write race and turns it into a rejected request instead of a silent
     * overwrite.
     */
    @Transactional
    public SubtaskResponseDTO updateById(
            String userId, String taskId, UpdateSubtaskRequestDTO dto) {
        var subtask = findById(userId, taskId);

        // dto.getVersion() is read ONLY here, for this stale-write precondition check — it is
        // never assigned onto `subtask`. The version value that actually gets persisted is
        // generated entirely by Hibernate's own @Version increment mechanism when the UPDATE
        // statement runs (forced below via entityManager.flush()), independent of whatever value
        // the client sent.
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

        // Hibernate only bumps the in-memory @Version field once the UPDATE statement actually
        // runs, which normally happens at transaction commit, not at save(). Flushing here forces
        // that UPDATE (and the version increment) to happen before the response DTO is built, so
        // the caller sees the new version instead of the stale pre-update one.
        entityManager.flush();

        // Fork D-B, resolved B2: isCompleted is read back from the managed entity's post-mutation
        // state, never echoed from dto.getIsCompleted() -- so a title-only update still reports
        // the subtask's real (unchanged) completion state, and the value published is what was
        // actually persisted. Published only after the version guard above has passed, so a
        // rejected update publishes nothing.
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
     * The ids below are captured into locals BEFORE the delete runs, on purpose — same reason as
     * {@code TaskService#deleteById}'s Javadoc: once {@code subtaskRepository.deleteById(...)}
     * executes there is nothing left to derive {@code taskId}/{@code boardId} from for the {@code
     * SubtaskDeletedEvent}. The ownership verifier already returns the verified subtask, whose
     * task/column/board chain is reachable via {@code @ManyToOne} associations (EAGER by JPA
     * default).
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
     * No {@code SubtaskDeletedEvent} is published here (fork D-D, resolved D1): this cascade fires
     * from {@link TaskService#deleteById}, whose own {@code TaskDeletedEvent} is the event a caller
     * sees. Deliberate — see {@link ColumnService#deleteAllByBoardId}'s Javadoc for the full
     * reasoning shared by every cascade path in this codebase.
     */
    void deleteAllByTaskId(String userId, String subtaskId) {
        var pair = ownershipVerifierService.verifyOwnershipOfTask(userId, subtaskId);

        subtaskRepository.deleteAllByTaskId(pair.getSecond().getId());
    }

    /**
     * Batch variant for callers that already verified ownership of the parent task(s) (e.g. a
     * column-level bulk delete) — avoids re-verifying per task id.
     *
     * <p>No {@code SubtaskDeletedEvent} is published here either (fork D-D, resolved D1) — same
     * reasoning as {@link #deleteAllByTaskId}.
     */
    void deleteAllByTaskIds(List<String> taskIds) {
        if (taskIds.isEmpty()) {
            return;
        }

        subtaskRepository.deleteAllByTaskIdIn(taskIds);
    }
}
