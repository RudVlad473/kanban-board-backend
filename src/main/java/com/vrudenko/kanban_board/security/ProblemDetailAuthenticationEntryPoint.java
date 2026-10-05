package com.vrudenko.kanban_board.security;

import java.io.IOException;
import java.net.URI;

import com.vrudenko.kanban_board.constant.ErrorCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Emit the RFC 7807 {@link ProblemDetail} envelope for a genuinely unauthenticated request, the one
 * rejection {@code GlobalExceptionHandler} structurally cannot reach.
 *
 * <p>This fires inside Spring Security's {@code ExceptionTranslationFilter}, before {@code
 * DispatcherServlet} runs, so no {@code @ExceptionHandler} is ever invoked. That is why this is a
 * second, independent producer rather than a shared method: the two run at structurally different
 * points in the request lifecycle and only converge on the JSON shape. Check the other producer for
 * drift when either envelope changes.
 */
@Component
@RequiredArgsConstructor
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        var problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.UNAUTHORIZED, "Authentication is required");
        problem.setProperty(ErrorCode.CODE_PROPERTY, ErrorCode.UNAUTHENTICATED.name());
        // GlobalExceptionHandler's arms get "instance" for free from HttpEntityMethodProcessor,
        // which only runs for a request reaching a HandlerMethod. This entry point runs earlier,
        // so set it explicitly to keep both producers' key sets identical.
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
