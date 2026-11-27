package com.jpmc.midascore.web;

import com.jpmc.midascore.entity.UserRecord;
import com.jpmc.midascore.foundation.Balance;
import com.jpmc.midascore.repository.TransactionRecordRepository;
import com.jpmc.midascore.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
public class BalanceController {
    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository users;
    private final TransactionRecordRepository transactions;

    public BalanceController(UserRepository users, TransactionRecordRepository transactions) {
        this.users = users;
        this.transactions = transactions;
    }

    /** Current balance. An unknown user has a balance of zero, as the Midas API contract requires. */
    @GetMapping("/balance")
    public Balance balance(@RequestParam long userId) {
        UserRecord user = users.findById(userId);
        return new Balance(user == null ? 0f : user.getBalance().floatValue());
    }

    /** A user's transactions as sender or recipient, newest first, including rejected ones. */
    @GetMapping("/users/{userId}/transactions")
    @Transactional(readOnly = true)
    public TransactionPage transactions(@PathVariable long userId,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        if (users.findById(userId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No user with id " + userId);
        }
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        Page<TransactionView> result = transactions
                .findByParticipant(userId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "processedAt", "id")))
                .map(TransactionView::of);
        return new TransactionPage(result.getContent(), page, size, result.getTotalElements());
    }

    public record TransactionPage(List<TransactionView> items, int page, int size, long total) {
    }
}
