package com.wallet.repository;

import com.wallet.config.DomainMetrics;
import com.wallet.entity.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public class WalletRepository {

    private static final Logger log = LoggerFactory.getLogger(WalletRepository.class);

    public static final UUID TREASURY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final long OPENING_BALANCE_PAISE = 100_000L;

    private static final RowMapper<Wallet> MAPPER = (rs, rowNum) ->
            new Wallet(rs.getObject("id", UUID.class),
                    rs.getString("user_id"),
                    rs.getLong("balance_paise"));

    private final JdbcClient db;
    private final DomainMetrics metrics;

    WalletRepository(JdbcClient db, DomainMetrics metrics) {
        this.db = db;
        this.metrics = metrics;
    }

    // Concurrent callers for the same user_id must end up with one wallet. The
    // insert is attempted unconditionally and the unique index on user_id
    // decides the winner; losers return no row and read the committed one
    // instead. A conflicting insert that is still uncommitted makes ON CONFLICT
    // wait for that transaction, so the follow-up select cannot miss it.
    //
    // The loop only matters in the rare case where the transaction we lost to
    // rolled back between our insert and our select, leaving nothing to read.
    @Transactional
    public Wallet getOrCreate(String userId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<UUID> created = db.sql("""
                            INSERT INTO wallets (user_id) VALUES (:userId)
                            ON CONFLICT (user_id) DO NOTHING
                            RETURNING id
                            """)
                    .param("userId", userId)
                    .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                    .optional();
            if (created.isPresent()) {
                fundFromTreasury(created.get());
                log.atInfo().setMessage("wallet created")
                        .addKeyValue("event", "wallet_created")
                        .addKeyValue("wallet_id", created.get())
                        .addKeyValue("user_id", userId)
                        .addKeyValue("opening_balance_paise", OPENING_BALANCE_PAISE)
                        .log();
                metrics.walletCreated();
                return new Wallet(created.get(), userId, OPENING_BALANCE_PAISE);
            }

            Optional<Wallet> existing = findByUserId(userId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        throw new IllegalStateException("Could not get or create wallet for user " + userId);
    }

    // Runs in the caller's transaction, so a wallet is never visible without
    // its opening balance, and the treasury is never debited without a wallet
    // to receive it.
    private void fundFromTreasury(UUID walletId) {
        int debited = db.sql("""
                        UPDATE wallets SET balance_paise = balance_paise - :amount
                         WHERE id = :treasury AND balance_paise >= :amount
                        """)
                .param("amount", OPENING_BALANCE_PAISE)
                .param("treasury", TREASURY_ID)
                .update();
        if (debited == 0) {
            throw new IllegalStateException("Treasury cannot cover an opening balance");
        }

        db.sql("UPDATE wallets SET balance_paise = balance_paise + :amount WHERE id = :id")
                .param("amount", OPENING_BALANCE_PAISE)
                .param("id", walletId)
                .update();

        db.sql("""
                        INSERT INTO ledger_entries (wallet_id, delta_paise)
                        VALUES (:treasury, :debit), (:wallet, :credit)
                        """)
                .param("treasury", TREASURY_ID)
                .param("debit", -OPENING_BALANCE_PAISE)
                .param("wallet", walletId)
                .param("credit", OPENING_BALANCE_PAISE)
                .update();
    }

    public Optional<Wallet> findById(UUID id) {
        return db.sql("SELECT id, user_id, balance_paise FROM wallets WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    Optional<Wallet> findByUserId(String userId) {
        return db.sql("SELECT id, user_id, balance_paise FROM wallets WHERE user_id = :userId")
                .param("userId", userId)
                .query(MAPPER)
                .optional();
    }
}
