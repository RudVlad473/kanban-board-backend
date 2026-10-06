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
     * Delete the board and cascade to its columns, tasks and subtasks, publishing one
     * BoardDeletedEvent.
     *
     * The id is captured into a local BEFORE the cascade and the delete run (see
     * TaskService.deleteById): afterwards nothing is left to derive boardId from. The
     * event fires once per directly-requested delete; cascaded children publish nothing of their
     * own, so deleteAllByUserId emits one event per board, not one combined
     * account-deletion event.
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
     * Return the board with its columns, tasks and subtasks nested, for GET
     * /boards/{boardId}/full: the one deliberate exception to this codebase's flat-DTO convention.
     *
     * Ownership is verified FIRST via findById, and the fetch-join query runs against
     * the verified entity's own id, never the raw boardId path parameter: a nested
     * response discloses strictly more than a flat one, so the ownership check matters more here,
     * not less. The fetch join and the mapping both happen inside this @Transactional
     * method, so the DTO tree is fully materialised before the transaction ends and no unfetched
     * association is touched outside it.
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
     * Rename the board if the caller's version matches, rejecting a stale write or a duplicate
     * name.
     *
     * The explicit version check is required in addition to @Version: this
     * load-then-save flow runs inside one transaction, so Hibernate's UPDATE-time dirty-check lock
     * cannot catch a stale read-then-write across separate HTTP requests. It runs before any field
     * is mutated and before the duplicate-name guard.
     */
    @Transactional
    public BoardResponseDTO updateById(
            String userId, String boardId, UpdateBoardRequestDTO boardDTO) {
        var boardToUpdate = findById(userId, boardId);

        // boardDTO.getVersion() is read only for this precondition and never assigned onto
        // `boardToUpdate`: the persisted version comes from Hibernate's own @Version increment.
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

        // Hibernate bumps the in-memory @Version only when the UPDATE runs, normally at commit.
        // Flush so the response DTO carries the new version, not the stale pre-update one.
        entityManager.flush();

        // Published only after both guards have passed, so a rejected update (stale version,
        // duplicate name) publishes nothing. Ids come from the verified entity, never a raw path
        // variable (docs/CODE_STYLE.md rule 2).
        eventPublisher.publishEvent(
                new BoardUpdatedEvent(
                        eventIdGenerator.generate(),
                        savedBoard.getUser().getId(),
                        savedBoard.getId(),
                        Instant.now()));

        return boardMapper.toResponseDTO(savedBoard);
    }

    /**
     * Create the board for user and publish BoardCreatedEvent after commit.
     *
     * The @Transactional annotation is declared here so the after-commit publish does not depend on the
     * caller; see TaskService.save.
     */
    @Transactional
    public BoardResponseDTO save(SaveBoardRequestDTO dto, UserEntity user) {
        var board = boardMapper.fromSaveBoardRequestDTO(dto);

        // Reject a caller-supplied id that already exists, before any field is written, so a
        // rejected create leaves the entity untouched. The generated-id path pays no extra lookup.
        //
        // Decisions:
        // The check-then-act window is deliberate: two concurrent creates naming the same id can
        // both pass this lookup, and the loser hits the primary key directly. That surfaces through
        // GlobalExceptionHandler's handleDataIntegrityViolation arm as a 409
        // DATA_INTEGRITY_VIOLATION instead of this method's checked DUPLICATE_RESOURCE, the same
        // relationship the board-name guard in UserService#addBoardByUserId has with
        // uk_boards_user_id_name. The database's primary key is the real guarantee; this check only
        // gives the friendlier envelope in the common non-racing case.
        // Caller-supplied ids must not be extended to columns, tasks or subtasks.
        // BoardEntity.column, ColumnEntity.task and TaskEntity.subtasks order by id ascending
        // (@OrderBy("id")) as a creation-order proxy, and base36 string ordering does not preserve
        // the numeric ordering that proxy depends on (a shorter string sorts before a longer one
        // regardless of magnitude). Boards are safe only because no board collection carries that
        // ordering. Extending this without first replacing @OrderBy("id") with an explicit
        // ordering column would silently corrupt their iteration order.
        if (dto.getId() != null && boardRepository.existsById(dto.getId())) {
            throw AppDuplicateResourceException.withMessage(
                    "Board with id '" + dto.getId() + "' already exists");
        }

        board.setUser(user);

        // Truncated to microseconds because `created_at` is timestamp(6) and PostgreSQL drops
        // anything finer; this same Instant seeds the response DTO and re-emerges on every later
        // read, so without truncation the two paths could return different values.
        var createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        board.setCreatedAt(createdAt);

        // A null dto.getId() leaves the entity's id null, so RandFlakeGenerator supplies it as it
        // did before the field existed. A pre-set id is kept only because both
        // RandFlakeGenerator#allowAssignedIdentifiers and its 4-arg generate(...) honour it.
        if (dto.getId() != null) {
            board.setId(dto.getId());
        }

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
