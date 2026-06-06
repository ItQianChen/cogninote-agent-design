# CogniNote Agent 第十一阶段任务计划：智能体模型运行时重构

## Summary

第十一阶段聚焦重构智能体执行管线和模型调用层，不做聊天记忆持久化。原第十一阶段的 SQLite 聊天记忆继续顺延；第十二阶段改做 AI 流式 Markdown 渲染重构，SQLite 聊天记忆顺延为第十三阶段。

实施状态：已落地。第十一阶段最初以单一 `CogninoteChatAgent` 承接对话编排；第十八阶段后，当前 `/api/chat/stream` 的真实链路已经升级为 `ChatController -> AgentExecutionService -> ChatAgentRouter -> GeneralChatAgent / KnowledgeBaseChatAgent -> AiRuntimeFactory`。OpenAI-compatible Chat / Embedding 已迁移到 Spring AI OpenAI Runtime，旧自研 OpenAI-compatible HTTP client 与旧 `LlmGateway` / `RagChatService` 兼容层已删除。第十二阶段在此基础上补充了流式 Markdown 输出约束：Spring AI 流式 chunk 必须保留空格和换行，SSE mapper 负责显式取消注册和 event-stream 错误兜底。

当前代码已经能完成 RAG 对话，但模型调用层的职责混在一起：

- `RagChatService` 同时负责检索、来源补全、Prompt 拼装和调用 LLM。
- `ChatController` 直接订阅模型流并组装 SSE 事件。
- `ModelRoutingLlmGateway` 只做 provider 分流，无法承载后续记忆、工具、Advisor、取消保存等智能体能力。
- `DASHSCOPE` 走 Spring AI Alibaba 原生模型，`OPENAI_COMPATIBLE` 目前走自研 HTTP client，两套调用行为缺少统一运行时边界。

第十一阶段的目标是先把“模型运行时”和“智能体执行”从 RAG 业务里拆出来。第十三阶段已在这个边界上接入 SQLite 会话表、消息表、分层聊天记忆和 Spring AI RAG Advisor。

## Reference Findings

本阶段设计参考了本地 `资料参考/` 和 `资料参考/tj-aigc/`：

- `资料参考/2.SpringAI入门.md`
  - Spring AI 推荐以 `ChatClient` 作为对话入口。
  - 流式输出使用 `chatClient.prompt().user(...).stream().content()`。
  - RAG、日志、记忆等增强能力通过 Advisor 组合。
  - ChatMemory 通过 conversation id 参数关联上下文。
- `资料参考/6.平台智能体、通用文本模型、语音.md`
  - 示例里区分 DashScope 和 OpenAI 两类通道。
  - 通用 OpenAI-compatible 服务需要保留用户自定义 Base URL。
- `资料参考/tj-aigc/`
  - `MyChatClientAutoConfiguration` 演示了同时构造 DashScope 和 OpenAI `ChatClient.Builder`。
  - `SpringAIConfig` 演示了 logger advisor、memory advisor 和 ChatClient 的组合。
  - `AbstractAgent` 演示了 Agent 统一入口：system prompt、advisors、tools、tool context、user message 和 stop 处理。
  - `RedisChatMemory` 只作为 ChatMemory 接口参考，CogniNote 后续应实现 SQLite 版本，不引入 Redis。

官方 Spring AI 参考确认：

- Spring AI 1.1.x 使用 `spring-ai-starter-model-openai` 接入 OpenAI 模型。
- OpenAI Chat 支持 `base-url`、`completions-path`、`api-key` 等配置项。
- ChatMemory Advisor 依赖 `ChatMemory.CONVERSATION_ID` 参数关联会话。

这些资料的结论是：CogniNote 应借鉴 ChatClient / Advisor / Agent 的边界，但不能照搬固定 Bean + Redis 记忆模式。CogniNote 的核心约束是用户在运行时维护多条模型配置，所以模型实例必须由 `ModelConfig` 动态创建和缓存。

## Key Changes

- 新增统一 AI Runtime：
  - 负责根据 active `ModelConfig` 创建、缓存和测试 Chat / Embedding 模型运行时。
  - Provider 分流只存在于 Runtime 层，不继续散落到 RAG、索引和 Controller。
