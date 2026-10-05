package com.vrudenko.kanban_board;

import com.vrudenko.kanban_board.support.containers.AbstractPostgresContainerTest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Boots the full context; needs a container because the test profile names no datasource.
@SpringBootTest
class KanbanBoardApplicationTests extends AbstractPostgresContainerTest {

    @Test
    void contextLoads() {}
}
