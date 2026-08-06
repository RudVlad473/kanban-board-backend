package com.vrudenko.kanban_board;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Boots the full context, including JPA, and the test profile no longer names a datasource
// (04.2, D-01) -- a container is required for even this smoke test to start.
@SpringBootTest
class KanbanBoardApplicationTests extends AbstractPostgresContainerTest {

    @Test
    void contextLoads() {}
}
