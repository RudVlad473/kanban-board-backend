package com.vrudenko.kanban_board.repository;

import com.vrudenko.kanban_board.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, String> {
  UserEntity findByEmail(String email);
}
