package io.taiyiflow.ops;

import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.hooks.PermissionPolicy;
import io.taiyiflow.sdk.hooks.StreamHook;
import io.taiyiflow.sdk.plugins.BuiltinPlugins;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 最小演示：用不到 50 行看懂 SDK 的完整闭环。
 * 连后台 → 注册只读工具 → 发问题 → 流式收结果 → 关闭。
 *
 * 运行：
 *   java -cp target/taiyiflow-ops-copilot.jar io.taiyiflow.ops.QuickDemo
 */
public final class QuickDemo {

    public static void main(String[] args) {
        try {
            run();
        } catch (Exception e) {
            System.err.println("演示失败: " + e.getMessage());
            System.err.println("提示：先运行 set -a && source config/ops.env && set +a 加载配置；若提示 401，请重新登录 CLI 并更新 TAIYIFLOW_API_KEY。");
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        String baseUrl = env("TAIYIFLOW_BASE_URL");
        String apiKey = env("TAIYIFLOW_API_KEY");
        String flowId = System.getenv("TAIYIFLOW_FLOW_ID");

        TaiyiAgent.Builder builder = TaiyiAgent.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .permissionPolicy(PermissionPolicy.READONLY);   // 只读优先，写工具全拒

        if (flowId == null || flowId.isBlank()) {
            builder.useServerDefaultFlow();
        } else {
            builder.flowId(flowId);
        }

        TaiyiAgent agent = builder.build();
        try {
            // SDK 默认不注册任何本地工具，这里只给 5 个只读文件/搜索工具
            BuiltinPlugins.registerReadOnly(agent.getPluginManager(), Path.of("."));

            AgentSession session = agent.newSession();
            session.onStream(new StreamHook() {
                @Override
                public void onChunk(String sessionId, String chunk) {
                    System.out.print(chunk);
                    System.out.flush();
                }
            });

            session.chat("用三句话说明：你能帮我做哪些 Linux 只读巡检工作？");
            if (!session.await(Duration.ofMinutes(3))) {
                throw new IllegalStateException("等待回复超时");
            }
        } finally {
            agent.shutdown();
        }
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少环境变量 " + name);
        }
        return value;
    }
}
