package com.vrudenko.kanban_board.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.vrudenko.kanban_board.config.EventIdGenerator;
import com.vrudenko.kanban_board.dto.board_dto.BoardFullResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.board_dto.UpdateBoardRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.entity.BoardEntity;
import com.vrudenko.kanban_board.entity.UserEntity;
import com.vrudenko.kanban_board.event.BoardCreatedEvent;
import com.vrudenko.kanban_board.event.BoardDeletedEvent;
import com.vrudenko.kanban_board.event.BoardUpdatedEvent;
import com.vrudenko.kanban_board.exception.AppDuplicateResourceException;
import com.vrudenko.kanban_board.exception.AppEntityNotFoundException;
import com.vrudenko.kanban_board.mapper.BoardFullMapper;
import com.vrudenko.kanban_board.mapper.BoardMapper;
import com.vrudenko.kanban_board.repository.BoardRepository;

import com.google.common.annotations.VisibleForTesting;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
public class BoardService {
    @Autowired private BoardRepository boardRepository;

    @Autowired private BoardMapper boardMapper;

    @Autowired private BoardFullMapper boardFullMapper;

    @Autowired private ColumnService columnService;

    @Autowired private OwnershipVerifierService ownershipVerifierService;

    @Autowired private EntityManager entityManager;

    @Autowired private ApplicationEventPublisher eventPublisher;

    @Autowired private EventIdGenerator eventIdGenerator;

    public List<BoardResponseDTO> findAllByUserId(String userId) {
        return boardMapper.toResponseDTOList(boardRepository.findAllByUserId(userId));
    }

    @Transactional
    public ColumnResponseDTO addColumnByBoardId(
            String userId, String boardId, SaveColumnRequestDTO columnDTO) {
        var board = findById(userId, boardId);

        return columnService.save(columnDTO, board);
    }

    /**
     * The id below is captured into a local BEFORE the cascade and the delete run, on purpose —
     * same reason as {@code TaskService#deleteById}'s Javadoc: once {@code
     * boardRepository.deleteById(...)} executes there is nothing left to derive {@code boardId}
     * from for the {@code BoardDeletedEvent}. Fires exactly once per directly-requested delete —
     * cascaded columns/tasks/subtasks underneath publish nothing of their own (fork D-D, resolved
     * D1); {@link #deleteAllByUserId} therefore emits one {@code BoardDeletedEvent} per board, not
     * one combined account-deletion event.
     */
    @Transactional
    public void deleteById(String userId, String boardId) {
        var board = findById(userId, boardId);
        var deletedBoardId = board.getId();

        columnService.deleteAllByBoardId(userId, deletedBoardId);

        boardRepository.deleteById(deletedBoardId);

        eventPublisher.publishEvent(
                new BoardDeletedEvent(
                        eventIdGenerator.generate(), userId, deletedBoardId, Instant.now()));
    }

    @Transactional
    public void deleteAllByUserId(String userId) {
        var boardsOwnedByUser = findAllByUserId(userId);

        for (var board : boardsOwnedByUser) {
            deleteById(userId, board.getId());
        }
    }

    @Transactional
    public BoardEntity findById(String userId, String boardId) {
        var pair = ownershipVerifierService.verifyOwnershipOfBoard(userId, boardId);

        return pair.getSecond();
    }

    /**
     * GAP-04's nested read ({@code GET /boards/{boardId}/full}) -- the one deliberate exception to
     * this codebase's flat-DTO convention, justified in {@code 06-05-PLAN.md}'s {@code
     * flat_dto_exception_justification} block. Ownership is verified FIRST via {@link #findById},
     * exactly like every other method in this class, and the fetch-join query below runs against
     * the <b>verified entity's own id</b> ({@code verifiedBoard.getId()}), never the raw {@code
     * boardId} path parameter -- a nested response discloses strictly more than any flat one, so
     * the ownership check matters more here, not less. The fetch join and the mapping both happen
     * inside this {@code @Transactional} method, so the returned DTO tree is fully materialised
     * before the transaction ends and no unfetched association is ever touched outside it.
     */
    @Transactional
    public BoardFullResponseDTO findFullById(String userId, String boardId) {
        var verifiedBoard = findById(userId, boardId);

        var fullBoard = boardRepository.findByIdWithColumnsTasksAndSubtasks(verifiedBoard.getId());
        if (fullBoard.isEmpty()) {
            throw new AppEntityNotFoundException("Board");
        }

        return boardFullMapper.toBoardFullResponseDTO(fullBoard.get());
    }

