# 模型配置指南

CogniNote 使用多模型配置中心。对话、Embedding 与视觉识别模型独立维护、独立激活：RAG 回答和内部 Agent 使用 active `CHAT` 配置，向量索引和向量检索使用 active `EMBEDDING` 配置，模型 OCR 使用 active `VISION` 配置。

配置保存在本机 SQLite 的 `model_configs` 表中，应用启动后无需重启即可读取最新 active 配置。旧的单行 `model_config` 会在启动时自动拆分为多条角色配置。

所有模型调用统一通过 Spring AI OpenAI Runtime 与 `OPENAI_COMPATIBLE` 协议执行。`ModelProvider` 和 `ModelRuntimeFactory` 仍保留为未来协议扩展边界，但当前不再提供 DashScope 专用 Provider、原生 API 调用链或 Spring AI Alibaba 运行时。

## Provider 类型

当前唯一的 Provider 为 `OPENAI_COMPATIBLE`。每一条配置都需要填写：

- Base URL
- API Key
- 模型 ID

后端按同一个 Base URL 调用标准接口：

```text
Base URL + /models
Base URL + /chat/completions
Base URL + /embeddings
```

Base URL 可指向任何实现上述 OpenAI-compatible 协议的服务。默认值为百炼兼容端点 `https://dashscope.aliyuncs.com/compatible-mode/v1`；它只是一个可替换的兼容服务地址，不是独立 Provider。若粘贴完整的 `/chat/completions`、`/embeddings` 或 `/models` 地址，后端会尽量规整回 Base URL。

Embedding 始终使用标准 `/embeddings` 请求。CogniNote 不会发送 DashScope 原生 `text_type` 等非标准参数；内部仍保留 `embedDocuments` / `embedQuery` 两个入口，以便将来有协议确实支持文档与查询语义时扩展。

已有数据库中的 `DASHSCOPE` 配置会在 V4 Flyway 迁移中自动改写为 `OPENAI_COMPATIBLE`，Base URL 改为上述兼容端点，名称中的 `DashScope` 也会改为 `OpenAI-compatible`。这是一次单向迁移，旧原生 DashScope 调用不再保留。

## 配置类型

| 类型 | 用途 | 主要字段 |
| --- | --- | --- |
| `CHAT` | RAG 流式回答、连接测试、内部 Agent、聊天上下文预算 | 模型 ID、Temperature、默认 Top K、上下文窗口、推理等级 |
| `EMBEDDING` | 文档向量化、向量检索、混合检索 | 模型 ID、Embedding 维度、RPM、TPM、Batch |
| `VISION` | 图片输入与模型 OCR | 模型 ID、Temperature |

每个类型可以保存多条配置，但同一时间只有一条 active 配置。激活一个角色的配置不会覆盖其他角色。

## 默认值

| 类型 | 字段 | 默认值 |
| --- | --- | --- |
| Chat | Provider | `OPENAI_COMPATIBLE` |
| Chat | Base URL | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| Chat | 模型 | `qwen-plus` |
| Chat | Temperature | `0.7` |
| Chat | Top K | `8` |
| Chat | 上下文窗口 | `128000`（前端显示 `128K`） |
| Chat | 推理等级 | `NONE` |
| 知识库设置 | 追问补全策略 | `AUTO`（前端显示“自动”） |
| Embedding | Provider | `OPENAI_COMPATIBLE` |
| Embedding | 模型 | `text-embedding-v4` |
| Embedding | 维度 | `1024` |
| Embedding | 请求限速 | `300 RPM / 300000 TPM / batch 16` |

Embedding 请求限速预设：

| 档位 | RPM | TPM | Batch | 适用场景 |
| --- | ---: | ---: | ---: | --- |
| 保守 | `60` | `100000` | `8` | 免费、试用或不确定配额的账号 |
| 标准 | `300` | `300000` | `16` | 默认档位，适合多数兼容服务 |
| 快速 | `1000` | `800000` | `32` | 明确知道账号配额较高，且需要更快索引 |
| 自定义 | 用户填写 | 用户填写 | 用户填写 | 按供应商控制台额度填写 |

后端校验范围：RPM `1` 到 `10000`，TPM `1000` 到 `10000000`，Batch `1` 到 `128`。Batch 越大越省 RPM，但单次输入 token 更多，仍受 TPM 和单请求限制约束。

## 推理等级与思考内容

`reasoningEffort` 仅用于 `CHAT` 配置，可选值为 `NONE`、`LOW`、`MEDIUM`、`HIGH`、`XHIGH`、`MAX`，默认 `NONE`。

- `NONE`：运行时透传 `enable_thinking=false`。
- 其他等级：运行时透传小写 `reasoning_effort` 与 `enable_thinking=true`。
- 不同服务对这些字段的支持度不同；不支持时可选择 `NONE`，或按服务商兼容性说明调整。

流式响应中的推理内容通过独立 `reasoning` SSE 事件返回，不与正常回答 `delta` 混合。前端在生成期间显示可展开的“思考中…”，完成后收起为“思考过程”。最终回答写入 `chat_messages.content`，推理内容单独写入 `chat_messages.reasoning_content`；会话记忆只把最终回答送回模型上下文，避免推理文本污染下一轮对话。

## 配置流程

1. 打开“设置 -> 模型”。
2. 在“对话模型”“Embedding 模型”或“视觉识别模型”之间切换。
3. 点击“新建配置”，填写 Base URL、API Key 和模型 ID。
4. Chat 模型可设置 Temperature、默认 Top K、上下文窗口和推理等级。
5. Embedding 模型可在“请求限速”里选择预设或自定义 RPM、TPM 和 Batch。
6. 点开“模型 ID”下拉框获取候选模型；列表不完整时可直接手动输入模型 ID。
7. 点击“测试连接”验证当前草稿，保存后在配置列表中设为启用。

## 模型列表与索引重建

前端会用当前表单的 role、Base URL 和 API Key 调用 `POST /api/model-configs/models` 拉取模型列表。模型列表仅用于辅助选择和排序；获取失败或返回不完整时，仍可以手动输入模型 ID 保存。

已启用的 Embedding 配置如果修改了 Base URL、模型 ID 或向量维度，需要在知识库中手动重建索引。系统不会自动重建旧向量，避免用户不知情地产生大量外部模型调用。仅改名称、API Key、RPM、TPM 或 Batch 不会触发重建提示。

## API Key 处理

当前开发阶段 API Key 明文保存在：

```text
%APPDATA%\CogniNote\data\cogninote.db
```

这是为了打通本地闭环的临时取舍。公开发布前应替换为操作系统凭据管理或本地加密。编辑既有配置时留空 API Key，后端会复用已保存的 key。

## 常见问题

### 连接测试或模型调用返回 URL 错误

确认填写的是兼容服务的 **Base URL**，而不是重复追加了 `/chat/completions` 的地址。即使粘贴完整 endpoint，后端也会尝试归一化；如果服务使用非标准路径，请填写该服务文档要求的 API 根地址。

### 获取模型失败

并非所有兼容服务都完整实现 `/models`。获取失败或列表缺少可用模型时，直接手动输入模型 ID 后保存即可；实际 Chat、Embedding 和 Vision 调用依赖最终保存的模型名。

### Embedding 不可用或被限流

Embedding 不可用会影响向量索引、向量检索和混合检索；RAG 在 `HYBRID` 或 `VECTOR` 失败时会尝试降级到 `KEYWORD`。遇到 429、rate limit、TPM limit 或 RPM limit 时，先按供应商配额下调 RPM、TPM 或 Batch，再在知识库中执行“补写索引”。
