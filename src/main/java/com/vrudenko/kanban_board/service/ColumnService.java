package com.vrudenko.kanban_board.service;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.primitives.Ints;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.UpdateColumnRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.entity.BoardEntity;
import com.vrudenko.kanban_board.entity.ColumnEntity;
import com.vrudenko.kanban_board.mapper.ColumnMapper;
import com.vrudenko.kanban_board.repository.ColumnRepository;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
public class ColumnService {
    @Autowired private ColumnRepository columnRepository;

    @Autowired private ColumnMapper columnMapper;

    @Autowired private TaskService taskService;

    @Autowired private OwnershipVerifierService ownershipVerifierService;

    @Autowired private EntityManager entityManager;

    @Transactional
    public void deleteAllByBoardId(String userId, String boardId) {
        var pair = ownershipVerifierService.verifyOwnershipOfBoard(userId, boardId);

        for (var column : columnRepository.findAllByBoardId(pair.getSecond().getId())) {
            taskService.deleteAllByColumn(column);
        }

        columnRepository.deleteAllByBoardId(pair.getSecond().getId());
    }

    public ColumnResponseDTO save(SaveColumnRequestDTO columnDTO, BoardEntity board) {
        var column = columnMapper.fromSaveColumnRequestDTO(columnDTO);
        column.setBoard(board);

        columnRepository.save(column);

        return columnMapper.toColumnResponseDTO(column);
    }

    @Transactional
    public TaskResponseDTO addTaskByColumnId(
            String userId, String columnId, SaveTaskRequestDTO taskDTO) {
        var column = findById(userId, columnId);

        return taskService.save(taskDTO, column);
    }

    @Transactional
    public ColumnEntity findById(String userId, String columnId) {
        var pair = ownershipVerifierService.verifyOwnershipOfColumn(userId, columnId);

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
    public ColumnResponseDTO updateById(
            String userId, String columnId, UpdateColumnRequestDTO dto) {
        var column = findById(userId, columnId);

        if (!column.getVersion().equals(dto.getVersion())) {
            throw new OptimisticLockingFailureException(
                    "Column was modified by another request, please refetch.");
        }

        column.setName(dto.getName());

        columnRepository.save(column);

        // Hibernate only bumps the in-memory @Version field once the UPDATE statement actually
        // runs, which normally happens at transaction commit, not at save(). Flushing here forces
        // that UPDATE (and the version increment) to happen before the response DTO is built, so
        // the caller sees the new version instead of the stale pre-update one (D-01).
        entityManager.flush();

        return columnMapper.toColumnResponseDTO(column);
    }

    @VisibleForTesting
    public int getColumnCountByBoardId(String boardId) {
        return Ints.checkedCast(columnRepository.countByBoardId(boardId));
    }

    @Transactional
    public List<ColumnResponseDTO> findAllByBoardId(String userId, String boardId) {
        var pair = ownershipVerifierService.verifyOwnershipOfBoard(userId, boardId);

        return columnMapper.toColumnResponseDTOList(
                columnRepository.findAllByBoardId(pair.getSecond().getId()));
    }

    // TODO: implement delete logic
}
