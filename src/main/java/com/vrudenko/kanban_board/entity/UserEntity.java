package com.vrudenko.kanban_board.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.vrudenko.kanban_board.base.entity.BaseUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.Collection;
import java.util.List;
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
    // Base user
    @Column(nullable = false, unique = true)
    private String email;

    @Column private String displayName;

    @OneToMany(mappedBy = "user")
    private List<BoardEntity> boards;

    // No authentication method other than password exists today. A null hash here is not a
    // forward-looking allowance -- it is an account that can never authenticate, because
    // passwordEncoder.matches(plaintext, null) is permanently false. The database now rejects
    // that write instead of silently accepting it. See
    // docs/plans/backend-modernization/04-password-hash-not-null-ddl.sql for the production-side
    // half of this change; ddl-auto is unset in the real Postgres profile, so this annotation
    // alone does not touch the production schema.
    @Column(nullable = false)
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
        return this.getId();
    }
}
