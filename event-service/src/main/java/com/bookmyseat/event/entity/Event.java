package com.bookmyseat.event.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "events")
@Getter
@Setter
@NoArgsConstructor
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /**
     * Maps the TEXT column.
     *
     * <p>Not @Lob: on a String that maps to CLOB, which Hibernate 6 renders as
     * MySQL tinytext, and ddl-auto=validate then rejects the TEXT column with
     * "found [text], but expecting [tinytext]". LONGVARCHAR is the JDBC type
     * that corresponds to TEXT. Column definition stays in Flyway, not here.
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "description")
    private String description;

    @Column(name = "category", nullable = false, length = 50)
    private String category;

    @Column(name = "poster_url", length = 500)
    private String posterUrl;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;
}
