package com.vrudenko.kanban_board.repository;

import com.vrudenko.kanban_board.entity.BoardEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoardRepository extends JpaRepository<BoardEntity, String> {}
