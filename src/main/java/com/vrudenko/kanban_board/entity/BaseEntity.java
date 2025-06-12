package com.vrudenko.kanban_board.entity;

import com.vrudenko.kanban_board.base.entity.BaseId;
import com.vrudenko.kanban_board.config.RandFlakeId;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

@MappedSuperclass
@Getter
@Setter
public abstract class BaseEntity implements BaseId {
    @Id @RandFlakeId protected String id;
}
