# 第 40 阶段：移除 DashScope，统一为 OpenAI-compatible 协议

## 目标

第 40 阶段围绕三个方面完成模型协议与推理能力改造：

1. 彻底移除 DashScope 专用实现，只保留 `OPENAI_COMPATIBLE` 一种协议，并保留 `ModelProvider` / `ModelRuntimeFactory` 抽象，为后续 `RESPONSES`、`ANTHROPIC` 等协议扩展留出边界。
2. 在对话模型配置中新增通用思考等级 `reasoning_effort`，可选 `NONE / LOW / MEDIUM / HIGH / XHIGH / MAX`，默认 `NONE`。
3. 增加思考推理内容输出：后端流式返回、前端“思考中”可展开展示、最终内容单独存数据库，不混入正常回答。

Chat、Embedding 和 Vision/OCR 三条链路全部改走 OpenAI-compatible 的 `/chat/completions`、`/embeddings` 和 `/models`。

本次是破坏性更新，不保留旧 DashScope 配置的运行时兼容。历史数据库中仍可能存在 `provider='DASHSCOPE'` 的记录，通过新增 Flyway 迁移统一改写，不提供降级或回退通道。

## 非目标

- 不保留 DashScope 原生 `/api/v1/services/...` 调用链。
- 不维护 `DashScopeBaseUrls`、`DashScopeModelFactory`、`DashScopeRuntimeFactory`、`DashScopeEmbeddingRuntime` 等兼容代码。
- 不在配置页继续展示 DashScope 或阿里百炼 Provider 选项。
- 不修改已经执行过的 `V1__baseline.sql`，避免 Flyway checksum 冲突。
- 不在本阶段实现 Anthropic 或 OpenAI Responses，只保留后续扩展所需的抽象边界。

## 架构约束

`ModelProvider` 枚举和 `ModelRuntimeFactory` 继续保留，作为未来扩展 `ANTHROPIC`、`OPENAI_RESPONSES` 等协议的稳定路由点。本阶段先让枚举只包含 `OPENAI_COMPATIBLE`，业务层继续通过 `AiRuntimeFactory` 获取运行时，避免把具体厂商逻辑散落到服务层。

## 删除内容

删除以下源文件：

- `src/main/java/com/itqianchen/agentdesign/service/model/DashScopeBaseUrls.java`
- `src/main/java/com/itqianchen/agentdesign/service/model/DashScopeModelFactory.java`
- `src/main/java/com/itqianchen/agentdesign/service/ai/DashScopeRuntimeFactory.java`
- `src/main/java/com/itqianchen/agentdesign/service/ai/DashScopeEmbeddingRuntime.java`

删除以下测试文件：

- `src/test/java/com/itqianchen/agentdesign/service/model/DashScopeBaseUrlsTests.java`
- `src/test/java/com/itqianchen/agentdesign/service/model/DashScopeModelFactoryTests.java`

## 后端改造

### Provider 与默认值

- `ModelProvider` 只保留 `OPENAI_COMPATIBLE`。
- `ModelConfigDefaults.PROVIDER` 改为 `ModelProvider.OPENAI_COMPATIBLE`。
- `ModelConfigDefaults` 中 `DashScope` 相关展示名统一改为 `OpenAI-compatible`。
- `ModelConfigDefaults.BASE_URL` 默认值由阶段开始时确认，推荐为空字符串并强制前端填写；若首启流程必须提供默认地址，则使用目标服务的 OpenAI-compatible Base URL，不再使用 DashScope 原生 API Root。

### 运行时路由

- `ModelRuntimeFactory` 删除 DashScope 依赖，`chatRuntime` 和 `embeddingRuntime` 都直接委托给 `OpenAiCompatibleRuntimeFactory`。
- `SpringAiEmbeddingRuntime` 成为唯一通用 Embedding 运行时，不再区分 query/document textType。
- `ModelConnectionTestService` 继续复用 `AiRuntimeFactory`，不新增 Provider 分流。

