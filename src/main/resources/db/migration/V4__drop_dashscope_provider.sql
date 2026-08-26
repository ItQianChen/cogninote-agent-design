UPDATE model_configs
SET provider = 'OPENAI_COMPATIBLE',
    display_name = CASE
        WHEN display_name LIKE 'DashScope%' THEN REPLACE(display_name, 'DashScope', 'OpenAI-compatible')
        ELSE display_name
    END,
    base_url = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
WHERE provider = 'DASHSCOPE';

ALTER TABLE model_configs
ADD COLUMN reasoning_effort TEXT NOT NULL DEFAULT 'NONE';

ALTER TABLE chat_messages
ADD COLUMN reasoning_content TEXT;
