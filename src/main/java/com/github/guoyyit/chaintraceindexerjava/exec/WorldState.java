package com.github.guoyyit.chaintraceindexerjava.exec;

import com.github.guoyyit.chaintraceindexerjava.model.ChangeType;
import com.github.guoyyit.chaintraceindexerjava.model.StateChange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory world state modified while replaying transactions.
 *
 * <p>Every successful mutation appends a journal entry (inverse) and an emitted
 * {@link StateChange}. Checkpoints frame execution: a frame rollback restores both
 * account/storage values and the emitted change list, so reverted sub-calls leave
 * no trace in the produced index.
 */
public final class WorldState {

    private final Map<String, Long> balances = new HashMap<>();
    private final Map<String, Long> nonces = new HashMap<>();
    private final Map<String, Map<String, String>> storage = new HashMap<>();
    private final List<JournalEntry> journal = new ArrayList<>();
    private final List<StateChange> emitted = new ArrayList<>();

    public static final class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException(String message) {
            super(message);
        }
    }

    private record JournalEntry(String address, ChangeType type, String key,
                                String oldValue, long emittedCountBefore) {
    }

    public int checkpoint() {
        return journal.size();
    }

    public void rollback(int checkpoint) {
        for (int i = journal.size() - 1; i >= checkpoint; i--) {
            JournalEntry entry = journal.get(i);
            applyInverse(entry);
        }
        journal.subList(checkpoint, journal.size()).clear();
        int restoreSize = checkpoint;
        emitted.subList(restoreSize, emitted.size()).clear();
    }

    private void applyInverse(JournalEntry entry) {
        switch (entry.type()) {
            case BALANCE -> balances.put(entry.address(), parseLongOrZero(entry.oldValue()));
            case NONCE -> nonces.put(entry.address(), parseLongOrZero(entry.oldValue()));
            case STORAGE -> {
                Map<String, String> accountStorage = storage.get(entry.address());
                if (accountStorage != null) {
                    if (entry.oldValue() == null) {
                        accountStorage.remove(entry.key());
                        if (accountStorage.isEmpty()) {
                            storage.remove(entry.address());
                        }
                    } else {
                        accountStorage.put(entry.key(), entry.oldValue());
                    }
                }
            }
        }
    }

    public void discard(int checkpoint) {
        if (checkpoint < journal.size()) {
            journal.subList(checkpoint, journal.size()).clear();
        }
    }

    public void resetJournal() {
        journal.clear();
    }

    public void reset() {
        balances.clear();
        nonces.clear();
        storage.clear();
        journal.clear();
        emitted.clear();
    }

    private static long parseLongOrZero(String value) {
        return value == null ? 0L : Long.parseLong(value);
    }

    public long getBalance(String address) {
        return balances.getOrDefault(address, 0L);
    }

    public void putBalance(String address, long value) {
        balances.put(address, value);
    }

    public long getNonce(String address) {
        return nonces.getOrDefault(address, 0L);
    }

    public void putNonce(String address, long value) {
        nonces.put(address, value);
    }

    public String getStorage(String address, String key) {
        Map<String, String> accountStorage = storage.get(address);
        return accountStorage == null ? null : accountStorage.get(key);
    }

    public void putStorage(String address, String key, String value) {
        if (value != null) {
            storage.computeIfAbsent(address, ignored -> new HashMap<>()).put(key, value);
        }
    }

    public List<StateChange> drainChanges() {
        List<StateChange> result = List.copyOf(emitted);
        emitted.clear();
        return result;
    }

    public void transfer(String from, String to, long value, int depth) {
        if (value < 0) {
            throw new IllegalArgumentException("transfer value must be non-negative");
        }
        long fromBalance = getBalance(from);
        if (fromBalance < value) {
            throw new InsufficientFundsException(
                    "address " + from + " has balance " + fromBalance + " < " + value);
        }
        long toBalance = getBalance(to);
        writeBalance(from, fromBalance - value, depth);
        writeBalance(to, toBalance + value, depth);
    }

    private void writeBalance(String address, long newValue, int depth) {
        long oldValue = getBalance(address);
        journal.add(new JournalEntry(address, ChangeType.BALANCE, null,
                Long.toString(oldValue), emitted.size()));
        balances.put(address, newValue);
        emit(address, ChangeType.BALANCE, null,
                Long.toString(oldValue), Long.toString(newValue), depth);
    }

    public void incrementNonce(String address, int depth) {
        long oldValue = getNonce(address);
        long newValue = oldValue + 1;
        journal.add(new JournalEntry(address, ChangeType.NONCE, null,
                Long.toString(oldValue), emitted.size()));
        nonces.put(address, newValue);
        emit(address, ChangeType.NONCE, null,
                Long.toString(oldValue), Long.toString(newValue), depth);
    }

    public void setStorage(String address, String key, String value, int depth) {
        String oldValue = getStorage(address, key);
        if (value.equals(oldValue)) {
            return;
        }
        journal.add(new JournalEntry(address, ChangeType.STORAGE, key, oldValue, emitted.size()));
        storage.computeIfAbsent(address, ignored -> new HashMap<>()).put(key, value);
        emit(address, ChangeType.STORAGE, key, oldValue, value, depth);
    }

    public void deleteStorage(String address, String key, int depth) {
        String oldValue = getStorage(address, key);
        if (oldValue == null) {
            return;
        }
        journal.add(new JournalEntry(address, ChangeType.STORAGE, key, oldValue, emitted.size()));
        Map<String, String> accountStorage = storage.get(address);
        accountStorage.remove(key);
        if (accountStorage.isEmpty()) {
            storage.remove(address);
        }
        emit(address, ChangeType.STORAGE, key, oldValue, null, depth);
    }

    public int changeCount() {
        return emitted.size();
    }

    public void recordChange(String address, ChangeType type, String key,
                            String oldValue, String newValue, int depth) {
        emit(address, type, key, oldValue, newValue, depth);
    }

    private void emit(String address, ChangeType type, String key,
                      String oldValue, String newValue, int depth) {
        emitted.add(new StateChange(address, type, key, oldValue, newValue, depth));
    }
}