### 配置归一化

- `ModelConfigService` 删除 `normalizeLoadedConfig` 中针对 `DASHSCOPE` 的 Base URL 分支。
- `normalizeBaseUrl` 只调用 `OpenAiCompatibleUrls.normalizeBaseUrl`。
- `ModelCatalogService.modelsUri` 只调用 `OpenAiCompatibleUrls.modelsUri`。
- `TokenEstimator` 删除 `provider() == ModelProvider.DASHSCOPE` 判断，保留基于模型名的启发式判断。

### Embedding 可用性

- `SpringAiEmbeddingGateway` 删除 `dashscopeApiKey` 字段、构造参数和 `spring.ai.dashscope.api-key` 注入。
- `isAvailable` 不再判断 `dashscope` provider，只依据数据库中的 active Embedding 配置和可选自动装配模型判断。

### 依赖与配置

- `pom.xml` 删除 `spring-ai-alibaba.version`、`spring-ai-alibaba-bom` 和 `spring-ai-alibaba-starter-dashscope`。
- `application.yaml` 删除整个 `spring.ai.dashscope` 配置块。
- `application.yaml` 中 DashScope/OpenAI-compatible 观测注释改为只描述 OpenAI-compatible。

## 数据库迁移

新增 `V4__drop_dashscope_provider.sql`，不改写 `V1__baseline.sql`。该迁移同时完成 Provider 收敛、思考等级初始化和消息推理内容字段初始化。

迁移内容：

```sql
UPDATE model_configs
SET provider = 'OPENAI_COMPATIBLE',
    base_url = '<目标兼容 Base URL>'
WHERE provider = 'DASHSCOPE';

ALTER TABLE model_configs
ADD COLUMN reasoning_effort TEXT NOT NULL DEFAULT 'NONE';

ALTER TABLE chat_messages
ADD COLUMN reasoning_content TEXT;
```

同时把 `DatabaseMigrationService.CURRENT_SCHEMA_VERSION` 从 `3` 更新为 `4`，并确认备份恢复相关白名单和 schema 校验不需要额外包含新列。迁移不会重建向量索引；Base URL 或 Embedding 模型变化后，仍沿用既有规则，由用户按需手动重建索引。

## 前端改造

### 模型配置 Store

`cogniNote-agent-front/src/stores/model-config.js`：

- `providerOptions` 只保留 `OPENAI_COMPATIBLE`。
- `defaultForm`、`formFromConfig`、`normalizeFormForRole` 不再产生 DashScope Provider 或 Display Name。
- `normalizeProviderValue` 默认返回 `OPENAI_COMPATIBLE`，删除 DashScope 归一化分支。

### 模型配置页面

`cogniNote-agent-front/src/views/model-config-view.vue`：

- Base URL 输入框不再使用 `https://dashscope.aliyuncs.com/api/v1` 作为占位提示。
- Provider 选择器可以改为只读标签，或直接移除，让用户只维护 Base URL、模型名和 API Key。
- 页面底部说明文案改为只描述 OpenAI-compatible 的 `/chat/completions`、`/embeddings` 和 `/models` 调用方式。

### OCR 设置

- `cogniNote-agent-front/src/stores/ocr-settings.js` 默认 Provider、Display Name 和 Base URL 改为 `OPENAI_COMPATIBLE`。
- `cogniNote-agent-front/src/components/ocr-settings-panel.vue` 删除 `DASHSCOPE` 标签映射。

## 测试改造

后端测试中所有 `DASHSCOPE`、`dashscope.aliyuncs.com/api/v1` 相关常量改为 `OPENAI_COMPATIBLE` 和目标 Base URL。覆盖范围至少包括：

