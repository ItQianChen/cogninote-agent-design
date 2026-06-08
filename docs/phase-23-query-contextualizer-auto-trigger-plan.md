# 第 23 阶段计划：知识库追问补全自动触发与前端可配置

## Summary

第 23 阶段把第 21 阶段的“知识库模式每轮都先调用追问补全 Agent”调整为“用户可配置策略”，默认 `AUTO`。这样完整问题可以直接检索，省略式追问或原问题检索弱时才补全检索 query，降低额外模型调用带来的延迟和成本。

该功能只影响知识库模式下的检索 query，不会改写用户原始消息，不会改变普通对话，也不会把内部补全结果写入 SQLite 聊天记录。

## 背景

第 21 阶段解决了“给出代码示例”“继续”“展开”等省略式追问直接检索不准的问题。但在第 22 阶段之后，主流模型上下文窗口已经普遍达到 `128K`、`200K` 甚至 `1M`，完整问题场景没有必要每轮都额外调用一次模型来判断是否需要改写检索问题。

第 23 阶段保留追问补全能力，但把触发权收敛为后端全局聊天设置，并在前端“设置 -> 模型 -> 对话模型”里暴露给用户选择。

## 模式语义

| 模式 | 说明 | 适用场景 |
| --- | --- | --- |
| `AUTO` | 默认值。通过本地轻量打分判断是否调用补全 Agent；省略式追问或弱检索时再补全。 | 日常使用，兼顾准确性、延迟和成本。 |
| `ALWAYS` | 保持第 21 阶段行为，知识库模式每轮都先调用补全 Agent 判断是否需要改写检索 query。 | 更看重追问稳健性，不介意额外延迟。 |
| `OFF` | 完全关闭追问补全，始终使用用户原问题检索。 | 成本最低，或用户明确不希望内部模型改写检索 query。 |

## 配置优先级

1. SQLite `app_settings` 用户设置优先。
2. 没有用户设置时读取 `COGNINOTE_QUERY_CONTEXTUALIZER_MODE`。
3. 未配置 mode 且旧配置 `COGNINOTE_QUERY_CONTEXTUALIZER_ENABLED=false` 时等价于 `OFF`。
4. 全部缺省时为 `AUTO`。

新增配置：

```yaml
app:
  chat:
    query-contextualizer:
      mode: ${COGNINOTE_QUERY_CONTEXTUALIZER_MODE:}
      enabled: ${COGNINOTE_QUERY_CONTEXTUALIZER_ENABLED:true}
```

`enabled` 只作为兼容旧版本的兜底开关，新代码应优先使用 `mode` 或 `/api/chat/settings`。

## 后端改动

- 新增 `QueryContextualizerMode`：`AUTO`、`ALWAYS`、`OFF`。
- 新增 SQLite `app_settings(setting_key, setting_value, updated_at)`，用于保存全局聊天设置。
- 新增普通 JSON API：
  - `GET /api/chat/settings`
  - `PUT /api/chat/settings`
- 新增请求/响应字段：
  - `queryContextualizerMode`: `"AUTO" | "ALWAYS" | "OFF"`
- 新增 `QueryContextualizerTriggerDecider`：
  - 不做精确短语匹配。
  - 通过历史存在、短句、省略/指代/延续信号、完整问题反向信号做轻量打分。
  - 无历史消息时不调用补全 Agent。
  - 完整独立问题默认直接检索。
- 补全 Prompt 输入升级为“会话摘要 + 最近 N 条原文消息 + 当前问题”，压缩会话不会只看最近消息。
- AUTO 模式下，如果原问题检索无来源且存在历史，会允许一次弱检索补全重试。

## 前端改动

- 新增 `chat-settings-api` 和 `chat-settings` Pinia store。
- 在“设置 -> 模型 -> 对话模型”中展示“知识库追问补全策略”。
- 使用单选/分段式控件暴露三个选项：
  - `自动`：推荐。只有像追问或检索较弱时才补全检索问题。
  - `始终`：每轮知识库问答都先判断是否需要补全，准确性更稳但更慢。
  - `关闭`：不补全追问，成本最低，但“继续/给个例子”等追问可能检索不准。
- 页面说明文本明确：
  - 只影响知识库检索 query。
  - 不会修改聊天记录中的用户原文。
  - 不会影响纯模型对话。

## API 示例

读取设置：

```http
GET /api/chat/settings
```

```json
{
  "success": true,
  "code": "OK",
  "message": "OK",
  "data": {
    "queryContextualizerMode": "AUTO"
  },
  "timestamp": 1710000000000
}
```

保存设置：

```http
PUT /api/chat/settings
Content-Type: application/json
```

```json
{
  "queryContextualizerMode": "OFF"
}
```

## Test Plan

- 默认返回 `queryContextualizerMode=AUTO`。
- `PUT /api/chat/settings` 保存 `AUTO/ALWAYS/OFF` 后，`GET` 能正确回显。
- `OFF` 模式不调用 `AiChatRuntime.callText`，检索 query 等于用户原问题。
- `ALWAYS` 模式保持第 21 阶段行为，非法 JSON 仍回退原问题。
- `AUTO` 无历史消息时不调用补全 Agent。
- `AUTO` 完整问题不调用补全 Agent。
- `AUTO` 省略式追问调用补全 Agent，检索 query 包含历史主题。
- 已有摘要时，补全 Prompt 包含摘要和最近消息。
- 前端设置页默认显示“自动”，切换保存后刷新仍能回显。
- `mvn test` 通过。
- `npm --prefix cogniNote-agent-front run build` 通过。

## Assumptions

- 追问补全策略是全局聊天设置，不跟随单个模型配置保存。
- 默认使用 `AUTO`，不是 `ALWAYS`，以符合大上下文窗口时代对延迟和调用成本的要求。
- 本阶段不在前端展示每轮是否触发补全；触发原因先通过后端日志观察。
