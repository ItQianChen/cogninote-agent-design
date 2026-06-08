# 第二十一阶段计划：模型驱动的追问补全 Agent

## Summary

第 21 阶段在知识库 Agent 的检索前增加一个内部“追问补全判断 Agent”。它复用当前 active Chat 模型，但使用独立的严格 JSON Prompt，专门判断当前问题是否是省略式追问，并在需要时生成更完整的检索 query。

这个 Agent 只影响知识库检索，不改变用户原始消息、不改变最终回答的用户问题、不写入 SQLite，也不改变 HTTP API 或 SSE 协议。失败时直接回退原问题，不能阻断主对话。

## 背景问题

典型问题链路：

1. 用户开启知识库，先问：`红黑树是什么？在 Java 中哪里用到了这个结构？`
2. 模型基于知识库回答。
3. 用户继续问：`给出代码示例`
4. 如果直接用 `给出代码示例` 检索，RAG 可能召回无关代码片段，最终回答偏离“红黑树”这个真实语境。

本地规则很难穷举所有追问表达，比如“继续”“展开”“这个呢”“举个例子”“上面那个怎么实现”。因此本阶段使用模型判断，但用强约束 Prompt 限制它只做 query 改写，不做回答。

## Key Changes

- 新增 `QueryContextualizerAgent`，作为 `KnowledgeBaseChatAgent` 的前置子 Agent。
- 新增 `AiChatRuntime.callText(systemPrompt, userMessage)`，用于非流式获取短 JSON。
- 新增 `QueryContextualizerProperties`：
  - `app.chat.query-contextualizer.enabled=true`
  - `app.chat.query-contextualizer.max-history-messages=6`
  - `app.chat.query-contextualizer.max-rewritten-query-length=800`
- 新增 Prompt：
  - `app.chat.prompts.query-contextualizer.system`
  - `app.chat.prompts.query-contextualizer.user`
- `CogninoteDocumentRetriever` 同时持有：
  - `originalQuestion`：用户原始问题，用于最终回答边界。
  - `retrievalQuery`：补全后的检索问题，用于知识库检索。
- `CogninoteRagQueryAugmenter` 在注入 RAG 上下文时明确区分“用户原始问题”和“知识库检索问题”，要求模型回答原始问题，避免被无关片段带偏。

## 行为规则

- `useKnowledgeBase=false`：走 `GENERAL_CHAT`，不会调用补全 Agent。
- `useKnowledgeBase=true` 或省略：走 `KNOWLEDGE_BASE`。第 21 阶段计划中的行为是每轮检索前调用补全 Agent；第 23 阶段已改为由 `queryContextualizerMode` 控制，默认 `AUTO`，只有省略式追问或弱检索等场景才调用。
- 当前问题是完整新问题时，补全 Agent 必须返回 `shouldRewrite=false`。
- 当前问题依赖上文时，补全 Agent 可以返回 `shouldRewrite=true` 和 `rewrittenQuery`。
- `rewrittenQuery` 只能补充历史中已经出现的主题，不能加入新事实。
- 非法 JSON、字段缺失、空 rewrittenQuery、过长 rewrittenQuery 或模型调用异常时，统一使用原问题检索。
- 补全结果只写日志，不通过 SSE meta 暴露给前端。

## 数据与接口

HTTP API 不变：

- `POST /api/chat/stream`
- SSE `meta/delta/done/error`
- RAG `sources`

SQLite schema 不变：

- 不新增表。
- 不保存补全 Agent 的内部消息。
- `chat_messages.content` 继续保存用户原始输入。
- `chat_messages.agent_type` 仍只记录最终回答 Agent：`GENERAL_CHAT` 或 `KNOWLEDGE_BASE`。

## 日志

成功时记录：

- `requestId`
- `conversationId`
- `rewritten`
- `confidence`
- `originalQuestion`
- `retrievalQuery`
- `reason`

失败时记录 warn 级别原因并回退原问题；完整堆栈只放 debug，避免模型偶发坏 JSON 造成日志噪音。

## Test Plan

- `mvn test`
- 当前问题为 `给出代码示例`，历史主题为“红黑树 / Java 使用场景”时，检索 query 应包含红黑树和 Java 语境。
- 当前问题为 `HashMap 是怎么扩容的？` 时，不补全，检索 query 等于原问题。
- 模型返回非法 JSON、空 rewrittenQuery、过长 query 或调用异常时，检索 query 回退原问题。
- 知识库关闭走 `GENERAL_CHAT`，不调用补全 Agent。
- 用户消息落库仍是原文，不能把补全 query 写进聊天历史。
- RAG Prompt 同时包含原始问题和补全检索问题边界，最终回答仍面向原始问题。

## Assumptions

- 本阶段不引入本地规则作为主要判断方式。
- 补全 Agent 复用 active Chat 模型，不新增前端模型角色。
- 不新增前端调试展示。
- 不新增数据库表。
- 不改变普通对话 Agent 和知识库 Agent 的外部路由语义。

## 后续阶段修正

第 23 阶段新增 `AUTO`、`ALWAYS`、`OFF` 三种追问补全策略，并将默认行为从“知识库模式每轮都调用模型判断”调整为 `AUTO` 自动触发。该修正保留第 21 阶段的补全能力和失败回退规则，但通过本地触发判断器、弱检索重试和前端设置降低完整问题场景的额外延迟。
