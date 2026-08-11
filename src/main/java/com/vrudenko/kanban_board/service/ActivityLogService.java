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
     * Reads a board's activity feed, newest first (READ-01, READ-02).
     *
     * <p>The caller's {@code pageable} may carry its own {@code sort}, but it is deliberately
     * discarded here: the service always sorts by {@code createdAt} descending, then by {@code id}
     * descending. The second key is what turns this into a <i>total</i> order rather than merely a
     * newest-first one — without it, rows sharing an identical {@code createdAt} instant have no
     * defined relative position, so the database may return them in a different order between two
     * page requests and a row can appear on two pages or on none. ULIDs sort lexicographically by
     * generation time, so the tiebreak degrades gracefully into newest-first rather than into an
     * arbitrary order. A caller-chosen sort is therefore never allowed to reintroduce that
     * non-determinism.
     *
     * <p>Even with a total order, offset pagination still cannot guarantee a stable snapshot across
     * concurrent writes: a row inserted while a client is paging can shift later pages by one
     * position, so an item may be seen twice or missed entirely. This is inherent to offset
     * pagination, not a bug in this method, and is exactly why keyset pagination is tracked
     * separately as PAGE-V2-01 rather than shipped here.
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
