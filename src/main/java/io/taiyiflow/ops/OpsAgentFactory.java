package io.taiyiflow.ops;

import io.taiyiflow.sdk.RecoveryPolicy;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.hooks.PermissionPolicy;
import io.taiyiflow.sdk.hooks.SessionHook;
import io.taiyiflow.sdk.hooks.ToolExecutionHook;
import io.taiyiflow.sdk.plugins.BuiltinPlugins;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** 按运维安全策略组装 TaiyiAgent：插件注册、权限钩子、审计钩子都收口在这里。 */
public final class OpsAgentFactory {

    /** 明确放行的只读工具；不在集合内的一律拒绝 */
    private static final Set<String> SAFE_READ_TOOLS = Set.of(
            "read_file", "list_files", "glob_files", "search", "grep",
            "process_list", "process_output");

    private static final Set<String> SHELL_TOOLS = Set.of(
            "shell", "bash", "execute_command", "shell_exec");

    private OpsAgentFactory() {
    }

    public static TaiyiAgent create(OpsConfig cfg, AuditLogger audit) {
        TaiyiAgent.Builder builder = TaiyiAgent.builder()
                .baseUrl(cfg.baseUrl)
                .apiKey(cfg.apiKey)
                .permissionTimeout(cfg.timeout)
                .recoveryPolicy(RecoveryPolicy.AUTO)
                .onLifecycle(new SessionHook() {
                    @Override
                    public void onStart(String sessionId) {
                        audit.lifecycle(sessionId, "start", null);
                    }

                    @Override
                    public void onFinish(String sessionId) {
                        audit.lifecycle(sessionId, "finish", null);
                    }

                    @Override
                    public void onInterrupt(String sessionId, String reason) {
                        audit.lifecycle(sessionId, "interrupt", reason);
                    }

                    @Override
                    public void onClose(String sessionId) {
                        audit.lifecycle(sessionId, "close", null);
                    }
                });

        if (cfg.flowId == null || cfg.flowId.isBlank()) {
            builder.useServerDefaultFlow();
        } else {
            builder.flowId(cfg.flowId);
        }

        Map<String, Long> started = new ConcurrentHashMap<>();
        builder.onToolExecution(new ToolExecutionHook() {
            @Override
            public ToolCall before(String sessionId, ToolCall toolCall) {
                started.put(key(sessionId, toolCall), System.currentTimeMillis());
                audit.toolCall(sessionId, toolCall.getName(), args(toolCall), "before", null, null);
                return toolCall;
            }

            @Override
            public ToolResult after(String sessionId, ToolCall toolCall, ToolResult result) {
                Long t0 = started.remove(key(sessionId, toolCall));
                long elapsed = t0 == null ? -1 : System.currentTimeMillis() - t0;
                String outcome = result != null && result.isSuccess() ? "success" : "failed";
                audit.toolCall(sessionId, toolCall.getName(), args(toolCall), "after", elapsed, outcome);
                return result;
            }
        });

        switch (cfg.permissionMode) {
            case READONLY -> {
                // 内置策略：只读放行，写操作 DENY。插件也只注册 5 个只读工具。
                builder.permissionPolicy(PermissionPolicy.READONLY);
            }
            case WHITELIST, CONFIRM -> {
                // 用 permissionHook 覆盖默认 policy：默认 policy 会把 shell 判为 mutating 并直接 DENY。
                // 多个 PermissionHook 是"与"关系，所以这里必须替换而不是追加。
                PermissionHook hook = (sessionId, toolCall) -> {
                    String name = toolCall.getName();
                    PermissionHook.Decision decision;
                    if (SAFE_READ_TOOLS.contains(name)) {
                        decision = PermissionHook.Decision.ALLOW;
                    } else if (SHELL_TOOLS.contains(name)) {
                        decision = cfg.permissionMode == OpsConfig.PermissionMode.CONFIRM
                                ? PermissionHook.Decision.ASK
                                : PermissionHook.Decision.ALLOW;
                    } else {
                        // write_file / file_edit / schedule_* / skill_* / run_subtask 等一律拒绝
                        decision = PermissionHook.Decision.DENY;
                    }
                    return CompletableFuture.completedFuture(decision);
                };
                builder.permissionHook(hook);
            }
        }

        TaiyiAgent agent = builder.build();

        // TaiyiAgent 默认不注册任何本地工具，按模式显式注册。
        if (cfg.permissionMode == OpsConfig.PermissionMode.READONLY) {
            BuiltinPlugins.registerReadOnly(agent.getPluginManager(), cfg.sandboxRoot);
        } else {
            BuiltinPlugins.registerAll(agent.getPluginManager(), cfg.sandboxRoot, cfg.allowedCommands);
        }
        return agent;
    }

    private static String key(String sessionId, ToolCall call) {
        return sessionId + "|" + call.getId() + "|" + call.getName();
    }

    private static String args(ToolCall call) {
        return call.getArguments() == null ? "" : call.getArguments().toString();
    }
}
