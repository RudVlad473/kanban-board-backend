package com.vrudenko.kanban_board.service;

import com.google.common.annotations.VisibleForTesting;
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
import com.vrudenko.kanban_board.exception.AppDuplicateResourceException;
import com.vrudenko.kanban_board.exception.AppEntityNotFoundException;
import com.vrudenko.kanban_board.mapper.BoardFullMapper;
import com.vrudenko.kanban_board.mapper.BoardMapper;
import com.vrudenko.kanban_board.repository.BoardRepository;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class BoardService {
    @Autowired private BoardRepository boardRepository;

    @Autowired private BoardMapper boardMapper;

    @Autowired private BoardFullMapper boardFullMapper;

    @Autowired private ColumnService columnService;

    @Autowired private OwnershipVerifierService ownershipVerifierService;

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

    @Transactional
    public void deleteById(String userId, String boardId) {
        var board = findById(userId, boardId);

        columnService.deleteAllByBoardId(userId, board.getId());

        boardRepository.deleteById(board.getId());
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

    @Transactional
    public BoardResponseDTO updateById(
            String userId, String boardId, UpdateBoardRequestDTO boardDTO) {
        var boardToUpdate = findById(userId, boardId);

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

        boardRepository.save(board);

        eventPublisher.publishEvent(
                new BoardCreatedEvent(
                        eventIdGenerator.generate(), user.getId(), board.getId(), Instant.now()));

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
