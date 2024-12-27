# kanban-board

# Architecture

Project utilizes layered architecture with some helpful additions that enhance reusability
- base
    Basic interfaces for entities, that are used for entities & DTOs 

# Testing philosophy

## Unit tests
Are only written for 
- `services`
- `DTOs`

Since both `entities` and `repositories` are boilerplate and don't contain custom logic

## Integration tests
Are only written for `controllers`