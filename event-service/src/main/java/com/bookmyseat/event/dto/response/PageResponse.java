package com.bookmyseat.event.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * A stable page envelope.
 *
 * <p>Spring Data's PageImpl is deliberately not returned directly: Boot 3.3 warns
 * that its JSON shape is not a stable contract, and CLAUDE.md requires every
 * endpoint to return a DTO. This record is that DTO.
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

    /** Hand-written mapping (CLAUDE.md): converts the entity page and rewraps it. */
    public static <E, T> PageResponse<T> from(Page<E> source, Function<E, T> mapper) {
        return new PageResponse<>(
                source.getContent().stream().map(mapper).toList(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages(),
                source.isLast());
    }
}
