package com.vrudenko.kanban_board.entity;

import com.vrudenko.kanban_board.base.BaseId;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

@MappedSuperclass
@Getter
public abstract class BaseEntity implements BaseId {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  protected String id;
}
