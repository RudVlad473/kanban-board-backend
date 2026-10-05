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
 * In-process counterpart to {@link AbstractAppE2ETest}: {@link #signinCookie()} and {@link
 * #signinCookie(String, String)} POST to the real {@code /signin} route through {@link MockMvc}.
 *
 * <p>Why this is the way it is: they do not inject a principal via {@code .with(user(userId))},
 * which bypasses {@code AuthenticationController.authenticate} entirely; this base exists for the
 * classes that must keep exercising the real signin/session path under the cheaper in-process tier.
 * That shortcut also establishes a brand-new session on every call, so more than two requests as
 * one principal in a test method hit {@code MAX_CONCURRENT_SESSIONS = 2} and the third surfaces as
 * a 401 indistinguishable from a wrong password. For three or more authenticated calls, call {@link
 * #signinCookie()} once and replay the cookie; see docs/CODE_STYLE.md rule 4 and {@code
 * InjectionAttemptTest} for the reference call site. The class carries no {@code @SpringBootTest}
 * or {@code @AutoConfigureMockMvc}, leaving them to each subclass as {@link AbstractAppE2ETest}
 * does. {@link MockMvc} ignores {@code server.servlet.context-path}, so subclasses build URLs from
 * {@link ApiPaths} constants bare, unlike the real-socket tier.
 */
public abstract class AbstractAppMockMvcTest extends AbstractAppTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Value("${server.servlet.session.cookie.name}")
    private String cookieName;

    /**
     * Signs in as this fixture's owning user ({@link #getOwningUser()}) through a real {@code POST
     * /signin} and returns the session cookie.
     */
    protected Cookie signinCookie() throws Exception {
        return signinCookie(getOwningUser().getEmail(), getOwningUserPassword());
    }

    /**
     * Signs in as an arbitrary user through a real {@code POST /signin} and returns the session
     * cookie, for tests that need a second user's session (e.g. cross-user isolation).
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
