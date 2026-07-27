package com.itqianchen.agentdesign.service.knowledge;

/** payload 缺失、损坏或版本不受支持时阻止任务被猜测性重放。 */
public class UnsupportedMaintenancePayloadException extends RuntimeException {

    public UnsupportedMaintenancePayloadException(String message) {
        super(message);
    }

    public UnsupportedMaintenancePayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
