package com.itqianchen.agentdesign.controller.ocr;

import com.itqianchen.agentdesign.common.api.ApiResponse;
import com.itqianchen.agentdesign.domain.dto.ocr.OcrSettingsRequest;
import com.itqianchen.agentdesign.domain.dto.ocr.OcrSettingsResponse;
import com.itqianchen.agentdesign.domain.dto.ocr.OcrTestResponse;
import com.itqianchen.agentdesign.service.ocr.OcrSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OCR 设置控制器。
 *
 * <p>设置页允许明文读取本机密钥；测试接口和其他业务接口不返回密钥。</p>
 */
@RestController
@RequestMapping("/api/ocr")
public class OcrSettingsController {

    private final OcrSettingsService ocrSettingsService;

    public OcrSettingsController(OcrSettingsService ocrSettingsService) {
        this.ocrSettingsService = ocrSettingsService;
    }

    @GetMapping("/settings")
    public ApiResponse<OcrSettingsResponse> settings() {
        return ApiResponse.ok(ocrSettingsService.settings());
    }

    @PutMapping("/settings")
    public ApiResponse<OcrSettingsResponse> update(@Valid @RequestBody OcrSettingsRequest request) {
        return ApiResponse.ok(ocrSettingsService.update(request));
    }

    @PostMapping("/test")
    public ApiResponse<OcrTestResponse> test() {
        return ApiResponse.ok(ocrSettingsService.test());
    }
}
