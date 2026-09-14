package com.bookmyseat.booking.repository;

import com.bookmyseat.booking.entity.OutboxEvent;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * The oldest unpublished events, in the order they were created. The id breaks ties
     * between events created in the same microsecond. Served by idx_outbox_published_created.
     */
    @Query("SELECT e FROM OutboxEvent e WHERE e.published = false ORDER BY e.createdAt ASC, e.id ASC")
    List<OutboxEvent> findUnpublished(Limit limit);

    /**
     * Marks one event published, in a short transaction of its own.
     *
     * <p>Conditional on still being unpublished, so the count says whether this call did it.
     * With a single publisher it always does; the condition keeps a second one from double
     * counting rather than from double sending, which it cannot prevent - see OutboxPublisher.
     *
     * @return 1 if marked by this call, 0 if it was already published
     */
    @Transactional
    @Modifying
    @Query("UPDATE OutboxEvent e SET e.published = true WHERE e.id = :id AND e.published = false")
    int markPublished(@Param("id") Long id);
}
