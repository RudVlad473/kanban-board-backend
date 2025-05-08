package com.vrudenko.kanban_board.service;

import com.google.common.annotations.VisibleForTesting;
import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.SigninRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.UserResponseDTO;
import com.vrudenko.kanban_board.entity.BoardEntity;
import com.vrudenko.kanban_board.entity.UserEntity;
import com.vrudenko.kanban_board.exception.AppEntityNotFoundException;
import com.vrudenko.kanban_board.mapper.BoardMapper;
import com.vrudenko.kanban_board.mapper.UserMapper;
import com.vrudenko.kanban_board.repository.BoardRepository;
import com.vrudenko.kanban_board.repository.UserRepository;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UserService implements UserDetailsService {
  @Autowired private UserRepository userRepository;
  @Autowired private UserMapper userMapper;

  @Autowired private BoardService boardService;
  @Autowired private BoardMapper boardMapper;
  @Autowired private BoardRepository boardRepository;

  public UserEntity findById(String id) throws AppEntityNotFoundException {
    var user = userRepository.findById(id);

    if (user.isEmpty()) {
      throw new AppEntityNotFoundException("User");
    }

    return user.get();
  }

  public UserResponseDTO save(SigninRequestDTO userDTO) {
    var savedUser = userRepository.save(userMapper.fromSigninRequestDTO(userDTO));

    return userMapper.toResponseDTO(savedUser);
  }

  @Transactional
  public Boolean deleteById(String id) {
    var userToDelete = userRepository.findById(id);

    if (userToDelete.isEmpty()) {
      return false;
    }

    boardService.deleteAllByUserId(id);

    userRepository.deleteById(id);

    return true;
  }

  @Transactional
  public BoardResponseDTO addBoardByUserId(String userId, SaveBoardRequestDTO boardDTO) {
    var user = findById(userId);

    var board = boardMapper.fromSaveBoardRequestDTO(boardDTO);
    board.setUser(user);

    // TODO: Disallow duplicating board names for a single user
    return boardMapper.toResponseDTO(boardRepository.save(board));
  }

  @Override
  // username is mapped to userid inside LoginController
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    var user = userRepository.findById(username);

    if (user.isEmpty()) {
      throw new UsernameNotFoundException(username + " was not found");
    }

    return user.get();
  }

  @VisibleForTesting
  public List<UserResponseDTO> findAll() {
    return userMapper.toResponseDTOList(userRepository.findAll());
  }

  @VisibleForTesting
  @Transactional
  public void deleteAll() {
    for (var user : userRepository.findAll()) {
      deleteById(user.getId());
    }
  }
}
