package com.itqianchen.agentdesign.service.ai;

public class ChatCompletionIncompleteException extends RuntimeException {

    public ChatCompletionIncompleteException(String message) {
        super(message);
    }
}
