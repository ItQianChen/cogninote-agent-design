package com.itqianchen.agentdesign.domain.dto.system;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 桌面壳已复制到受控 inbox 的恢复包标识。 */
public record RestorePreflightRequest(
        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F-]{36}$")
        String importId
) {
}