- 新增 Agent 执行层：
  - `AgentRequest` 表达一次对话请求。
  - `AgentChatStream` 表达一次对话执行结果。
  - `AgentEvent` 表达 `meta/delta/error/done` 等内部事件。
  - Controller 只把内部事件映射成 SSE，不参与 Prompt、RAG、模型调用细节。
- 抽离 RAG 上下文：
  - 第十一阶段先由 `KnowledgeContextProvider` 负责调用知识库检索、降级、来源补全和上下文长度控制。
  - 向量检索和混合检索仍由 `LuceneKnowledgeStore -> EmbeddingGateway -> AiRuntimeFactory` 链路生成查询向量，必须读取 active `EMBEDDING` 配置。
  - 第十三阶段后，知识库片段由 `CogninoteDocumentRetriever` 交给 Spring AI `RetrievalAugmentationAdvisor` 注入，`PromptAssembler` 不再拼接 `{context}`。
- OpenAI-compatible 和 DashScope 重构到同一运行时接口：
  - DashScope 保留 Spring AI Alibaba 原生实现。
  - OpenAI-compatible 迁移到 Spring AI OpenAI 模型实现，并保留用户自定义 Base URL。
  - 现有 `OpenAiCompatibleClient` / `OpenAiCompatibleEmbeddingClient` 不进入第十一阶段新架构；迁移完成后删除。
- 聊天记忆现状补注：
  - 第十一阶段不新增 `chat_sessions` / `chat_messages` 表。
  - 最终第十三阶段没有保留第十一阶段设想的旧空记忆接口，而是采用 `SQLiteChatMemory` + `CogninoteMemoryAdvisor`。
  - 记忆策略为 SQLite 全量保存，模型输入按“会话摘要 + token 预算内最近原文消息”生成，不写死固定条数。

## Backend Architecture

建议新增或重组包结构：

```text
src/main/java/com/itqianchen/agentdesign/
  domain/
    agent/
      AgentRequest.java
      AgentChatStream.java
      AgentEvent.java
    ai/
      AiChatRuntime.java
      AiEmbeddingRuntime.java
      AiRuntimeFactory.java
  service/
    agent/
      ChatAgentRouter.java
      AbstractChatAgent.java
      GeneralChatAgent.java
      KnowledgeBaseChatAgent.java
      AgentExecutionService.java
      KnowledgeContextProvider.java
      PromptAssembler.java
    ai/
      DashScopeRuntimeFactory.java
      OpenAiCompatibleRuntimeFactory.java
    chat/
      SQLiteChatMemory.java
      CogninoteMemoryAdvisor.java
      ChatSseEventMapper.java
```

职责边界：

- `controller.chat.ChatController`
  - 只接收 `ChatStreamRequest`。
  - 调用 `AgentExecutionService.stream(request)`。
  - 使用 `ChatSseEventMapper` 输出 SSE。
- `service.agent.ChatAgentRouter`
  - 根据 `AgentRequest.useKnowledgeBase` 路由到普通对话或知识库 Agent。
  - 当前不引入 LLM 意图识别路由，避免额外延迟和不确定性。
- `service.agent.AbstractChatAgent`
  - 抽取 active Chat 配置、会话更新、消息落库、流式保存、停止保存、错误保存和日志。
- `service.agent.GeneralChatAgent`
  - 执行普通对话，只挂 `CogninoteMemoryAdvisor`，不检索知识库。
- `service.agent.KnowledgeBaseChatAgent`
  - 执行知识库对话，挂会话记忆和 `RetrievalAugmentationAdvisor`。
- `service.agent.KnowledgeContextProvider`
  - 负责调用 `KnowledgeStore.search(...)`、Embedding 降级、来源补全和 context 字符预算。
  - 不直接构造查询向量；向量生成保留在 `LuceneKnowledgeStore` 经由 `EmbeddingGateway` 调用 active `EMBEDDING` runtime 的链路中。
  - 返回 `KnowledgeContext`，包含实际检索模式、sources、contextText。
