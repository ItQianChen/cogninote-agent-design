package com.itqianchen.agentdesign.domain.exception.storage;

/** 备份或恢复请求无法安全完成时抛出的业务异常。 */
public class DataProtectionException extends RuntimeException {

    private final Reason reason;

    public DataProtectionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DataProtectionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_PACKAGE,
        CONFLICT,
        NOT_FOUND,
        INSUFFICIENT_STORAGE,
        IO_FAILURE
    }
}