    /**
     * Explicit version check is required in addition to {@code @Version}, for the same reason
     * {@link ColumnService#updateById} needs one: this load-then-save flow runs entirely within one
     * transaction, so Hibernate's own dirty-checking optimistic lock (which fires on the UPDATE
     * statement) does not by itself model a stale-read-then-write race across separate HTTP
     * requests. Comparing the caller-supplied {@code boardDTO.getVersion()} against the just-loaded
     * managed entity's version, before any field is mutated -- and before the duplicate-name guard
     * below, matching {@code ColumnService}'s "compare before any other logic" ordering -- is what
     * actually catches that race (D-13).
     */
    @Transactional
    public BoardResponseDTO updateById(
            String userId, String boardId, UpdateBoardRequestDTO boardDTO) {
        var boardToUpdate = findById(userId, boardId);

        // boardDTO.getVersion() is read ONLY here, for this stale-write precondition check -- it
        // is never assigned onto `boardToUpdate`. The version value that actually gets persisted
        // is generated entirely by Hibernate's own @Version increment mechanism when the UPDATE
        // statement runs (forced below via entityManager.flush()), independent of whatever value
        // the client sent.
        if (!boardToUpdate.getVersion().equals(boardDTO.getVersion())) {
            throw new OptimisticLockingFailureException(
                    "Board was modified by another request, please refetch.");
        }

        // A no-op rename (new name equals the current name) must not collide with the board's
        // own existing row, so the uniqueness check is skipped entirely in that case.
        var isNoOpRename = boardToUpdate.getName().equals(boardDTO.getName());
        if (!isNoOpRename
                && boardRepository.existsByUserIdAndName(
                        boardToUpdate.getUser().getId(), boardDTO.getName())) {
            throw new AppDuplicateResourceException("Board");
        }

        boardToUpdate.setName(boardDTO.getName());

        var savedBoard = boardRepository.save(boardToUpdate);

        // Hibernate only bumps the in-memory @Version field once the UPDATE statement actually
        // runs, which normally happens at transaction commit, not at save(). Flushing here forces
        // that UPDATE (and the version increment) to happen before the response DTO is built, so
        // the caller sees the new version instead of the stale pre-update one -- same reasoning as
        // ColumnService.updateById.
        entityManager.flush();

        // Published only after both guards above have passed, so a rejected update (stale
        // version, duplicate name) publishes nothing. Ids derived from the verified entity, never
        // a raw path variable (docs/CODE_STYLE.md rule 2).
        eventPublisher.publishEvent(
                new BoardUpdatedEvent(
                        eventIdGenerator.generate(),
                        savedBoard.getUser().getId(),
                        savedBoard.getId(),
                        Instant.now()));

        return boardMapper.toResponseDTO(savedBoard);
    }

    /**
     * {@code @Transactional} here (rather than relying on the caller, {@link
     * UserService#addBoardByUserId}, already being {@code @Transactional}) makes the after-commit
     * {@code BoardCreatedEvent} publish guarantee self-contained — see {@link
     * TaskService#save(com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO,
     * com.vrudenko.kanban_board.entity.ColumnEntity)}'s Javadoc for the full reasoning.
     */
    @Transactional
    public BoardResponseDTO save(SaveBoardRequestDTO dto, UserEntity user) {
        var board = boardMapper.fromSaveBoardRequestDTO(dto);
        board.setUser(user);

        // Truncated to microseconds because the `created_at` column is timestamp(6) -- PostgreSQL
        // drops anything finer -- and this same in-memory Instant both seeds the response DTO
        // below and re-emerges verbatim on every later database read; without truncation those two
        // paths could return different values for the same board.
        var createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        board.setCreatedAt(createdAt);

        boardRepository.save(board);

        eventPublisher.publishEvent(
                new BoardCreatedEvent(
                        eventIdGenerator.generate(), user.getId(), board.getId(), createdAt));

        return boardMapper.toResponseDTO(board);
    }

    @VisibleForTesting
    @Transactional
    void deleteAll() {
        for (var boardEntity : boardRepository.findAll()) {
            deleteById(boardEntity.getUser().getId(), boardEntity.getId());
        }
    }

    @VisibleForTesting
    public List<BoardResponseDTO> findAll() {
        return boardMapper.toResponseDTOList(boardRepository.findAll());
    }
}
