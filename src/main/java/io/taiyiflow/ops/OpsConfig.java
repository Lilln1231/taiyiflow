package io.taiyiflow.ops;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 运维智能体的全部配置都来自环境变量，便于放进 systemd / docker / k8s 而不改代码。
 */
public final class OpsConfig {

    public enum PermissionMode {
        /** 只注册 5 个只读文件/搜索工具，所有 shell 与写工具都不可用 */
        READONLY,
        /** 注册 shell 但仅允许白名单首 token，禁止写工具 */
        WHITELIST,
        /** shell 调用进入本地人工审批（或 -y 自动批准） */
        CONFIRM
    }

    /** 保守的只读命令白名单：首 token 精确匹配，无系统 shell 解释 */
    public static final List<String> DEFAULT_READONLY_COMMANDS = List.of(
            "ls", "cat", "date", "whoami", "pwd", "uname",
            "df", "free", "ps", "uptime", "head", "tail", "id", "hostname");

    public final String baseUrl;
    public final String apiKey;
    public final String flowId;
    public final Path sandboxRoot;
    public final List<String> allowedCommands;
    public final PermissionMode permissionMode;
    public final Duration timeout;
    public final boolean autoApprove;
    public final Path auditDir;
    public final Path reportDir;

    private OpsConfig(String baseUrl, String apiKey, String flowId, Path sandboxRoot,
                      List<String> allowedCommands, PermissionMode permissionMode,
                      Duration timeout, boolean autoApprove, Path auditDir, Path reportDir) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.flowId = flowId;
        this.sandboxRoot = sandboxRoot;
        this.allowedCommands = allowedCommands;
        this.permissionMode = permissionMode;
        this.timeout = timeout;
        this.autoApprove = autoApprove;
        this.auditDir = auditDir;
        this.reportDir = reportDir;
    }

    public static OpsConfig fromEnv() {
        String baseUrl = required("TAIYIFLOW_BASE_URL");
        String apiKey = required("TAIYIFLOW_API_KEY");
        String flowId = optional("TAIYIFLOW_FLOW_ID");

        Path sandbox = Path.of(optionalOrDefault("OPS_SANDBOX_ROOT", ".")).toAbsolutePath().normalize();
        Path audit = Path.of(optionalOrDefault("OPS_AUDIT_DIR", "audit")).toAbsolutePath().normalize();
        Path report = Path.of(optionalOrDefault("OPS_REPORT_DIR", "reports")).toAbsolutePath().normalize();

        String commands = optional("OPS_ALLOWED_COMMANDS");
        List<String> allowed = (commands == null || commands.isBlank())
                ? DEFAULT_READONLY_COMMANDS
                : Arrays.stream(commands.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();

        PermissionMode mode;
        try {
            mode = PermissionMode.valueOf(
                    optionalOrDefault("OPS_PERMISSION_MODE", "READONLY").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("OPS_PERMISSION_MODE 只能是 READONLY / WHITELIST / CONFIRM");
        }

        int seconds;
        try {
            seconds = Integer.parseInt(optionalOrDefault("OPS_TIMEOUT_SECONDS", "180"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("OPS_TIMEOUT_SECONDS 必须是整数秒");
        }
        if (seconds < 10 || seconds > 6000) {
            throw new IllegalArgumentException("OPS_TIMEOUT_SECONDS 必须在 10~6000 之间");
        }

        boolean auto = Boolean.parseBoolean(optionalOrDefault("OPS_AUTO_APPROVE", "false"));
        return new OpsConfig(baseUrl, apiKey, flowId, sandbox, allowed, mode,
                Duration.ofSeconds(seconds), auto, audit, report);
    }

    /** 仅用于 help / check 等不需要连后端的命令 */
    public static OpsConfig previewOrNull() {
        if (System.getenv("TAIYIFLOW_BASE_URL") == null || System.getenv("TAIYIFLOW_API_KEY") == null) {
            return null;
        }
        return fromEnv();
    }

    public String describe() {
        return """
                后台地址      : %s
                智能体 Flow   : %s
                权限模式      : %s
                沙盒根目录    : %s
                Shell 白名单  : %s
                单轮超时      : %ss
                自动审批      : %s
                审计目录      : %s
                报告目录      : %s
                """.formatted(baseUrl,
                flowId == null ? "(服务端默认 Flow)" : flowId,
                permissionMode,
                sandboxRoot,
                allowedCommands,
                timeout.toSeconds(),
                autoApprove,
                auditDir,
                reportDir);
    }

    private static String required(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("缺少环境变量 " + name + "，参见 config/ops.env.example");
        }
        return v;
    }

    private static String optional(String name) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? null : v;
    }

    private static String optionalOrDefault(String name, String fallback) {
        String v = optional(name);
        return v == null ? fallback : v;
    }
}
