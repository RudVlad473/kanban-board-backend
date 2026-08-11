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
 * Emits the same RFC 7807 {@link ProblemDetail} envelope {@link
 * com.vrudenko.kanban_board.handler.GlobalExceptionHandler} produces, for the one rejection path
 * {@code GlobalExceptionHandler} structurally cannot reach: a genuinely unauthenticated request.
 * This fires from inside Spring Security's {@code ExceptionTranslationFilter}, before {@code
 * DispatcherServlet} ever runs -- the request never resolves to a controller method, so no
 * {@code @ExceptionHandler} in {@code GlobalExceptionHandler} is ever invoked for it. That is why
 * this class exists as a second, independent envelope producer instead of sharing a method with
 * {@code GlobalExceptionHandler}: the two run at structurally different points in the request
 * lifecycle, and only converge on producing the same JSON shape. A future change to either
 * producer's envelope shape should check the other for drift -- {@code GlobalExceptionHandler} is
 * this class's sibling producer.
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
        // GlobalExceptionHandler's ResponseEntity<ProblemDetail> arms get "instance" populated
        // for free by HttpEntityMethodProcessor, which only runs for a request that actually
        // reaches a HandlerMethod. This entry point runs earlier, from the filter chain, so that
        // machinery never fires here -- set it explicitly to keep the two producers' key sets
        // identical.
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