- `service.agent.PromptAssembler`
  - 读取 `ChatPromptProperties`。
  - 组装 system/user messages。
  - 第十三阶段后只承载 system prompt 和当前用户问题，不再手动拼接 RAG `{context}` 或聊天历史。
- `service.ai.AiRuntimeFactory`
  - 根据 `ModelConfig.provider()` 创建 runtime。
  - 保证同一配置对应的模型实例可复用。
  - 对 Chat 和 Embedding 分别缓存，缓存 key 必须包含 provider、baseUrl、apiKey、modelName、temperature、embeddingDimensions 等影响行为的字段。

## Provider Runtime Strategy

### DashScope

DashScope 继续使用 Spring AI Alibaba：

- Chat 使用 `DashScopeChatModel`。
- Embedding 使用 `DashScopeEmbeddingModel`。
- 保留 `DashScopeBaseUrls` 的地址规范化逻辑：
  - 用户界面展示 `https://dashscope.aliyuncs.com/api/v1`。
  - Spring AI Alibaba 构造客户端时转换为 `https://dashscope.aliyuncs.com`。
- 保留当前多模态 endpoint 判断：
  - qwen3.5 / qwen3.6 / qwen3.7、VL、omni、image、asr、tts 等模型使用 multimodal endpoint。
  - 这个判断要放在 DashScope Runtime 内部，不让 RAG 或 Controller 知道。

### OpenAI-compatible

OpenAI-compatible 的硬约束是用户自定义 Base URL：

```text
Base URL + /models
Base URL + /chat/completions
Base URL + /embeddings
```

第十一阶段直接接入 `spring-ai-starter-model-openai`，用用户配置动态创建 OpenAI Chat / Embedding 模型：

- OpenAI-compatible Chat 迁移到 Spring AI OpenAI Runtime。
- OpenAI-compatible Embedding 迁移到 Spring AI OpenAI Runtime。
- Spring AI OpenAI Runtime 读取 `ModelConfig.baseUrl()`、`apiKey()`、`modelName()`、`temperature()`、`embeddingDimensions()`。
- 自研 `OpenAiCompatibleClient` 和 `OpenAiCompatibleEmbeddingClient` 在迁移完成后删除，避免长期保留两套 OpenAI-compatible 调用路径。

关键原则：不能因为重构破坏用户自定义 URL 能力，也不能把自定义 OpenAI-compatible URL 喂给 DashScope Provider。

## Agent Execution Flow

第十一阶段目标流程：

```text
ChatController
  ↓
AgentExecutionService
  ↓
ChatAgentRouter
  ├─ useKnowledgeBase=false -> GeneralChatAgent
  │    └─ 普通对话 prompt + CogninoteMemoryAdvisor
  └─ useKnowledgeBase=true/null -> KnowledgeBaseChatAgent
       └─ RAG prompt + CogninoteMemoryAdvisor + RetrievalAugmentationAdvisor
       ↓
AgentEvent(meta/delta/error/done)
  ↓
ChatSseEventMapper
  ↓
SSE: meta -> delta -> done/error
```

第十一阶段仍保持当前前端行为：

- `useKnowledgeBase=false` 的纯对话能力不在本阶段正式开放。
- `/api/chat/stream` 的 SSE 协议保持 `meta -> delta -> done/error`。
- `conversationId` 可以继续由后端生成，但不做持久化。
- 第十二阶段后，`meta` 同时返回 `requestId`；用户点击停止时通过 `POST /api/chat/stream/{requestId}/cancel` 显式取消模型订阅。
- 普通浏览器刷新、切页或 SSE 连接断开不等于用户停止。后端仍消费模型流到完成，为第十三阶段完整保存 assistant 消息预留空间。

第十三阶段再扩展：

- `conversationId` 从前端传入或由后端创建并返回。
- `useKnowledgeBase=false` 走纯模型对话。
- `useKnowledgeBase=true` 走 RAG + 分层会话记忆。
- SQLite 保存全量历史，模型输入由会话摘要和 token 预算内最近原文消息组成。

## API Compatibility

第十一阶段不强制新增公开 API。第十二阶段为用户显式停止生成补充了一个兼容新增接口，`POST /api/chat/stream` 的主 SSE 协议仍保持向后兼容。

