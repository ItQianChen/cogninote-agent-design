# 模型配置指南

CogniNote 第八阶段开始使用多模型配置中心。对话模型和 Embedding 模型独立维护、独立激活：RAG 回答使用 active `CHAT` 配置，向量索引和向量检索使用 active `EMBEDDING` 配置。

配置保存在本机 SQLite 的 `model_configs` 表中，应用启动后无需重启即可读取最新 active 配置。旧的单行 `model_config` 会在启动时自动拆成 Chat 和 Embedding 两条配置。

## Provider 类型

### DashScope

`DASHSCOPE` 用于阿里百炼默认通道。

- 配置页展示的 Base URL：`https://dashscope.aliyuncs.com/api/v1`
- Chat / Embedding 调用：使用 Spring AI Alibaba 原生 DashScope 客户端
- 模型列表：使用百炼兼容 `/models` 端点

DashScope 不允许用户自定义 host。需要自定义 URL 时，应选择 `OPENAI_COMPATIBLE`。

实现约束：DashScope SDK 示例中的 HTTP API Root 是 `https://dashscope.aliyuncs.com/api/v1`。Spring AI Alibaba 的 `DashScopeApi` 内部 path 已包含 `/api/v1/services/...`，因此后端构造客户端时会转换为裸域名 `https://dashscope.aliyuncs.com`，避免拼出重复 `/api/v1`。

### OpenAI-compatible

`OPENAI_COMPATIBLE` 用于通用 OpenAI-compatible 服务。

用户需要填写：

- Base URL
- API Key
- 模型 ID

后端会调用：

```text
Base URL + /models
Base URL + /chat/completions
Base URL + /embeddings
```

如果用户粘贴了完整的 `/chat/completions`、`/embeddings` 或 `/models` 地址，后端会尽量规整回 Base URL。

## 配置类型

| 类型 | 用途 | 主要字段 |
| --- | --- | --- |
| `CHAT` | RAG 流式回答、连接测试 | 模型 ID、Temperature、默认 Top K |
| `EMBEDDING` | 文档向量化、向量检索、混合检索 | 模型 ID、Embedding 维度 |

每个类型可以保存多条配置，但同一时间只有一条 active 配置。激活 Chat 配置不会覆盖 Embedding 配置，反之亦然。

## 默认值

| 类型 | 字段 | 默认值 |
| --- | --- | --- |
| Chat | Provider | `DASHSCOPE` |
| Chat | 模型 | `qwen-plus` |
| Chat | Temperature | `0.7` |
| Chat | Top K | `8` |
| Embedding | Provider | `DASHSCOPE` |
| Embedding | 模型 | `text-embedding-v4` |
| Embedding | 维度 | `1024` |

## 配置流程

1. 打开“设置”页，切换到“模型”区域。
2. 在“对话模型”或“Embedding 模型”之间切换。
3. 点击“新建配置”，填写 Provider、Base URL、API Key 和模型 ID。
4. 点击“获取模型”拉取候选模型；如果服务商模型列表不完整，可直接手动输入模型 ID。
5. 点击“测试连接”验证当前配置草稿。
6. 保存配置。
7. 在配置列表中点击“激活”让该配置成为当前类型的 active 配置。

保存或激活 Embedding 配置后，如果模型或维度发生变化，需要在知识库中手动重建索引。系统不会自动重建旧向量，避免用户不知情地产生大量外部模型调用。

## API Key 处理

当前开发阶段 API Key 明文保存到：

```text
%APPDATA%\CogniNote\data\cogninote.db
```

这是为了先打通本地闭环的临时取舍。公开发布前应改为 Windows 本地加密或凭据管理。

保存配置时，如果 API Key 留空，后端会复用该配置已保存的 key。这样用户只改模型名、Base URL、temperature、Top K 或维度时，不需要重新输入密钥。

## 环境变量 fallback

没有 SQLite Embedding 配置或 active Embedding 配置没有 API Key 时，Embedding 仍保留 Phase 3 的环境变量 fallback：

```powershell
$env:COGNINOTE_AI_EMBEDDING_PROVIDER="dashscope"
$env:COGNINOTE_DASHSCOPE_API_KEY="your-api-key"
$env:COGNINOTE_EMBEDDING_MODEL="text-embedding-v4"
```

如果 SQLite 中存在 active Embedding 配置并填写了 API Key，优先使用 SQLite。

## 常见问题

### DashScope 连接测试返回 url error

通常是把自定义 OpenAI-compatible URL 配到了 DashScope Provider，或者把 DashScope 的 `/api/v1` 地址直接传给了不该接收它的客户端。

处理方式：

- 阿里百炼选择 `DASHSCOPE`，使用默认地址。
- 自定义网关、OpenAI-compatible 服务选择 `OPENAI_COMPATIBLE`。

### 获取模型失败

模型列表接口并不是所有服务都实现完整。如果获取失败，可以手动输入模型 ID 后保存。Chat 调用和 Embedding 调用只依赖最终保存的模型名。

### Embedding 不可用

Embedding 不可用会影响向量索引、向量检索和混合检索。RAG 对话在 `HYBRID` 或 `VECTOR` 失败时会尝试降级到 `KEYWORD`，并在 SSE `meta.retrievalMode` 中返回实际检索模式。
