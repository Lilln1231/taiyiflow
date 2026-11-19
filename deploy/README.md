# 常驻运维数字员工（可选）

`worker-ops.yml` 是用 `taiyiflow-sdk-worker` 部署常驻数字员工的模板。跟命令行工具的区别：

| | 命令行 ops-copilot | DigitalWorker |
|---|---|---|
| 谁发起 | 本地运维主动提问 | 平台反向下发任务 |
| 运行形态 | 一次性命令 / 交互会话 | 常驻进程或容器 |
| 身份治理 | 用个人令牌 | `WorkerIdentityValidator` 由第三方系统校验 |
| 审批 | 本地终端 | 平台审批通道 |

## 关键约束

- 同一个 `workerId` **只能跑一个副本**；容器用 Recreate 更新策略。
- worker **没有 HTTP 端口、没有 healthcheck**；就绪状态看协同连接 `connection.ready` 和平台在线状态，存活由容器平台负责。
- 三个卷必须分开：`workspace`（临时文件）、`state-dir`（Outbox 补传）、`credentials`（加密凭证）。凭证不能进镜像或前两个卷。
- `extensions.identity-validator` 必填，替换成你方身份系统的类；不知道该填什么之前不要上线。

## 构建与运行

在 SDK 仓库根目录：

```bash
./mvnw clean install -DskipTests
./mvnw -pl taiyiflow-sdk-worker -am package     # 产出 *-all.jar
docker build -t taiyiflow-worker:local .
docker run -d --name taiyiflow-worker \
  --restart always \
  -e TAIYIFLOW_API_KEY="$TAIYIFLOW_API_KEY" \
  -v taiyiflow-worker-state:/var/lib/taiyiflow/worker \
  -v taiyiflow-worker-runtime-state:/var/lib/taiyiflow/runtime-state \
  -v taiyiflow-worker-credentials:/var/lib/taiyiflow-credentials \
  -v "$(pwd)/deploy/worker.yml:/config/worker.yml:ro" \
  -v "$(pwd)/build/adapters:/app/lib:ro" \
  taiyiflow-worker:local
```

本地直接跑（不打包镜像）：

```bash
java -cp 'taiyiflow-sdk-worker/target/*-all.jar:build/adapters/*' \
  io.taiyiflow.sdk.worker.DigitalWorkerMain --config deploy/worker.yml
```

停机时发 SIGTERM：停止接新任务 → 按 `drain-timeout` 排空 → 关闭协同连接。
