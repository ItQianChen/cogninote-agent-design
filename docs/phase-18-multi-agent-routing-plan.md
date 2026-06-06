# 第十八阶段计划：路由式多智能体 Agent 与模式隔离记忆

实施状态：已落地。当前 `/api/chat/stream` 链路为 `ChatController -> AgentExecutionService -> ChatAgentRouter -> GeneralChatAgent / KnowledgeBaseChatAgent`。关闭知识库时路由到 `GENERAL_CHAT`，不会检索知识库、不会挂 RAG Advisor，也不会继承知识库模式的“依据不足”拒答规则；开启或省略知识库开关时路由到 `KNOWLEDGE_BASE`，继续保留本轮 RAG sources 和 Embedding 降级。

## Summary

第十八阶段把单一对话 Agent 升级为路由式多智能体架构。实现参考 `资料参考/tj-aigc` 的 `Agent + AbstractAgent + 具体 Agent` 抽象方式，但按 CogniNote 现有 Spring Boot、SSE、SQLite 记忆和 Spring AI Advisor 架构重写，不照搬 Redis、Nacos、业务工具和 LLM 二次路由。

本阶段先实现两个 Agent：普通对话智能体 `GENERAL_CHAT` 和知识库智能体 `KNOWLEDGE_BASE`。路由依据为用户显式开关 `useKnowledgeBase`。关闭知识库时必须进入普通对话 Agent，避免继续使用 RAG prompt 导致模型按“知识库依据不足”拒答。

## Key Changes

- 新增 `AgentType`、`ChatAgent`、`AbstractChatAgent`、`GeneralChatAgent`、`KnowledgeBaseChatAgent` 和 `ChatAgentRouter`。
- `AgentExecutionService` 只负责把 HTTP DTO 转成 `AgentRequest` 并交给 Router；Controller 和 SSE 协议保持不变。
- `AbstractChatAgent` 抽取 active Chat 模型、会话更新、消息落库、流式保存、错误保存、停止保存和日志。
- `GeneralChatAgent` 只挂 `CogninoteMemoryAdvisor`，不检索知识库，不挂 RAG Advisor，返回 `retrievalMode=null` 和空 sources。
- `KnowledgeBaseChatAgent` 保留 `KnowledgeContextProvider`、`CogninoteDocumentRetriever` 和 `RetrievalAugmentationAdvisor` 链路。
- Prompt 按 Agent 分离：`general.system/user` 用于普通对话，`rag.system/user/empty-context` 只用于知识库模式。

## Memory Isolation

- `chat_messages` 新增 `agent_type` 字段，记录每条消息所属 Agent。
- 旧消息兼容读取：assistant 有 `retrieval_mode` 时视为 `KNOWLEDGE_BASE`，否则视为 `GENERAL_CHAT`；旧 user 消息按中性历史处理。
- `ConversationMemorySnapshot` 返回带 `agentType` 的内部记忆条目，`CogninoteMemoryAdvisor` 根据当前 Agent 决定注入方式。
- 同 Agent 历史按普通 user/assistant 消息注入。
- 跨 Agent assistant 历史只作为带标签的系统参考注入，明确不是当前规则、不是知识库证据、不能作为引用来源。
- 摘要继续保留，但摘要文本带 Agent 标签，避免混合摘要在模式切换后污染当前 Agent。

## Public Interfaces

- `POST /api/chat/stream` 请求体不变。
- SSE `meta/delta/done/error` 不变。
- 前端不新增必填字段。
- 日志新增 `agentType`，用于确认本轮走 `GENERAL_CHAT` 还是 `KNOWLEDGE_BASE`。

## Test Plan

- `mvn test`
- 覆盖 `useKnowledgeBase=false` 路由到 `GENERAL_CHAT`，不调用知识库检索，不挂 RAG Advisor。
- 覆盖 `useKnowledgeBase=true/null` 路由到 `KNOWLEDGE_BASE`，保留 RAG sources 和 Embedding 降级。
- 覆盖同一会话中从知识库模式切到普通模式，普通模式不再使用 RAG prompt，也不继承“知识库依据不足”的拒答规则。
- 覆盖同一会话中从普通模式切到知识库模式，普通历史只能作为对话背景，不能作为引用来源。
- 覆盖旧 SQLite 缺少 `agent_type` 时自动补列，旧消息读取不报错。

已执行验证：

```text
mvn -Dtest=ChatAgentRouterTests test
mvn test
```

结果：后端 68 个测试通过，0 failures，0 errors。

## Assumptions

- 本阶段只做两个 Agent，不做工具调用 Agent、联网 Agent、代码 Agent。
- 路由使用用户开关，不额外调用 LLM 做语义路由。
- 同一会话允许来回切换 Agent，UI 历史完整保留。
- 模式隔离只影响模型输入，不删除 SQLite 原始消息。
- Lucene、SQLite、Spring AI Advisor 和 SSE 协议保持不变。
