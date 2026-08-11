package com.vrudenko.kanban_board.repository;

import java.util.Optional;

import com.vrudenko.kanban_board.entity.UserEntity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, String> {
    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);
}
