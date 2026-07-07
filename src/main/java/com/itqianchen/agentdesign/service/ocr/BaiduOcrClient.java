package com.itqianchen.agentdesign.service.ocr;

/**
 * 百度 OCR HTTP 客户端边界。
 *
 * <p>单元测试通过 fake client 覆盖 token、识别和异常分支，避免真实网络和真实密钥。</p>
 */
public interface BaiduOcrClient {

    BaiduAccessToken fetchAccessToken(String apiKey, String secretKey, int timeoutSeconds);

    BaiduOcrRecognitionResponse recognizeImage(BaiduOcrRecognitionRequest request);
}
