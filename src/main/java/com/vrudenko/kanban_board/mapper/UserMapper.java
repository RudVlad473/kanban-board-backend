package com.vrudenko.kanban_board.mapper;

import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.SaveUserRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.UserResponseDTO;
import com.vrudenko.kanban_board.entity.BoardEntity;
import java.util.List;

import com.vrudenko.kanban_board.entity.UserEntity;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

/**
 * If interface or an abstract class is used here, it should provide an implementation, otherwise it
 * won't work
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface UserMapper {
  UserResponseDTO toResponseDTO(UserEntity dto);

  UserEntity fromSaveBoardRequestDTO(SaveUserRequestDTO dto);

  List<UserResponseDTO> toResponseDTOList(List<UserEntity> dto);

  SaveUserRequestDTO toSaveBoardRequestDTO(UserEntity dto);
}
