package com.github.guoyyit.chaintraceindexerjava.execution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mutable world state during replay.
 *
 * <p>Mutating operations are journaled. {@link #snapshot()} marks a point in
 * the journal and {@link #rollback(int)} reverses exactly the mutations after
 * it, which is what gives nested CALL/DEPLOY failure the "only the failed
 * frame is undone" semantics; a root frame failure rolls back the whole
 * transaction.
 *
 * <p>The executed transaction hash set is intentionally <b>not</b> journaled:
 * inclusion of a transaction in the canonical chain is an immutable fact, so
 * duplicate hash detection never changes within a replay. It is reconstructed
 * naturally whenever the canonical chain is replayed from genesis.
 */
public class AccountState {

    public static final String FIELD_BALANCE = "balance";
    public static final String FIELD_NONCE = "nonce";
    public static final String FIELD_CODE = "code";
    public static final String FIELD_STORAGE = "storage";

    private final Map<String, Long> balances = new HashMap<>();
    private final Map<String, Long> nonces = new HashMap<>();
    private final Map<String, String> codes = new HashMap<>();
    private final Map<String, Map<String, String>> storage = new HashMap<>();
    private final Set<String> executedTxHashes = new HashSet<>();
    private final List<Undo> journal = new ArrayList<>();

    public AccountState() {
    }

    /**
     * Deep copy of balances, nonces, code, storage and the executed hash set.
     * Used to retain a state at a fork ancestor before replaying a new branch.
     */
    public AccountState copy() {
        AccountState clone = new AccountState();
        clone.balances.putAll(this.balances);
        clone.nonces.putAll(this.nonces);
        clone.codes.putAll(this.codes);
        this.storage.forEach((address, accountStorage) ->
                clone.storage.put(address, new HashMap<>(accountStorage)));
        clone.executedTxHashes.addAll(this.executedTxHashes);
        return clone;
    }

    public static String normalizeAddress(String address) {
        return address == null ? "" : address.trim().toLowerCase();
    }

    public long getBalance(String address) {
        return balances.getOrDefault(normalizeAddress(address), 0L);
    }

    public long getNonce(String address) {
        return nonces.getOrDefault(normalizeAddress(address), 0L);
    }

    public String getCode(String address) {
        return codes.get(normalizeAddress(address));
    }

    public String getStorage(String address, String key) {
        if (key == null) {
            return null;
        }
        Map<String, String> accountStorage = storage.get(normalizeAddress(address));
        return accountStorage == null ? null : accountStorage.get(key.trim());
    }

    public void setBalance(String address, long newValue) {
        String key = normalizeAddress(address);
        Long old = balances.put(key, newValue);
        journal.add(new Undo(key, FIELD_BALANCE, null, old == null ? null : old.toString()));
    }

    public void setNonce(String address, long newValue) {
        String key = normalizeAddress(address);
        Long old = nonces.put(key, newValue);
        journal.add(new Undo(key, FIELD_NONCE, null, old == null ? null : old.toString()));
    }

    public void setCode(String address, String code) {
        String key = normalizeAddress(address);
        String old = codes.put(key, code == null ? "" : code);
        journal.add(new Undo(key, FIELD_CODE, null, old));
    }

    public void setStorage(String address, String storageKey, String value) {
        String key = normalizeAddress(address);
        Map<String, String> accountStorage = storage.computeIfAbsent(key, ignored -> new HashMap<>());
        String old = value == null ? accountStorage.remove(storageKey.trim())
                : accountStorage.put(storageKey.trim(), value);
        journal.add(new Undo(key, FIELD_STORAGE, storageKey.trim(), old));
    }

    /**
     * @return true if the hash was not seen before on this chain
     */
    public boolean markExecuted(String txHash) {
        if (txHash == null || txHash.isBlank()) {
            return false;
        }
        return executedTxHashes.add(txHash.trim().toLowerCase());
    }

    public boolean isExecuted(String txHash) {
        return txHash != null && executedTxHashes.contains(txHash.trim().toLowerCase());
    }

    /**
     * Records a journal position.
     */
    public int snapshot() {
        return journal.size();
    }

    /**
     * Reverses every mutation performed after {@code snapshotId}, in reverse.
     */
    public void rollback(int snapshotId) {
        for (int i = journal.size() - 1; i >= snapshotId; i--) {
            Undo undo = journal.get(i);
            switch (undo.field) {
                case FIELD_BALANCE -> restoreLong(balances, undo);
                case FIELD_NONCE -> restoreLong(nonces, undo);
                case FIELD_CODE -> restoreString(codes, undo);
                case FIELD_STORAGE -> {
                    Map<String, String> accountStorage = storage.get(undo.address);
                    if (accountStorage != null) {
                        if (undo.oldValue == null) {
                            accountStorage.remove(undo.storageKey);
                        } else {
                            accountStorage.put(undo.storageKey, undo.oldValue);
                        }
                    }
                }
                default -> throw new IllegalStateException("unknown field " + undo.field);
            }
        }
        journal.subList(snapshotId, journal.size()).clear();
    }

    private static void restoreLong(Map<String, Long> target, Undo undo) {
        if (undo.oldValue == null) {
            target.remove(undo.address);
        } else {
            target.put(undo.address, Long.parseLong(undo.oldValue));
        }
    }

    private static void restoreString(Map<String, String> target, Undo undo) {
        if (undo.oldValue == null) {
            target.remove(undo.address);
        } else {
            target.put(undo.address, undo.oldValue);
        }
    }

    private record Undo(String address, String field, String storageKey, String oldValue) {
    }
}
