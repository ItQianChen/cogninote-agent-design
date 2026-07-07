package com.itqianchen.agentdesign.service.ocr;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.itqianchen.agentdesign.domain.enums.ocr.BaiduOcrRecognitionMode;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 百度 OCR 官方 HTTP 接口客户端。
 *
 * <p>所有异常在这里脱敏，不把 query 中的 client_secret 或 access_token 继续向上抛。</p>
 */
@Component
public class DefaultBaiduOcrClient implements BaiduOcrClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultBaiduOcrClient.class);
    private static final URI TOKEN_URI = URI.create("https://aip.baidubce.com/oauth/2.0/token");
    private static final URI STANDARD_OCR_URI =
            URI.create("https://aip.baidubce.com/rest/2.0/ocr/v1/general_basic");
    private static final URI ACCURATE_OCR_URI =
            URI.create("https://aip.baidubce.com/rest/2.0/ocr/v1/accurate_basic");

    private final ConcurrentMap<Integer, RestClient> restClientsByTimeoutSeconds = new ConcurrentHashMap<>();

    @Override
    public BaiduAccessToken fetchAccessToken(String apiKey, String secretKey, int timeoutSeconds) {
        try {
            URI uri = UriComponentsBuilder.fromUri(TOKEN_URI)
                    .queryParam("grant_type", "client_credentials")
                    .queryParam("client_id", apiKey)
                    .queryParam("client_secret", secretKey)
                    .build()
                    .toUri();
            BaiduTokenApiResponse response = restClient(timeoutSeconds)
                    .post()
                    .uri(uri)
                    .retrieve()
                    .body(BaiduTokenApiResponse.class);
            if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
                log.warn("baidu_ocr_token_failed errorCode={}", response == null ? "empty" : response.error());
                throw new OcrProviderException("百度 OCR 鉴权失败，请检查 API Key、Secret Key 或服务状态。");
            }
            return new BaiduAccessToken(response.accessToken(), response.expiresIn() == null
                    ? 2_592_000L
                    : response.expiresIn());
        } catch (RestClientException ex) {
            log.warn("baidu_ocr_token_request_failed errorType={}", ex.getClass().getSimpleName());
            throw new OcrProviderException("百度 OCR 鉴权失败，请检查网络、密钥或服务额度。");
        }
    }

    @Override
    public BaiduOcrRecognitionResponse recognizeImage(BaiduOcrRecognitionRequest request) {
        try {
            URI uri = UriComponentsBuilder.fromUri(endpointFor(request.recognitionMode()))
                    .queryParam("access_token", request.accessToken())
                    .build()
                    .toUri();
            MultiValueMap<String, String> form = formFor(request);
            BaiduOcrApiResponse response = restClient(request.timeoutSeconds())
                    .post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(BaiduOcrApiResponse.class);
            if (response == null) {
                return new BaiduOcrRecognitionResponse(-1, "empty_response", List.of());
            }
            return new BaiduOcrRecognitionResponse(
                    response.errorCode(),
                    response.errorMessage(),
                    words(response)
            );
        } catch (RestClientException ex) {
            log.warn("baidu_ocr_recognition_request_failed mode={} errorType={}",
                    request.recognitionMode(),
                    ex.getClass().getSimpleName()
            );
            throw new OcrProviderException("百度 OCR 识别调用失败，请检查网络、额度或接口权限。");
        }
    }

    static MultiValueMap<String, String> formFor(BaiduOcrRecognitionRequest request) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("image", request.imageBase64());
        form.add("detect_direction", Boolean.toString(request.detectDirection()));
        form.add("language_type", request.languageType());
        return form;
    }

    private RestClient restClient(int timeoutSeconds) {
        int normalizedTimeoutSeconds = Math.clamp(timeoutSeconds, 3, 120);
        return restClientsByTimeoutSeconds.computeIfAbsent(normalizedTimeoutSeconds,
                DefaultBaiduOcrClient::buildRestClient);
    }

    private static RestClient buildRestClient(int timeoutSeconds) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    private static URI endpointFor(BaiduOcrRecognitionMode mode) {
        return mode == BaiduOcrRecognitionMode.ACCURATE ? ACCURATE_OCR_URI : STANDARD_OCR_URI;
    }

    private static List<String> words(BaiduOcrApiResponse response) {
        if (response.wordsResult() == null) {
            return List.of();
        }
        return response.wordsResult().stream()
                .map(BaiduOcrWordResult::words)
                .filter(word -> word != null && !word.isBlank())
                .toList();
    }

    private record BaiduTokenApiResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Long expiresIn,
            String error,
            @JsonProperty("error_description") String errorDescription
    ) {
    }

    private record BaiduOcrApiResponse(
            @JsonProperty("error_code") Integer errorCode,
            @JsonProperty("error_msg") String errorMessage,
            @JsonProperty("words_result") List<BaiduOcrWordResult> wordsResult
    ) {
    }

    private record BaiduOcrWordResult(String words) {
    }
}