- `ModelConfigServiceTests`
- `ModelConfigControllerTests`
- `ModelCatalogServiceTests`
- `OcrSettingsServiceTests`
- `ModelVisionOcrEngineTests`
- `DocumentFailureCodecTests`
- `DocumentIngestionServiceTests`
- `KnowledgeMaintenanceQueueServiceTests`
- `ChatControllerTests`

思考等级与推理存储相关的测试补充：

- `ModelConfigServiceTests` 覆盖 `reasoning_effort` 默认 `NONE`、空值归一化和 `CHAT` 专属语义。
- `ModelConfigControllerTests` 覆盖 `reasoning_effort` 请求响应透传。
- `OpenAiCompatibleRuntimeFactoryTests` 覆盖 `NONE` 映射 `enable_thinking=false`，非 `NONE` 映射 `reasoningEffort` 和 `enable_thinking=true`。
- Chat 消息落库测试覆盖 `reasoning_content` 只保存最终内容、不污染 `content`。
- Chat SSE 测试覆盖 `reasoning/streaming`、`reasoning/done` 和正常 `delta` 分流。

前端测试同步更新：

- `model-config.test.js`
- `02-model-config.spec.js`
- `05-restart-persistence.spec.js`

删除后的测试不得再引用已删除的 DashScope 工厂或 Base URL 适配器。

## 思考等级配置

移除 DashScope 后，模型配置不再维护两套 reasoning 参数。`OPENAI_COMPATIBLE` 统一通过 `OpenAiChatOptions` 透传思考等级，不需要重写 Spring AI 官方类。Spring AI 1.1.2 的 `OpenAiChatOptions` 已提供 `reasoningEffort(...)` 和 `extraBody(...)`。

### 数据模型

`model_configs` 新增单一字段：

- `reasoning_effort TEXT NOT NULL DEFAULT 'NONE'`：通用思考等级。

取值统一使用大写枚举，默认 `NONE`：

```text
NONE / LOW / MEDIUM / HIGH / XHIGH / MAX
```

前端首版只暴露 `NONE / LOW / MEDIUM / HIGH / XHIGH`。字段只对 `CHAT` 角色有意义，`EMBEDDING / VISION` 不展示、不保存。

对应改动点：

- `ModelConfig` record 增加 `String reasoningEffort`。
- `ModelConfigMapper.xml` 的 `resultMap`、`ModelConfigColumns`、`INSERT`、`ON CONFLICT` 四处同步加列。
- `ModelConfigRequest`、`ModelConfigUpsertRequest`、`ModelConfigResponse` 增加字段。
- `ModelConfigService` 的 `mergeRequest`、`defaultConfig`、`normalizeLoadedConfig`、`toModelConfigRequest` 同步补字段，并把空值归一化为 `NONE`。

### 前端配置控件

在 `cogniNote-agent-front/src/views/model-config-view.vue` 的 Temperature 附近，仅 `CHAT` 角色显示 `推理等级` 单选控件：

- 选项：`NONE / LOW / MEDIUM / HIGH / XHIGH`。
- 默认选中 `NONE`。
- 使用 `el-select` 或分段按钮，绑定 `form.reasoningEffort`。

`cogniNote-agent-front/src/stores/model-config.js` 需要同步：

- `defaultForm`
- `formFromConfig`
- `normalizeFormForRole`
- `normalizeConfigForRole`
- `formPayload`
- 返回 `reasoningEffortOptions` 供页面渲染

### 运行时透传

在 `OpenAiCompatibleRuntimeFactory.buildChatModel` 中按配置透传：

```java
OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
        .model(config.modelName())
        .temperature(config.resolvedTemperature());

if (config.reasoningEffort() == null || "NONE".equalsIgnoreCase(config.reasoningEffort())) {
    options.extraBody(Map.of("enable_thinking", false));
} else {
    options.reasoningEffort(config.reasoningEffort().toLowerCase(Locale.ROOT));
    options.extraBody(Map.of("enable_thinking", true));
}
```

关键点：

