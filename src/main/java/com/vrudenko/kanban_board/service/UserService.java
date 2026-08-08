package com.vrudenko.kanban_board.service;

import com.google.common.annotations.VisibleForTesting;
import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.SignupRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.UpdateThemeRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.UserResponseDTO;
import com.vrudenko.kanban_board.entity.UserEntity;
import com.vrudenko.kanban_board.exception.AppDuplicateResourceException;
import com.vrudenko.kanban_board.exception.AppEntityNotFoundException;
import com.vrudenko.kanban_board.mapper.UserMapper;
import com.vrudenko.kanban_board.repository.BoardRepository;
import com.vrudenko.kanban_board.repository.UserRepository;
import jakarta.transaction.Transactional;
import java.util.List;
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

    @Autowired private BoardRepository boardRepository;

    @Transactional
    public UserEntity findById(String id) throws AppEntityNotFoundException {
        var user = userRepository.findById(id);

        if (user.isEmpty()) {
            throw new AppEntityNotFoundException("User");
        }

        return user.get();
    }

    public UserEntity findByEmail(String email) throws AppEntityNotFoundException {
        var user = userRepository.findByEmail(email);

        if (user.isEmpty()) {
            throw new AppEntityNotFoundException("User");
        }

        return user.get();
    }

    public UserResponseDTO save(SignupRequestDTO userDTO) {
        return userMapper.toResponseDTO(
                userRepository.save(userMapper.fromSignupRequestDTO(userDTO)));
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

        if (boardRepository.existsByUserIdAndName(user.getId(), boardDTO.getName())) {
            throw new AppDuplicateResourceException("Board");
        }

        return boardService.save(boardDTO, user);
    }

    // GAP-05 (D-10..D-12). userId comes from @CurrentUserId (the session) in UserController --
    // never a path variable or request-body field -- so this method has no ownership chain to
    // verify: UserService is the identity root, findById(String) above already is the sanctioned
    // direct-repository load (docs/CODE_STYLE.md rule 2).
    @Transactional
    public UserResponseDTO findThemeByUserId(String userId) {
        var user = findById(userId);

        return userMapper.toResponseDTO(user);
    }

    // No entityManager.flush()/version-compare here, unlike TaskService/ColumnService/
    // SubtaskService's Update* methods -- UserEntity carries no @Version field and this write is
    // deliberately last-write-wins (see UpdateThemeRequestDTO's Javadoc, T-06-29).
    @Transactional
    public UserResponseDTO updateTheme(String userId, UpdateThemeRequestDTO dto) {
        var user = findById(userId);

        user.setTheme(dto.getTheme());
        userRepository.save(user);

        return userMapper.toResponseDTO(user);
    }

    @Override
    // username is mapped to userid inside AuthenticationController
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
