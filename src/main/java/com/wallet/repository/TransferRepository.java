package com.wallet.repository;

import com.wallet.config.DomainMetrics;
import com.wallet.entity.Transfer;
import com.wallet.entity.TransferResult;
import com.wallet.entity.TransferStatus;
import com.wallet.exception.IdempotencyConflictException;
import com.wallet.exception.WalletNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public class TransferRepository {

    private static final Logger log = LoggerFactory.getLogger(TransferRepository.class);

    private static final RowMapper<Transfer> MAPPER = (rs, rowNum) ->
            new Transfer(rs.getObject("id", UUID.class),
                    rs.getString("idempotency_key"),
                    rs.getString("request_hash"),
                    rs.getObject("from_wallet_id", UUID.class),
                    rs.getObject("to_wallet_id", UUID.class),
                    rs.getLong("amount_paise"),
                    TransferStatus.valueOf(rs.getString("status")));

    private static final String COLUMNS =
            "id, idempotency_key, request_hash, from_wallet_id, to_wallet_id, amount_paise, status";

    private final JdbcClient db;
    private final DomainMetrics metrics;

    TransferRepository(JdbcClient db, DomainMetrics metrics) {
        this.db = db;
        this.metrics = metrics;
    }

    // One transaction covers the idempotency claim and the money movement, so a
    // duplicate can never land between the two. That ordering is the whole
    // exactly-once guarantee: the key is claimed first, and only the claimant
    // ever touches a balance.
    @Transactional
    public TransferResult execute(String idempotencyKey,
                                  String requestHash,
                                  UUID fromWalletId,
                                  UUID toWalletId,
                                  long amountPaise) {

        Optional<UUID> claimed = db.sql("""
                        INSERT INTO transfers
                            (idempotency_key, request_hash, from_wallet_id, to_wallet_id, amount_paise, status)
                        VALUES (:key, :hash, :from, :to, :amount, 'PENDING')
                        ON CONFLICT (idempotency_key) DO NOTHING
                        RETURNING id
                        """)
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .param("from", fromWalletId)
                .param("to", toWalletId)
                .param("amount", amountPaise)
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .optional();

        if (claimed.isEmpty()) {
            return replay(idempotencyKey, requestHash);
        }

        UUID transferId = claimed.get();
        log.atInfo().setMessage("transfer created")
                .addKeyValue("event", "transfer_created")
                .addKeyValue("transfer_id", transferId)
                .addKeyValue("from_wallet_id", fromWalletId)
                .addKeyValue("to_wallet_id", toWalletId)
                .addKeyValue("amount_paise", amountPaise)
                .log();
        metrics.transferCreated();

        lockBothWallets(fromWalletId, toWalletId);

        int debited = db.sql("""
                        UPDATE wallets SET balance_paise = balance_paise - :amount
                         WHERE id = :from AND balance_paise >= :amount
                        """)
                .param("amount", amountPaise)
                .param("from", fromWalletId)
                .update();

        // Zero rows means the balance was too low. Nothing partially applied,
        // because the debit is a single conditional statement rather than a
        // read followed by a write.
        if (debited == 0) {
            log.atWarn().setMessage("transfer declined for insufficient funds")
                    .addKeyValue("event", "transfer_declined")
                    .addKeyValue("transfer_id", transferId)
                    .addKeyValue("from_wallet_id", fromWalletId)
                    .addKeyValue("amount_paise", amountPaise)
                    .addKeyValue("reason", "insufficient_funds")
                    .log();
            metrics.transferDeclined();
            return new TransferResult(finish(transferId, TransferStatus.DECLINED_INSUFFICIENT_FUNDS), false);
        }

        db.sql("UPDATE wallets SET balance_paise = balance_paise + :amount WHERE id = :to")
                .param("amount", amountPaise)
                .param("to", toWalletId)
                .update();

        db.sql("""
                        INSERT INTO ledger_entries (transfer_id, wallet_id, delta_paise)
                        VALUES (:transfer, :from, :debit), (:transfer, :to, :credit)
                        """)
                .param("transfer", transferId)
                .param("from", fromWalletId)
                .param("debit", -amountPaise)
                .param("to", toWalletId)
                .param("credit", amountPaise)
                .update();

        log.atInfo().setMessage("transfer debited and credited")
                .addKeyValue("event", "transfer_completed")
                .addKeyValue("transfer_id", transferId)
                .addKeyValue("from_wallet_id", fromWalletId)
                .addKeyValue("to_wallet_id", toWalletId)
                .addKeyValue("amount_paise", amountPaise)
                .log();
        metrics.transferCompleted();

        return new TransferResult(finish(transferId, TransferStatus.COMPLETED), false);
    }

    // A -> B and B -> A arriving together would grab the same two rows in
    // opposite orders and deadlock. Taking the locks up front in a fixed order
    // removes the cycle. Any total order works as long as every transaction
    // uses the same one; UUID.compareTo is simply the one available here.
    private void lockBothWallets(UUID fromWalletId, UUID toWalletId) {
        UUID first = fromWalletId.compareTo(toWalletId) <= 0 ? fromWalletId : toWalletId;
        UUID second = first.equals(fromWalletId) ? toWalletId : fromWalletId;
        lockWallet(first);
        lockWallet(second);
    }

    // FOR NO KEY UPDATE, not FOR UPDATE. Inserting the transfers row above takes
    // a KEY SHARE lock on both referenced wallets to satisfy the foreign keys,
    // and it takes them in column order rather than sorted order. FOR UPDATE
    // conflicts with KEY SHARE, so two opposing transfers would each hold a
    // share lock the other needs to upgrade past - a deadlock that no amount of
    // ordering on our side can prevent. FOR NO KEY UPDATE is the lock an UPDATE
    // of a non-key column takes anyway, and it does not conflict with KEY SHARE.
    private void lockWallet(UUID walletId) {
        db.sql("SELECT 1 FROM wallets WHERE id = :id FOR NO KEY UPDATE")
                .param("id", walletId)
                .query((rs, rowNum) -> rs.getInt(1))
                .optional()
                .orElseThrow(() -> new WalletNotFoundException(walletId));
    }

    private TransferResult replay(String idempotencyKey, String requestHash) {
        Transfer existing = findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency key " + idempotencyKey + " conflicted but no transfer was found"));

        if (!existing.requestHash().equals(requestHash)) {
            log.atWarn().setMessage("idempotency key reused with a different body")
                    .addKeyValue("event", "idempotency_conflict")
                    .addKeyValue("idempotency_key", idempotencyKey)
                    .addKeyValue("transfer_id", existing.id())
                    .log();
            metrics.transferConflicted();
            throw new IdempotencyConflictException(idempotencyKey);
        }

        log.atInfo().setMessage("idempotent replay")
                .addKeyValue("event", "idempotent_replay")
                .addKeyValue("idempotency_key", idempotencyKey)
                .addKeyValue("transfer_id", existing.id())
                .addKeyValue("status", existing.status())
                .log();
        metrics.transferReplayed();
        return new TransferResult(existing, true);
    }

    private Transfer finish(UUID transferId, TransferStatus status) {
        return db.sql("UPDATE transfers SET status = :status WHERE id = :id RETURNING " + COLUMNS)
                .param("status", status.name())
                .param("id", transferId)
                .query(MAPPER)
                .single();
    }

    public Optional<Transfer> findById(UUID id) {
        return db.sql("SELECT " + COLUMNS + " FROM transfers WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    private Optional<Transfer> findByIdempotencyKey(String idempotencyKey) {
        return db.sql("SELECT " + COLUMNS + " FROM transfers WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(MAPPER)
                .optional();
    }
}