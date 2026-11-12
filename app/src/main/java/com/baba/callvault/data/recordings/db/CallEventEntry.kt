/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One carrier call-outcome event awaiting (or done with) upload to
 * WhatsApp Max (`POST /api/v1/call-events`). Persisted the moment
 * the call ends — a missed/rejected call has no recording, so
 * without this row an offline gap would lose the event forever.
 *
 * The [clientEventId] is the idempotency key: generated once per
 * call, carried through every WorkManager retry, and UNIQUE
 * server-side per account — so a retry uploads the same event,
 * never a second one. It is a UUID, never a filename or number.
 */
@Entity(tableName = "call_events")
data class CallEventEntry(
    @PrimaryKey val clientEventId: String,
    val direction: String,
    val outcome: String,
    val phoneNumber: String?,
    val durationSeconds: Long?,
    val occurredAtMillis: Long,
    val uploadState: Int?,
    val createdAtMillis: Long,
)

@Dao
interface CallEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: CallEventEntry): Long

    @Query("SELECT * FROM call_events WHERE clientEventId = :clientEventId LIMIT 1")
    suspend fun findById(clientEventId: String): CallEventEntry?

    /**
     * A call fingerprint already recorded and not yet failed: reuse
     * its event id instead of minting a second event for the same
     * call (process-death restart racing the IDLE handler, double
     * broadcasts). A FAILED row may be superseded — its upload
     * already gave up.
     */
    @Query(
        "SELECT * FROM call_events WHERE phoneNumber IS :phoneNumber " +
            "AND occurredAtMillis = :occurredAtMillis AND direction = :direction " +
            "AND (uploadState IS NULL OR uploadState != :failedState) LIMIT 1"
    )
    suspend fun findLiveByFingerprint(
        phoneNumber: String?,
        occurredAtMillis: Long,
        direction: String,
        failedState: Int,
    ): CallEventEntry?

    @Query("UPDATE call_events SET uploadState = :state WHERE clientEventId = :clientEventId")
    suspend fun setState(clientEventId: String, state: Int)

    /**
     * IDs of events whose upload terminally failed. Used only by the
     * one-time HTTP-400 recovery: a failed row keeps its original
     * payload and idempotency UUID, so requeueing it can never
     * create a duplicate — the server dedupes on
     * UNIQUE(account_id, client_event_id).
     */
    @Query("SELECT clientEventId FROM call_events WHERE uploadState = :failedState")
    suspend fun failedEventIds(failedState: Int): List<String>

    /**
     * Return a failed event to the never-attempted state so the
     * regular worker (same idempotency key, same unique work name)
     * picks it up again. Only the recovery path uses this; normal
     * uploads move forward through PENDING → UPLOADED/FAILED.
     */
    @Query("UPDATE call_events SET uploadState = NULL WHERE clientEventId = :clientEventId")
    suspend fun clearState(clientEventId: String)

    @Query("SELECT COUNT(*) FROM call_events WHERE uploadState IS NULL OR uploadState = :pendingState")
    suspend fun pendingCount(pendingState: Int): Int
}
