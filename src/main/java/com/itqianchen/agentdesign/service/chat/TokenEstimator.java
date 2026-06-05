package com.itqianchen.agentdesign.service.chat;

import org.springframework.stereotype.Component;

@Component
public class TokenEstimator {

    public int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        /*
         * 本地应用不绑定具体 tokenizer。这里用保守估算：
         * 中文约 1 字 1 token，英文约 4 字符 1 token，取字符数的一半作为混合文本近似。
         * 预算只用于选择历史消息窗口，不作为计费或精确上下文长度。
         */
        return Math.max(1, (int) Math.ceil(text.codePointCount(0, text.length()) / 2.0));
    }
}
