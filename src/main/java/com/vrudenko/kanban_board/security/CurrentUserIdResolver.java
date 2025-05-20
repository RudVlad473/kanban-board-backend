package com.vrudenko.kanban_board.security;

import com.vrudenko.kanban_board.exception.AppEntityNotFoundException;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
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
      throws AppEntityNotFoundException {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    var principal = authentication.getPrincipal();

    if (principal instanceof User user) {
      var userId = user.getUsername();

      if (userId == null || userId.isBlank()) {
        throw new AppEntityNotFoundException("User id");
      }

      return userId;
    } else {
      throw new AppEntityNotFoundException("User principal");
    }
  }
}