- `reasoningEffort` 是标准参数，走 builder。
- `enable_thinking` 是非标准参数，走 `extraBody`；官方 OpenAI 服务会忽略它，百炼/Qwen 兼容服务需要它。
- `ChatRuntimeKey` 必须加入 `reasoningEffort`，否则修改配置后可能复用旧客户端。

## 推理内容输出与存储

思考内容必须与正常回答分离：`reasoning_content` 只用于展示和落库，`content` 仍表示最终回答。

### 后端流式链路

- `SpringAiChatRuntime` 读取流式 metadata 中的 `reasoningContent`。
- `AgentEvent.Delta` 增加独立 `reasoning` 字段，与 `text` 分开。
- `ChatSseEventMapper` 新增 reasoning SSE 事件，不把推理文本拼到 `content`。

SSE 事件约定：

```json
{"type":"reasoning","delta":"正在分析用户问题","status":"streaming"}
{"type":"reasoning","delta":"","status":"done"}
```

后续正常回答仍使用既有 `delta` 事件。

### 数据库存储

`chat_messages` 新增：

```text
reasoning_content TEXT
```

只保存最终完整思考内容，不逐字落库每个 delta。历史消息回填模型时只回填 `content`，不回填 `reasoning_content`。

对应改动点：

- `ChatMessage` 领域对象、Mapper 和响应 DTO 增加 `reasoningContent`。
- 消息保存链路在 assistant 最终内容落库时一并写入 `reasoning_content`。
- 消息查询接口返回 `reasoningContent`，供历史会话展开查看。

### 前端展示

- `chat.js` 新增 `reasoning` 事件处理，累积到 `message.reasoningContent`。
- 收到 `status=streaming` 时显示“思考中”。
- 收到 `status=done` 时停止 loading，展示可展开的思考块。
- 最终回答仍追加到 `message.content`，不做字符串拼接。
- `chat-view.vue` 增加默认收起、点击展开的思考过程区域。

## 验证

后端：

```powershell
$ErrorActionPreference = 'Stop'
mvn test
```

前端：

```powershell
$ErrorActionPreference = 'Stop'
npm test
npm run build
```

手工验证：

- Chat 配置保存、连接测试和流式对话。
- `reasoning_effort` 默认 `NONE`，切换后运行时不复用旧客户端。
- 开启思考等级后，前端显示“思考中”，思考块可展开，最终回答不混入推理文本。
- 历史会话查询返回 `reasoningContent`，且重新发送时模型上下文不包含旧思考内容。
- Embedding 模型列表拉取和向量索引。
- Vision/OCR 配置保存和调用。
- 数据库中存在历史 `DASHSCOPE` 配置时，V4 迁移后能正常读取并进入 OpenAI-compatible 运行时。

## 风险与验收

- Embedding 是最大风险点。原先 DashScope 的 query/document textType 语义在 OpenAI-compatible `/embeddings` 中可能不生效，需要确认目标服务是否支持当前默认模型 `text-embedding-v4`。若不支持，用户在模型配置页修改模型 ID 后即可继续。
- Vision/OCR 同样依赖 OpenAI-compatible Chat Completions 的多模态能力，删除前必须完成一次实际调用验证。
- 思考等级是否被目标 OpenAI-compatible 服务支持取决于具体模型；`enable_thinking` 对非 Qwen 服务可能被忽略，必须在真实账号上验证。
- 推理内容可能很长，`chat_messages.reasoning_content` 直接存完整文本即可，但应避免在流式阶段逐 token 写库。
- 本阶段完成后，仓库中不应再存在 `ModelProvider.DASHSCOPE`、`DashScopeBaseUrls`、`DashScopeModelFactory` 或 `spring-ai-alibaba-starter-dashscope` 引用。
- 保留 `ModelProvider` 和 `ModelRuntimeFactory`，为后续 Anthropic、OpenAI Responses 扩展留出边界。
