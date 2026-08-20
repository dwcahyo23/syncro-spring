package com.syncro.notification.infrastructure;

import com.syncro.config.WahaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WahaClient {

  private static final Logger log = LoggerFactory.getLogger(WahaClient.class);
  private static final int MAX_DETAIL_LENGTH = 512;

  private final RestClient restClient;

  public WahaClient(WahaProperties wahaProperties) {
    this.restClient = RestClient.builder()
        .baseUrl(wahaProperties.url())
        .defaultHeader("X-Api-Key", wahaProperties.apiKey())
        .build();
  }

  public Result send(String recipientPhone, String messageText, String traceId) {
    var payload = new SendTextRequest(recipientPhone + "@c.us", messageText, "default");
    try {
      ResponseEntity<String> response = restClient.post()
          .uri("/api/sendText")
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .retrieve()
          .onStatus(HttpStatusCode::isError, (req, res) -> {
            // suppress exception — handle via status code in result
          })
          .toEntity(String.class);

      boolean success = response.getStatusCode().is2xxSuccessful();
      String detail = truncate(response.getBody());
      log.info("[WAHA][traceId={}] send attempt phone=*** status={}", traceId,
          response.getStatusCode().value());
      return new Result(success, response.getStatusCode().value(), detail);
    } catch (Exception e) {
      log.error("[WAHA][traceId={}] send failed: {}", traceId, e.getMessage());
      return new Result(false, 0, truncate(e.getMessage()));
    }
  }

  private static String truncate(String value) {
    if (value == null) {
      return null;
    }
    return value.length() > MAX_DETAIL_LENGTH ? value.substring(0, MAX_DETAIL_LENGTH) : value;
  }

  public record Result(boolean success, int httpStatus, String detail) {
  }

  record SendTextRequest(String chatId, String text, String session) {
  }
}
