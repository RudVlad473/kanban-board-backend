package com.vrudenko.kanban_board.mapper;

import com.vrudenko.kanban_board.dto.user_dto.SigninRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.SignupRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.UserResponseDTO;
import com.vrudenko.kanban_board.entity.UserEntity;
import java.util.List;

import lombok.Getter;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * If interface or an abstract class is used here, it should provide an implementation, otherwise it
 * won't work
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE)
public abstract class UserMapper {
  @Autowired PasswordEncoder passwordEncoder;

  public abstract UserResponseDTO toResponseDTO(UserEntity entity);

  public abstract UserEntity fromSigninRequestDTO(SigninRequestDTO dto);

  public abstract List<UserResponseDTO> toResponseDTOList(List<UserEntity> entities);

  public abstract SigninRequestDTO toSigninRequestDTO(UserEntity entity);

  public abstract SignupRequestDTO toSignupRequestDTO(UserEntity entity);

  @Mapping(target = "passwordHash", expression = "java(passwordEncoder.encode(dto.getPassword()))")
  public abstract UserEntity fromSignupRequestDTO(SignupRequestDTO dto);
}
