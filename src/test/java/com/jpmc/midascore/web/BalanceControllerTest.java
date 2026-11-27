package com.jpmc.midascore.web;

import com.jpmc.midascore.entity.MessageSource;
import com.jpmc.midascore.entity.TransactionRecord;
import com.jpmc.midascore.entity.UserRecord;
import com.jpmc.midascore.repository.TransactionRecordRepository;
import com.jpmc.midascore.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BalanceController.class)
class BalanceControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockBean
    private UserRepository users;
    @MockBean
    private TransactionRecordRepository transactions;

    @Test
    void returnsTheBalanceOfAKnownUser() throws Exception {
        when(users.findById(7L)).thenReturn(new UserRecord("waldorf", new BigDecimal("444.55")));

        mvc.perform(get("/balance").param("userId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(444.55));
    }

    @Test
    void unknownUserHasZeroBalance() throws Exception {
        mvc.perform(get("/balance").param("userId", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(0.0));
    }

    @Test
    void missingOrMalformedUserIdIsABadRequest() throws Exception {
        mvc.perform(get("/balance")).andExpect(status().isBadRequest());
        mvc.perform(get("/balance").param("userId", "abc")).andExpect(status().isBadRequest());
    }

    @Test
    void listsAUsersTransactions() throws Exception {
        UserRecord sender = new UserRecord("a", new BigDecimal("10"));
        UserRecord recipient = new UserRecord("b", new BigDecimal("10"));
        when(users.findById(3L)).thenReturn(sender);
        TransactionRecord record = TransactionRecord.accepted(new MessageSource("t", 0, 1),
                sender, recipient, new BigDecimal("5.00"), new BigDecimal("0.50"));
        when(transactions.findByParticipant(eq(3L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(record), PageRequest.of(0, 20), 1));

        mvc.perform(get("/users/3/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].amount").value(5.00))
                .andExpect(jsonPath("$.items[0].incentive").value(0.50))
                .andExpect(jsonPath("$.items[0].status").value("ACCEPTED"));
    }

    @Test
    void transactionsOfUnknownUserIsNotFound() throws Exception {
        mvc.perform(get("/users/42/transactions")).andExpect(status().isNotFound());
    }

    @Test
    void oversizedPageIsRejected() throws Exception {
        when(users.findById(3L)).thenReturn(new UserRecord("a", new BigDecimal("1")));
        mvc.perform(get("/users/3/transactions").param("size", "500")).andExpect(status().isBadRequest());
    }
}
