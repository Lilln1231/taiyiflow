package io.taiyiflow.ops;

import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.transport.envelope.StreamEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * CONFIRM 模式下的本地审批通道。
 * SDK 的 PermissionHook 返回 ASK 后，工具调用会等待 session.approve/deny；
 * 这里从流事件里拿到 toolCallId，在终端上请运维人员裁决。
 */
public final class LocalApproval implements AutoCloseable {

    private final LinkedBlockingQueue<String> pending = new LinkedBlockingQueue<>();
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final boolean autoApprove;
    private final AuditLogger audit;
    private final Thread worker;
    private volatile AgentSession session;
    private volatile boolean running = true;

    public LocalApproval(boolean autoApprove, AuditLogger audit) {
        this.autoApprove = autoApprove;
        this.audit = audit;
        this.worker = Thread.ofVirtual().name("ops-approval").start(this::loop);
    }

    public void attach(AgentSession session) {
        this.session = session;
    }

    /** 挂到 StreamHook.onEvent 上，捕获需要审批的 client_tool 调用 ID */
    public void onEvent(StreamEvent event) {
        if (event == null || !event.isClientTool()) {
            return;
        }
        try {
            String id = event.getData().required("function").required("id").asText();
            if (id != null && !id.isBlank() && seen.add(id)) {
                pending.offer(id);
            }
        } catch (Exception ignored) {
            // 事件结构不满足时跳过；审批超时由 SDK 自身兜底
        }
    }

    private void loop() {
        while (running) {
            try {
                String id = pending.poll(200, TimeUnit.MILLISECONDS);
                if (id == null || session == null) {
                    continue;
                }
                System.out.println();
                System.out.println("[审批] 工具调用 " + id + " 请求执行");
                String decision = autoApprove ? "approve" : readDecision();
                submit(id, decision);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                System.err.println("[审批] 处理异常: " + e.getMessage());
            }
        }
    }

    private String readDecision() throws IOException {
        System.out.print("[审批] 输入 approve / deny（回车或非法输入按 deny 处理）: ");
        System.out.flush();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line = reader.readLine();
        return line == null ? "deny" : line.trim();
    }

    private void submit(String id, String decision) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean accepted = false;
        while (System.nanoTime() < deadline) {
            accepted = "approve".equalsIgnoreCase(decision)
                    ? session.approve(id)
                    : session.deny(id, "本地运维拒绝");
            if (accepted) {
                break;
            }
            Thread.sleep(10);
        }
        String result = accepted ? decision : "failed(no-pending-call)";
        System.out.println("[审批] " + id + " -> " + result);
        audit.command("approval", id + " -> " + result);
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
    }
}
