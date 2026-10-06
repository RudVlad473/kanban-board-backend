package com.vrudenko.kanban_board.service;

import com.vrudenko.kanban_board.dto.activity_dto.ActivityLogResponseDTO;
import com.vrudenko.kanban_board.mapper.ActivityLogMapper;
import com.vrudenko.kanban_board.repository.ActivityLogRepository;

import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class ActivityLogService {
    @Autowired private ActivityLogRepository activityLogRepository;

    @Autowired private ActivityLogMapper activityLogMapper;

    @Autowired private OwnershipVerifierService ownershipVerifierService;

    /**
     * Return a board's activity feed, newest first.
     *
     * Decisions:
     *
     * The caller's pageable sort is deliberately discarded: the service always sorts by
     * createdAt descending, then id descending. The second key makes it a
     * total order; without it, rows sharing a createdAt instant have no defined
     * relative position, so between two page requests a row can appear on two pages or on none.
     *
     * Offset pagination still cannot give a stable snapshot across concurrent writes: a row
     * inserted while a client pages can shift later pages by one, so an item may be seen twice or
     * missed. That is inherent to offset pagination; keyset pagination is the fix and is not
     * shipped here.
     */
    @Transactional
    public Page<ActivityLogResponseDTO> findAllByBoardId(
            String userId, String boardId, Pageable pageable) {
        var pair = ownershipVerifierService.verifyOwnershipOfBoard(userId, boardId);

        var createdAtDesc = Sort.Order.desc("createdAt");
        var idDesc = Sort.Order.desc("id");
        var deterministicSort = Sort.by(createdAtDesc, idDesc);
        var effectivePageable =
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), deterministicSort);

        var page =
                activityLogRepository.findAllByBoardId(pair.getSecond().getId(), effectivePageable);

        return page.map(activityLogMapper::toActivityLogResponseDTO);
    }
}
