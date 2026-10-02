package com.bookmyseat.booking.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * A stable page envelope, field for field the one event-service returns from
 * GET /api/events.
 *
 * <p>The same shape on purpose: a client that can page one list can page the other with
 * the same code. Duplicated rather than shared for the reason every client DTO in this
 * service is - a common module would couple the two services' deploy cycles.
 *
 * <p>Spring Data's PageImpl is deliberately not returned directly: Boot 3.3 warns that its
 * JSON shape is not a stable contract, and CLAUDE.md requires every endpoint to return a
 * DTO. This record is that DTO.
 */
@Schema(description = "One page of results")
public record PageResponse<T>(

        List<T> content,

        @Schema(description = "Zero-based page index", example = "0")
        int page,

        @Schema(example = "20")
        int size,

        @Schema(example = "42")
        long totalElements,

        @Schema(example = "3")
        int totalPages,

        @Schema(description = "True when this is the final page", example = "false")
        boolean last
) {

    /**
     * Wraps already-mapped content in the paging facts of the page it came from.
     *
     * <p>Takes the content rather than a per-element mapper, unlike event-service's
     * version: a booking's seats are fetched for the whole page in one statement and
     * handed to the mapper, so the mapping cannot be a function of the entity alone.
     */
    public static <T> PageResponse<T> of(Page<?> source, List<T> content) {
        return new PageResponse<>(
                content,
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages(),
                source.isLast());
    }
}
