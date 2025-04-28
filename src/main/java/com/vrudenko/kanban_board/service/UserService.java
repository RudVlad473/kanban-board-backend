package com.vrudenko.kanban_board.service;

import com.vrudenko.kanban_board.dto.user_dto.SigninRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.UserResponseDTO;
import com.vrudenko.kanban_board.mapper.UserMapper;
import com.vrudenko.kanban_board.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UserService implements UserDetailsService {
  @Autowired private UserRepository userRepository;
  @Autowired private UserMapper userMapper;

  public Optional<UserResponseDTO> findById(String id) {
    return userRepository.findById(id).map(userEntity -> userMapper.toResponseDTO(userEntity));
  }

  public UserResponseDTO save(SigninRequestDTO userDTO) {
    var savedUser = userRepository.save(userMapper.fromSigninRequestDTO(userDTO));

    return userMapper.toResponseDTO(savedUser);
  }

  public Boolean deleteById(String id) {
    var userToDelete = userRepository.findById(id);

    if (userToDelete.isEmpty()) {
      return false;
    }

    userRepository.deleteById(id);

    return true;
  }

  public List<UserResponseDTO> findAll() {
    return userMapper.toResponseDTOList(userRepository.findAll());
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
}
