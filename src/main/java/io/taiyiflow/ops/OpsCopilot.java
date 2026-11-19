package io.taiyiflow.ops;

import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.TaiyiAgent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 太乙智启运维智能体 CLI。
 *
 * 命令：
 *   check              只校验配置，不连后端
 *   ask "&lt;问题&gt;"    单次提问，流式输出
 *   patrol             固定巡检清单，生成 Markdown 报告
 *   repl               多轮交互
 */
public final class OpsCopilot {

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        String command = args[0];
        try {
            switch (command) {
                case "help", "--help", "-h" -> usage();
                case "check" -> check();
                case "ask" -> {
                    if (args.length < 2) {
                        System.err.println("ask 需要提供问题文本");
                        System.exit(2);
                    }
                    ask(join(args, 1));
                }
                case "patrol" -> patrol();
                case "repl" -> repl();
                default -> {
                    System.err.println("未知命令: " + command);
                    usage();
                    System.exit(2);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("配置错误: " + e.getMessage());
            System.exit(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("已被中断");
        } catch (Exception e) {
            System.err.println("执行失败: " + e);
            String message = String.valueOf(e.getMessage());
            if (message.contains("first-frame") || message.contains("401")) {
                System.err.println("提示：后台拒绝了请求。请确认 TAIYIFLOW_API_KEY 有效——"
                        + "本机 CLI 当前为未登录状态，可先运行 downloads/taiyiflow-cli-windows-amd64.exe login 重新登录。");
            }
            System.exit(1);
        }
    }

    private static void check() {
        OpsConfig cfg = OpsConfig.previewOrNull();
        if (cfg == null) {
            System.out.println("""
                    当前未设置 TAIYIFLOW_BASE_URL / TAIYIFLOW_API_KEY，仅显示需要配置的环境变量：
                      TAIYIFLOW_BASE_URL    必填，例如 https://snz1.cn/taiyi/composer
                      TAIYIFLOW_API_KEY     必填，Bearer 令牌
                      TAIYIFLOW_FLOW_ID     可选，缺省使用服务端默认 Flow
                      OPS_PERMISSION_MODE   READONLY(默认) / WHITELIST / CONFIRM
                      OPS_ALLOWED_COMMANDS  逗号分隔的 shell 首 token 白名单
                      OPS_SANDBOX_ROOT      可读文件根目录，默认当前目录
                      OPS_TIMEOUT_SECONDS   单轮超时，默认 180
                      OPS_AUTO_APPROVE      CONFIRM 模式下是否自动批准，默认 false
                      OPS_AUDIT_DIR         审计日志目录，默认 ./audit
                      OPS_REPORT_DIR        巡检报告目录，默认 ./reports
                    """);
            return;
        }
        System.out.println("配置校验通过（未连接后端）：");
        System.out.println(cfg.describe());
    }

    private static void ask(String question) throws Exception {
        OpsConfig cfg = OpsConfig.fromEnv();
        AuditLogger audit = new AuditLogger(cfg.auditDir);
        audit.command("ask-start", question);
        LocalApproval approval = new LocalApproval(cfg.autoApprove, audit);
        TaiyiAgent agent = OpsAgentFactory.create(cfg, audit);
        try {
            AgentSession session = agent.newSession();
            ChatRunner chat = new ChatRunner(session, true, approval).attach();
            chat.ask(question, cfg.timeout);
        } finally {
            approval.close();
            agent.shutdown();
        }
    }

    private static void patrol() throws Exception {
        OpsConfig cfg = OpsConfig.fromEnv();
        AuditLogger audit = new AuditLogger(cfg.auditDir);
        audit.command("patrol-start", null);
        LocalApproval approval = new LocalApproval(cfg.autoApprove, audit);
        TaiyiAgent agent = OpsAgentFactory.create(cfg, audit);
        try {
            AgentSession session = agent.newSession();
            ChatRunner chat = new ChatRunner(session, true, approval).attach();
            Path report = PatrolRunner.run(chat, cfg, audit);
            System.out.println("\n[巡检] 报告已生成: " + report);
        } finally {
            approval.close();
            agent.shutdown();
        }
    }

    private static void repl() throws Exception {
        OpsConfig cfg = OpsConfig.fromEnv();
        AuditLogger audit = new AuditLogger(cfg.auditDir);
        LocalApproval approval = new LocalApproval(cfg.autoApprove, audit);
        TaiyiAgent agent = OpsAgentFactory.create(cfg, audit);
        System.out.println("进入多轮会话，输入 exit / quit 退出。审计日志: " + audit.file());
        try {
            AgentSession session = agent.newSession();
            ChatRunner chat = new ChatRunner(session, true, approval).attach();
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String input = line.trim();
                if (input.isEmpty()) {
                    continue;
                }
                if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
                    break;
                }
                audit.command("repl-input", input);
                chat.ask(input, cfg.timeout);
            }
        } finally {
            approval.close();
            agent.shutdown();
        }
    }

    private static String join(String[] args, int from) {
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    private static void usage() {
        System.out.println("""
                太乙智启运维智能体（基于 taiyiflow-javasdk）

                用法:
                  ops-copilot check              校验环境变量，不连接后端
                  ops-copilot ask "<问题>"       单次提问，流式输出
                  ops-copilot patrol             执行基础巡检并生成 Markdown 报告
                  ops-copilot repl               多轮交互会话

                运行前请先设置 TAIYIFLOW_BASE_URL 与 TAIYIFLOW_API_KEY，
                详见 config/ops.env.example 与 README.md。
                """);
    }
}
