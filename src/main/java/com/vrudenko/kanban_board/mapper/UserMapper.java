package com.vrudenko.kanban_board.mapper;

import java.util.List;

import com.vrudenko.kanban_board.dto.user_dto.SignupRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.UserResponseDTO;
import com.vrudenko.kanban_board.entity.UserEntity;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Declare no method that accepts a UserEntity and returns a request DTO.
 *
 * UserEntity implements Spring Security's
 * org.springframework.security.core.userdetails.UserDetails, so its getPassword() returns
 * the stored bcrypt hash, and request DTOs declare a matching password property. Under
 * unmappedTargetPolicy = ReportingPolicy.IGNORE, MapStruct would silently map the two by
 * name. If such a method is ever genuinely needed, it must declare an explicit mapping that ignores
 * the password target.
 */
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE)
public abstract class UserMapper {
    @Autowired PasswordEncoder passwordEncoder;

    public abstract UserResponseDTO toResponseDTO(UserEntity entity);

    public abstract List<UserResponseDTO> toResponseDTOList(List<UserEntity> entities);

    @Mapping(
            target = "passwordHash",
            expression = "java(passwordEncoder.encode(dto.getPassword()))")
    public abstract UserEntity fromSignupRequestDTO(SignupRequestDTO dto);
}
