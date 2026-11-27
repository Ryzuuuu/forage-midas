package com.jpmc.midascore.repository;

import com.jpmc.midascore.entity.TransactionRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

public interface TransactionRecordRepository extends JpaRepository<TransactionRecord, Long> {

    boolean existsBySourceTopicAndSourcePartitionAndSourceOffset(String topic, int partition, long offset);

    @Query(value = "select t from TransactionRecord t "
            + "where t.requestedSenderId = :userId or t.requestedRecipientId = :userId",
            countQuery = "select count(t) from TransactionRecord t "
                    + "where t.requestedSenderId = :userId or t.requestedRecipientId = :userId")
    Page<TransactionRecord> findByParticipant(@Param("userId") long userId, Pageable pageable);
}