保留：

```text
POST /api/chat/stream
POST /api/chat/stream/{requestId}/cancel
```

请求体兼容当前字段：

```json
{
  "question": "string",
  "topK": 8,
  "mode": "HYBRID"
}
```

响应 SSE 兼容当前事件：

```text
meta
delta
error
done
```

可以在内部 DTO 上预留 `conversationId`、`useKnowledgeBase` 字段，但第十一阶段不要让前端展示假功能。真正纯对话和持久化记忆放到第十三阶段。

流式文本约束：

- `delta.text` 是模型原始增量，可能是单独的空格、换行或缩进；后端 runtime 和前端 parser 都不能 `trim`。
- `SpringAiChatRuntime` 只过滤 `null` 和空字符串，不使用 `isBlank()`，避免把 Markdown 语法所需空白吞掉。
- SSE 已经进入 `text/event-stream` 后不能再走全局 JSON `ApiResponse` 错误响应；业务错误应映射为 SSE `error`，容器级异常只关闭响应。

## Prompt And Advisor Strategy

第十一阶段不把当前 Lucene RAG 强行替换成 Spring AI `QuestionAnswerAdvisor`。

原因：

- 当前项目需要返回自定义 sources，前端要展示文件名、路径、页码、chunk 和预览。
- 当前 `KnowledgeStore` 是 Lucene + SQLite 组合，不是 Spring AI VectorStore。
- 当前向量检索依赖 active `EMBEDDING` 模型生成查询向量，不能在 Agent 重构时把 Embedding 调用从检索链路里抹掉。
- 当前 RAG 有 Embedding 降级到关键词检索的逻辑，不能丢。

第十一阶段采用折中方案：

- Prompt 组装继续由 CogniNote 自己控制。
- RAG 检索抽成 `KnowledgeContextProvider`，形成 advisor-like 的业务组件；向量查询仍通过 `KnowledgeStore -> EmbeddingGateway -> AiRuntimeFactory` 使用 active `EMBEDDING` runtime。
- 日志、记忆、工具调用等通用增强能力在 Agent 层预留 Advisor 接入点。
- 第十三阶段最终实现 SQLite `ChatMemory` 适配和 `CogninoteMemoryAdvisor`，RAG 改由 `CogninoteDocumentRetriever` + `RetrievalAugmentationAdvisor` 注入，不再手动拼 `{context}`。

## Logging And Observability

重构后必须补充可观察性，避免模型调用出错时只能看前端白屏：

- 每次 agent 执行记录：
  - requestId
  - conversationId
  - provider
  - modelName
  - retrievalMode
  - topK
  - sourceCount
  - durationMs
- 错误日志保留异常栈，但不能记录完整 API Key。
- API Key 日志只允许显示掩码，例如 `sk-***abcd`。
- OpenAI-compatible URL 日志记录规范化后的 endpoint，方便排查 `/chat/completions` 拼接问题。
- DashScope 日志记录实际 endpoint 类型：text-generation 或 multimodal-generation。

## Implementation Changes

### 第一组：运行时接口

- 新增 `AiChatRuntime`：
  - `Flux<String> stream(Prompt prompt)`
  - `void testConnection(Prompt prompt)`
- 新增 `AiEmbeddingRuntime`：
  - `float[] embed(String text)`
  - `List<float[]> embedBatch(List<String> texts)`
- 新增 `AiRuntimeFactory`：
  - `AiChatRuntime chatRuntime(ModelConfig config)`
  - `AiEmbeddingRuntime embeddingRuntime(ModelConfig config)`

### 第二组：Provider 实现

- 将 `DashScopeModelFactory` 收敛到 DashScope Runtime 内。
- 新增 Spring AI OpenAI Runtime，用 `spring-ai-starter-model-openai` 承接 OpenAI-compatible Chat / Embedding 调用。
- 删除 `OpenAiCompatibleClient` / `OpenAiCompatibleEmbeddingClient` 的业务调用路径；迁移稳定后删除类文件。

### 第三组：Agent 执行

