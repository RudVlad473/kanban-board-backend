package com.vrudenko.kanban_board.support.fixtures;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.user_dto.SigninRequestDTO;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * In-process counterpart to {@link AbstractAppE2ETest} (D-03 tier downgrade). Where that class
 * drives a real HTTP socket via REST Assured, this one drives the same {@code
 * AuthenticationController.authenticate} call site through Spring's in-process {@link MockMvc}
 * dispatch -- {@link #signinCookie()} and {@link #signinCookie(String, String)} deliberately POST
 * to the real {@code /signin} route rather than injecting a pre-authenticated principal via {@code
 * .with(user(userId))} (the shortcut the four {@code controller/*ControllerTest} classes use for
 * already-authenticated scenarios): that shortcut bypasses {@code
 * AuthenticationController.authenticate} entirely, and this base exists precisely for the small set
 * of classes that must keep exercising the real signin/session path under the cheaper in-process
 * tier (RESEARCH.md Common Pitfalls, Pitfall 2 -- this is the phase-wide proof of Assumption A2).
 *
 * <p>Carries no class-level {@code @SpringBootTest}/{@code @AutoConfigureMockMvc} -- matching
 * {@link AbstractAppE2ETest}'s own precedent of leaving Spring Boot test annotations to each
 * concrete subclass rather than assuming a base class can supply them by inheritance.
 *
 * <p>{@link MockMvc} does not apply {@code server.servlet.context-path}: subclasses build request
 * URLs from {@link ApiPaths} constants bare, without the context-path prefix {@link
 * AbstractAppE2ETest}'s real-socket tier needs.
 */
public abstract class AbstractAppMockMvcTest extends AbstractAppTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Value("${server.servlet.session.cookie.name}")
    private String cookieName;

    /**
     * Signs in as this fixture's owning user ({@link #getOwningUser()}) through a real {@code POST
     * /signin}, and returns the session cookie the response set.
     */
    protected Cookie signinCookie() throws Exception {
        return signinCookie(getOwningUser().getEmail(), getOwningUserPassword());
    }

    /**
     * Signs in as an arbitrary user through a real {@code POST /signin}, and returns the session
     * cookie the response set. For tests that need a second, non-owning user's session (e.g.
     * cross-user isolation checks).
     */
    protected Cookie signinCookie(String email, String password) throws Exception {
        var result =
                mockMvc.perform(
                                post(ApiPaths.SIGNIN)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        SigninRequestDTO.builder()
                                                                .email(email)
                                                                .password(password)
                                                                .build())))
                        .andExpect(status().isOk())
                        .andReturn();

        return result.getResponse().getCookie(cookieName);
    }
}
