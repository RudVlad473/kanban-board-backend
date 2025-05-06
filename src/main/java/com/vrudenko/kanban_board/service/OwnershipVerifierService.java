package com.vrudenko.kanban_board.service;

import com.google.common.annotations.VisibleForTesting;
import com.vrudenko.kanban_board.entity.BoardEntity;
import com.vrudenko.kanban_board.entity.ColumnEntity;
import com.vrudenko.kanban_board.entity.TaskEntity;
import com.vrudenko.kanban_board.entity.UserEntity;
import com.vrudenko.kanban_board.exception.AppAccessDeniedException;
import com.vrudenko.kanban_board.exception.AppEntityNotFoundException;
import com.vrudenko.kanban_board.repository.BoardRepository;
import com.vrudenko.kanban_board.repository.ColumnRepository;
import com.vrudenko.kanban_board.repository.TaskRepository;
import com.vrudenko.kanban_board.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OwnershipVerifierService {
  @Autowired private UserRepository userRepository;
  @Autowired private BoardRepository boardRepository;
  @Autowired private TaskRepository taskRepository;
  @Autowired private ColumnRepository columnRepository;

  public Pair<UserEntity, BoardEntity> verifyOwnershipOfBoard(String userId, String boardId)
      throws AppEntityNotFoundException, AppAccessDeniedException {
    if (userId == null || boardId == null) {
      throw new IllegalArgumentException();
    }

    var user = userRepository.findById(userId);

    if (user.isEmpty()) {
      throw new AppEntityNotFoundException("User");
    }

    var board = boardRepository.findById(boardId);

    if (board.isEmpty()) {
      throw new AppEntityNotFoundException("Board");
    }

    var userOwnsBoard = board.get().getUser().getId().equals(user.get().getId());

    if (!userOwnsBoard) {
      throw new AppAccessDeniedException("Board");
    }

    return Pair.of(user.get(), board.get());
  }

  public Pair<UserEntity, ColumnEntity> verifyOwnershipOfColumn(String userId, String columnId)
      throws AppEntityNotFoundException, AppAccessDeniedException {
    var column = columnRepository.findById(columnId);

    if (column.isEmpty()) {
      throw new AppEntityNotFoundException("Column");
    }

    var pair = verifyOwnershipOfBoard(userId, column.get().getBoard().getId());

    return Pair.of(pair.getFirst(), column.get());
  }

  public Pair<UserEntity, TaskEntity> verifyOwnershipOfTask(String userId, String taskId)
      throws AppEntityNotFoundException, AppAccessDeniedException {
    var task = taskRepository.findById(taskId);

    if (task.isEmpty()) {
      throw new AppEntityNotFoundException("Task");
    }

    var pair = verifyOwnershipOfColumn(userId, task.get().getColumn().getId());

    return Pair.of(pair.getFirst(), task.get());
  }
}
