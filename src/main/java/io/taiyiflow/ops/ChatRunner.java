package io.taiyiflow.ops;

import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.hooks.StreamHook;

import java.time.Duration;

/** 把一次或多次 chat 的流式输出收集成可落盘的会话记录。 */
public final class ChatRunner {

    private final AgentSession session;
    private final boolean echo;
    private final LocalApproval approval;
    private final StringBuilder transcript = new StringBuilder();

    public ChatRunner(AgentSession session, boolean echo, LocalApproval approval) {
        this.session = session;
        this.echo = echo;
        this.approval = approval;
    }

    public ChatRunner attach() {
        approval.attach(session);
        session.onStream(new StreamHook() {
            @Override
            public void onChunk(String sid, String chunk) {
                transcript.append(chunk);
                if (echo) {
                    System.out.print(chunk);
                    System.out.flush();
                }
            }

            @Override
            public void onEvent(String sid, io.taiyiflow.sdk.transport.envelope.StreamEvent event) {
                approval.onEvent(event);
            }

            @Override
            public void onError(String sid, Throwable error) {
                String line = "\n[错误] " + error.getMessage() + "\n";
                transcript.append(line);
                System.err.print(line);
            }

            @Override
            public void onClose(String sid, String reason) {
                transcript.append("\n");
                if (echo) {
                    System.out.println("\n--- 流结束: " + reason + " ---");
                }
            }
        });
        return this;
    }

    public void ask(String prompt, Duration timeout) throws InterruptedException {
        transcript.append("\n### 运维请求\n\n").append(prompt).append("\n\n### 智能体回复\n\n");
        if (echo) {
            System.out.println("\n[提问] " + prompt);
        }
        session.chat(prompt);
        if (!session.await(timeout)) {
            throw new IllegalStateException("等待智能体回复超时（" + timeout.toSeconds() + "s）");
        }
    }

    public String transcript() {
        return transcript.toString();
    }

    public AgentSession session() {
        return session;
    }
}
