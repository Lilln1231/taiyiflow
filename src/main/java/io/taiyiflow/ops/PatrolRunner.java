package io.taiyiflow.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 基础巡检：固定检查清单 + 让智能体汇总成一份 Markdown 报告。 */
public final class PatrolRunner {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final String[] CHECKS = {
            "请检查系统负载与 CPU：给出 uptime 和占用最高的 5 个进程，判断是否异常。",
            "请检查内存与磁盘：给出关键指标，并列出使用率超过 80% 的挂载点。",
            "请检查关键服务状态，列出失败或未运行的服务单元；如果当前环境不适用请说明。",
            "请在沙盒目录内检索最近日志中的 ERROR / FATAL，给出最多 20 条摘要。",
            "请把以上结果汇总成一份中文运维巡检报告：按【正常 / 关注 / 严重】分级，并给出可执行的处置建议。"
    };

    private PatrolRunner() {
    }

    public static Path run(ChatRunner chat, OpsConfig cfg, AuditLogger audit) throws InterruptedException, IOException {
        for (int i = 0; i < CHECKS.length; i++) {
            System.out.println("[巡检 " + (i + 1) + "/" + CHECKS.length + "] " + CHECKS[i]);
            chat.ask(CHECKS[i], cfg.timeout);
        }

        Files.createDirectories(cfg.reportDir);
        String name = "patrol-" + LocalDateTime.now().format(STAMP) + ".md";
        Path report = cfg.reportDir.resolve(name);
        String content = "# 运维巡检报告\n\n"
                + "- 生成时间：" + LocalDateTime.now() + "\n"
                + "- 权限模式：" + cfg.permissionMode + "\n"
                + "- 沙盒根目录：" + cfg.sandboxRoot + "\n"
                + "- 审计日志：" + audit.file() + "\n\n"
                + "---\n"
                + chat.transcript();
        Files.writeString(report, content, StandardCharsets.UTF_8);
        audit.command("patrol", report.toString());
        return report;
    }
}
