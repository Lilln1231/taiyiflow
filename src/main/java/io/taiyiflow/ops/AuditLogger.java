package io.taiyiflow.ops;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 追加写 JSON Lines 审计日志：运维场景要求每一次工具调用都可追溯。 */
public final class AuditLogger {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file;

    public AuditLogger(Path dir) throws IOException {
        Files.createDirectories(dir);
        this.file = dir.resolve("ops-audit.jsonl");
    }

    public void toolCall(String sessionId, String toolName, String arguments, String phase,
                         Long elapsedMs, String outcome) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ts", Instant.now().toString());
        row.put("event", "tool." + phase);
        row.put("session_id", sessionId);
        row.put("tool", toolName);
        row.put("arguments", arguments);
        if (elapsedMs != null) row.put("elapsed_ms", elapsedMs);
        if (outcome != null) row.put("outcome", outcome);
        append(row);
    }

    public void lifecycle(String sessionId, String event, String detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ts", Instant.now().toString());
        row.put("event", "session." + event);
        row.put("session_id", sessionId);
        if (detail != null) row.put("detail", detail);
        append(row);
    }

    public void command(String command, String detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ts", Instant.now().toString());
        row.put("event", "command");
        row.put("command", command);
        if (detail != null) row.put("detail", detail);
        append(row);
    }

    public Path file() {
        return file;
    }

    private synchronized void append(Map<String, Object> row) {
        try {
            String line = mapper.writeValueAsString(row) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("[audit] 写入失败: " + e.getMessage());
        }
    }
}
