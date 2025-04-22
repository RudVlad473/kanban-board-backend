package com.vrudenko.kanban_board.constant;

import org.springframework.beans.factory.annotation.Value;

public final class SecurityConstants {
  @Value("${server.servlet.session.cookie.name}")
  public static String SESSION_NAME;
}
