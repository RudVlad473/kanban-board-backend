package com.vrudenko.kanban_board.entity;

import com.vrudenko.kanban_board.base.BaseId;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@MappedSuperclass
@Getter
@Setter
public abstract class BaseEntity implements BaseId {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  protected String id;
}
