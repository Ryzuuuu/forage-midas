package com.jpmc.midascore.repository;

import com.jpmc.midascore.entity.UserRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends CrudRepository<UserRecord, Long> {
    UserRecord findById(long id);

    /** Row lock (SELECT ... FOR UPDATE) held until the surrounding transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserRecord u where u.id = :id")
    Optional<UserRecord> findByIdForUpdate(@Param("id") long id);
}
