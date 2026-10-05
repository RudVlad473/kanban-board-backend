package com.vrudenko.kanban_board.entity;

import java.util.Collection;
import java.util.List;

import com.vrudenko.kanban_board.base.entity.BaseUser;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
// @EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@Table(name = "users")
public class UserEntity extends BaseEntity implements BaseUser, UserDetails {
    @Column(nullable = false, unique = true)
    private String email;

    @Column private String displayName;

    @OneToMany(mappedBy = "user")
    private List<BoardEntity> boards;

    // Reject a null hash at the database: it would be an account that can never authenticate.
    //
    // No authentication method other than password exists, and
    // passwordEncoder.matches(plaintext, null) is permanently false. ddl-auto is unset in the real
    // Postgres profile, so this annotation alone does not change the production schema; see
    // docs/plans/backend-modernization/04-password-hash-not-null-ddl.sql for the production half.
    @Column(nullable = false)
    @JsonIgnore
    private String passwordHash;

    // Default LIGHT for every user with no explicit preference.
    //
    // @Builder.Default is mandatory: @Builder silently discards a plain field initialiser without
    // it, which would write null into this NOT NULL column on every signup (UserService.addUser
    // builds via UserEntity.builder()).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ThemePreference theme = ThemePreference.LIGHT;

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
        return this.getId();
    }
}
