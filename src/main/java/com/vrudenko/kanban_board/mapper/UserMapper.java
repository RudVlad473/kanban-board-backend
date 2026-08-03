package com.vrudenko.kanban_board.mapper;

import com.vrudenko.kanban_board.dto.user_dto.SignupRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.UserResponseDTO;
import com.vrudenko.kanban_board.entity.UserEntity;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * If interface or an abstract class is used here, it should provide an implementation, otherwise it
 * won't work
 *
 * <p>This mapper intentionally declares no method that accepts a {@code UserEntity} and returns a
 * request DTO. {@code UserEntity} implements Spring Security's {@link
 * org.springframework.security.core.userdetails.UserDetails}, so its {@code getPassword()} returns
 * the stored bcrypt hash, and request DTOs declare a matching {@code password} property. Under this
 * mapper's {@code unmappedTargetPolicy = ReportingPolicy.IGNORE}, MapStruct would silently map the
 * two by name. If such a method is ever genuinely needed, it must carry an explicit {@code
 * &#064;Mapping(target = "password", ignore = true)}. See quick task {@code 260803-ns9}.
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
