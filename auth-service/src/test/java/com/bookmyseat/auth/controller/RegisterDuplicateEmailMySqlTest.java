package com.bookmyseat.auth.controller;

import com.bookmyseat.auth.MySqlContainerTest;
import com.bookmyseat.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Review finding #4, case 2: a duplicate registration that gets past the pre-check is a 409,
 * not a 500.
 *
 * <h2>What this proves, and what it does not</h2>
 * It is NOT a two-thread race, and saying so matters more than the test itself. A genuine race
 * would have to get both threads past {@code existsByEmail} before either commits, and nothing
 * in the code offers a place to synchronise them - so the test would pass through the
 * pre-check most of the time, prove nothing, and occasionally prove everything. An honest
 * deterministic test beats a strong flaky one.
 *
 * <p>So the pre-check is stubbed to miss, which is exactly what the loser of the race
 * experiences: {@code existsByEmail} answers false for an email that is already in the table.
 * Everything after that is real, and it is the part that was broken -
 *
 * <ul>
 *   <li>a real UNIQUE index on {@code users.email} refuses the INSERT;
 *   <li>a real {@code DataIntegrityViolationException} comes back from {@code saveAndFlush},
 *       rather than one a mock was told to throw;
 *   <li>the translation happens inside a transaction the violation has already marked
 *       rollback-only, and the 409 still comes out. That is the question the arrangement
 *       turns on - had the catch tried to commit instead of throwing, this test would see a
 *       500 from an {@code UnexpectedRollbackException}, which is how the cheaper
 *       mocked-repository version of this test would have missed it.
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegisterDuplicateEmailMySqlTest extends MySqlContainerTest {

    private static final String EMAIL = "arshad@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Spied, not mocked: every method but the pre-check has to be the real one, because the
     * real one is what talks to the unique index.
     */
    @SpyBean
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM users");
    }

    @Test
    @DisplayName("the pre-check answers 409 on the ordinary second registration")
    void theSecondRegistrationIsRefusedByThePreCheck() throws Exception {
        register(EMAIL).andExpect(status().isCreated());

        // Nothing stubbed here: this is the common path, and it costs one indexed SELECT
        // rather than a failed INSERT and a rolled-back transaction.
        register(EMAIL)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email is already registered"));

        assertThat(countUsers()).isEqualTo(1);
    }

    @Test
    @DisplayName("a registration that gets PAST the pre-check is refused by the unique index, and answers the same 409")
    void theUniqueIndexAnswersTheSame409() throws Exception {
        register(EMAIL).andExpect(status().isCreated());

        // The race, made deterministic. This is what the loser sees: the row is committed and
        // the pre-check says the email is free anyway.
        doReturn(false).when(userRepository).existsByEmail(anyString());

        register(EMAIL)
                .andExpect(status().isConflict())
                // The SAME message as the pre-check's, and asserted as the same literal on
                // purpose: two different 409s for one situation would be a difference the
                // caller can see and can do nothing with.
                .andExpect(jsonPath("$.message").value("Email is already registered"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.path").value("/api/auth/register"));

        // The refused INSERT wrote nothing, so the transaction did roll back rather than
        // committing a second row behind the 409.
        assertThat(countUsers()).isEqualTo(1);
    }

    private ResultActions register(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\","
                        + "\"password\":\"correct-horse-battery\","
                        + "\"fullName\":\"Arshad\"}"));
    }

    /** Plain JDBC, so no persistence context can mask what MySQL actually holds. */
    private int countUsers() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        return count == null ? 0 : count;
    }
}
