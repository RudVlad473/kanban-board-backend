package com.vrudenko.kanban_board.security;

import java.util.Optional;
import org.springframework.core.MethodParameter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class CurrentUserIdResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(CurrentUserId.class);
  }

  @Override
  public String resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory)
      throws AccessDeniedException {
    var authentication = SecurityContextHolder.getContext().getAuthentication();

    var userId = Optional.ofNullable(authentication).map(auth -> auth.getPrincipal().toString());

    if (userId.isEmpty()) {
      throw new AccessDeniedException("Error while authenticating");
    }

    return userId.get();
  }
}
