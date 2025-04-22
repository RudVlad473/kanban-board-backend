package com.vrudenko.kanban_board.entity;

import com.vrudenko.kanban_board.base.BaseUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import net.minidev.json.annotate.JsonIgnore;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@Table(name = "users")
public class UserEntity extends BaseEntity implements BaseUser, UserDetails {
  // Base user
  @Column(nullable = false, unique = true)
  private String email;

  @Column private String displayName;

  // since we may have other kinds of authentication that may not require password, it can be null
  @Column(nullable = true)
  @JsonIgnore
  private String passwordHash;

  // SECURITY INFO
  @Override
  @JsonIgnore
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return null;
  }

  @Override
  public String getPassword() {
    return this.getPasswordHash();
  }

  @Override
  public String getUsername() {
    return this.getEmail();
  }
}
