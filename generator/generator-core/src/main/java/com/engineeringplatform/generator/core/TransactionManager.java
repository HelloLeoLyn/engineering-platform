package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.GenerationTransaction;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Transaction Manager（V0.7 §21：增量修改使用 GenerationTransaction；
 * EP-WORK-005/006 指令 §十一）。
 *
 * 使用 .generator/transactions/{transactionId}/ 建立最小 Transaction V1。
 * 记录 transaction id / plan id / state / operations / timestamps / result。
 * Executor 是执行层，允许记录执行时间。
 */
public final class TransactionManager {

    private final WorkspacePort port;

    public TransactionManager() {
        this(new WorkspacePort.Default());
    }

    public TransactionManager(WorkspacePort port) {
        this.port = port;
    }

    public Path transactionDir(Path root, String transactionId) {
        return port.resolve(root, ".generator/transactions/" + transactionId);
    }

    /** 创建 transaction（state=STAGED），落盘 transaction.json。 */
    public GenerationTransaction begin(Path root, String transactionId, String planId,
                                       List<String> operations) throws IOException {
        Path dir = transactionDir(root, transactionId);
        port.createDirectories(dir);
        GenerationTransaction tx = new GenerationTransaction(
                transactionId, planId, GenerationTransaction.TransactionState.STAGED,
                operations, Map.of("beganAt", String.valueOf(System.currentTimeMillis())), null);
        writeJson(root, tx);
        return tx;
    }

    public void updateState(Path root, GenerationTransaction tx,
                            GenerationTransaction.TransactionState state, String result) throws IOException {
        GenerationTransaction updated = new GenerationTransaction(
                tx.transactionId(), tx.planId(), state, tx.operations(),
                withTimestamp(tx.timestamps(), state), result);
        writeJson(root, updated);
    }

    public GenerationTransaction read(Path root, String transactionId) throws IOException {
        Path file = port.resolve(transactionDir(root, transactionId), "transaction.json");
        if (!port.exists(file)) {
            throw new IOException("transaction not found: " + transactionId);
        }
        String json = port.readString(file);
        String id = extract(json, "transactionId");
        String planId = extract(json, "planId");
        String stateRaw = extract(json, "state");
        String result = extract(json, "result");
        GenerationTransaction.TransactionState state;
        try {
            state = GenerationTransaction.TransactionState.valueOf(stateRaw);
        } catch (IllegalArgumentException e) {
            state = GenerationTransaction.TransactionState.FAILED;
        }
        return new GenerationTransaction(id, planId, state, List.of(),
                Map.of("restoredAt", String.valueOf(System.currentTimeMillis())), result);
    }

    private static Map<String, Object> withTimestamp(Map<String, Object> timestamps,
                                                     GenerationTransaction.TransactionState state) {
        Map<String, Object> copy = new java.util.HashMap<>(timestamps);
        copy.put(state.name().toLowerCase() + "At", String.valueOf(System.currentTimeMillis()));
        return copy;
    }

    private void writeJson(Path root, GenerationTransaction tx) throws IOException {
        String json = "{\"transactionId\":\"" + tx.transactionId()
                + "\",\"planId\":\"" + tx.planId()
                + "\",\"state\":\"" + tx.state().name()
                + "\",\"operations\":" + (tx.operations() == null ? "[]" : "[" + String.join(",", tx.operations().stream()
                        .map(o -> "\"" + o + "\"").toList()) + "]")
                + ",\"result\":" + (tx.result() == null ? "null" : "\"" + tx.result() + "\"")
                + "}";
        port.writeString(port.resolve(transactionDir(root, tx.transactionId()), "transaction.json"), json);
    }

    private static String extract(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) {
            return "";
        }
        int colon = json.indexOf(':', idx);
        if (colon < 0) {
            return "";
        }
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        if (start < json.length() && json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return end < 0 ? "" : json.substring(start + 1, end);
        }
        int end = json.indexOf(',', start);
        if (end < 0) {
            end = json.indexOf('}', start);
        }
        return end < 0 ? json.substring(start) : json.substring(start, end);
    }
}