- 第十一阶段新增过单一 `CogninoteChatAgent`，承接原 RAG 对话编排职责；第十八阶段已替换为 `ChatAgentRouter + AbstractChatAgent + GeneralChatAgent + KnowledgeBaseChatAgent`。
- 将 Prompt 构造拆到 `PromptAssembler`。
- 将原 `searchWithFallback()`、`hydrateSources()`、`buildContext()` 拆到 `KnowledgeContextProvider`，并保留 `KnowledgeStore` / `EmbeddingGateway` 负责查询向量生成的职责边界。
- 第十三阶段后，旧的空记忆接口已删除，聊天记忆由 `SQLiteChatMemory`、`ConversationMemorySnapshotService` 和 `CogninoteMemoryAdvisor` 承接。

### 第四组：SSE 适配

- `ChatController` 不再直接订阅 `RagChatStream.answer()`。
- 新增 `ChatSseEventMapper` 或 `ChatStreamEmitterService`。
- Controller 只负责创建 `SseEmitter`、调用 mapper、完成或报错。
- 第十二阶段后，`ChatSseEventMapper` 还负责注册 `requestId` 与模型流订阅的取消关系；只有显式取消接口会中断模型流。
- SSE 发送失败时只安全完成 emitter，不调用 `completeWithError()`，避免全局异常处理在 `text/event-stream` 响应上写 JSON。

## Documentation Changes

- 新增本计划文件：`docs/phase-11-agent-model-runtime-refactor-plan.md`。
- 更新 `docs/cogninote-agent-design.md`：
  - Milestone 11 改为智能体模型运行时重构。
  - SQLite 聊天记忆顺延到 Milestone 13。
  - 模型调用章节补充 AI Runtime / Agent 执行层。
- 更新 `docs/phase-7-chat-ui-refactor-plan.md`、`docs/phase-8-multi-model-configuration-plan.md`、`docs/phase-9-ui-visual-readability-plan.md`、`docs/phase-10-knowledge-base-folders-plan.md` 中关于聊天记忆阶段的描述。
- 更新 `docs/model-configuration-guide.md`，说明模型配置后续由统一 AI Runtime 消费。
- README 只更新开发状态和 Phase 11 文档入口，不展开完整设计细节。

## Test Plan

后端：

```powershell
mvn test
```

重点覆盖：

- DashScope Runtime 使用默认百炼地址，不读取用户自定义 host。
- DashScope qwen3.7 / VL / omni 等模型仍走 multimodal endpoint。
- OpenAI-compatible Runtime 通过 Spring AI OpenAI 使用用户自定义 Base URL，Chat / Embedding / Models 调用保持 OpenAI-compatible 语义。
- Provider 切换不会影响 active Chat / active Embedding 独立性。
- `KnowledgeContextProvider` 调用 `KnowledgeStore` 检索；`LuceneKnowledgeStore` 通过 active Embedding runtime 生成查询向量，Embedding 不可用时仍从 HYBRID/VECTOR 降级到 KEYWORD。
- `PromptAssembler` 能稳定注入系统提示词、知识上下文和用户问题。
- `ChatSseEventMapper` 保持 `meta -> delta -> done/error` 输出顺序。

前端：

- 第十一阶段原则上不改前端。
- 如果请求 DTO 或 SSE payload 有兼容性字段调整，只跑：

```powershell
npm --prefix cogniNote-agent-front run build
```

不做桌面整包验证：

- 本阶段只验证后端模型调用和对话功能。
- 不执行 Tauri / 桌面安装包打包验收。

## Assumptions

- 第十一阶段只重构智能体执行层和模型运行时，不做 SQLite 聊天记忆。
- SQLite 聊天记忆、纯模型对话和会话恢复顺延到第十三阶段。
- 现有 `/api/chat/stream` 对前端保持兼容。
- DashScope Provider 继续使用 Spring AI Alibaba 原生客户端。
- OpenAI-compatible Provider 必须继续支持用户自定义 Base URL。
- 当前 Lucene + SQLite RAG 不迁移到 Spring AI VectorStore。
- Prompt 文本继续放在配置文件中管理，代码只注入运行时变量。
