# 模型配置指南

CogniNote 通过一个 active 模型配置驱动 Embedding、检索和 RAG 对话。配置保存在本机 SQLite 中，应用启动后无需重启即可读取最新配置。

## Provider 类型

### DashScope

`DASHSCOPE` 用于阿里百炼默认通道。

- 配置页展示的 Base URL：`https://dashscope.aliyuncs.com/api/v1`
- Chat / Embedding 调用：使用 Spring AI Alibaba 原生 DashScope 客户端
- 模型列表：使用百炼兼容 `/models` 端点

DashScope 不允许用户自定义 host。需要自定义 URL 时，应选择 `OPENAI_COMPATIBLE`。

实现约束：DashScope SDK 示例中的 HTTP API Root 是 `https://dashscope.aliyuncs.com/api/v1`。Spring AI Alibaba 的 `DashScopeApi` 内部 path 已包含 `/api/v1/services/...`，因此后端构造 Spring AI Alibaba 客户端时会转换为裸域名 `https://dashscope.aliyuncs.com`，避免拼出重复 `/api/v1`。

### OpenAI-compatible

`OPENAI_COMPATIBLE` 用于通用 OpenAI-compatible 服务。

用户需要填写：

- Base URL
- API Key
- Chat 模型
- Embedding 模型

后端会调用：

```text
Base URL + /models
Base URL + /chat/completions
Base URL + /embeddings
```

如果用户粘贴了完整的 `/chat/completions`、`/embeddings` 或 `/models` 地址，后端会尽量规整回 Base URL。

## 默认值

| 字段 | 默认值 |
| --- | --- |
| Provider | `DASHSCOPE` |
| Chat 模型 | `qwen-plus` |
| Embedding 模型 | `text-embedding-v4` |
| Embedding 维度 | `1024` |
| Temperature | `0.7` |
| Top K | `8` |

## 配置流程

1. 打开“设置”页，切换到“模型”区域。
2. 选择 Provider。
3. 填写 API Key。
4. 如果选择 OpenAI-compatible，填写 Base URL。
5. 点击“获取模型”拉取模型列表。
6. 选择 Chat 模型和 Embedding 模型。
7. 点击“测试连接”确认 Chat 模型可用。
8. 保存配置。

保存后，RAG 对话和 Embedding 网关会读取最新 active 配置。

## API Key 处理

当前开发阶段 API Key 明文保存到：

```text
%APPDATA%\CogniNote\data\cogninote.db
```

这是为了先打通本地闭环的临时取舍。公开发布前应改为 Windows 本地加密或凭据管理。

保存配置时，如果 API Key 留空，后端会复用已保存 key。这样用户只改模型名、Base URL、temperature 或 Top K 时，不需要重新输入密钥。

## 环境变量 fallback

没有 SQLite 模型配置时，Embedding 仍保留 Phase 3 的环境变量 fallback：

```powershell
$env:COGNINOTE_AI_EMBEDDING_PROVIDER="dashscope"
$env:COGNINOTE_DASHSCOPE_API_KEY="your-api-key"
$env:COGNINOTE_EMBEDDING_MODEL="text-embedding-v4"
```

如果 SQLite 中存在 active 配置，优先使用 SQLite。

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
