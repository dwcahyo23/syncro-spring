package com.syncro.storage.infrastructure;

import com.syncro.config.GarageProperties;
import com.syncro.storage.application.ObjectStorageException;
import com.syncro.storage.application.ObjectStorageService;
import java.io.ByteArrayInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * S3/Garage-backed {@link ObjectStorageService}. Uploads bytes and generates short-TTL presigned
 * GET URLs. Raw storage exceptions are wrapped in {@link ObjectStorageException} (never leaked
 * with credential details); the expiry window comes from {@link GarageProperties#presignTtl()}.
 */
@Service
public class GarageObjectStorageService implements ObjectStorageService {

  private static final Logger log = LoggerFactory.getLogger(GarageObjectStorageService.class);

  private final S3Client s3Client;
  private final S3Presigner presigner;
  private final GarageProperties properties;

  public GarageObjectStorageService(
      S3Client s3Client, S3Presigner presigner, GarageProperties properties) {
    this.s3Client = s3Client;
    this.presigner = presigner;
    this.properties = properties;
  }

  @Override
  public String store(String key, byte[] data, String contentType) {
    requireArgument(key != null && !key.isBlank(), "object key must not be blank");
    requireArgument(data != null && data.length > 0, "object data must not be empty");
    requireArgument(contentType != null && !contentType.isBlank(),
        "content type must not be blank");
    try {
      PutObjectRequest request =
          PutObjectRequest.builder()
              .bucket(properties.bucket())
              .key(key)
              .contentType(contentType)
              .build();
      s3Client.putObject(request, RequestBody.fromInputStream(new ByteArrayInputStream(data),
          data.length));
      log.info("Stored object key={} ({} bytes) in bucket={}", key, data.length, properties.bucket());
      return key;
    } catch (Exception exception) {
      log.warn("Failed to store object key={} in bucket={}", key, properties.bucket(), exception);
      throw new ObjectStorageException("Failed to store object: " + key, exception);
    }
  }

  @Override
  public String presignGetUrl(String key) {
    requireArgument(key != null && !key.isBlank(), "object key must not be blank");
    try {
      GetObjectPresignRequest request =
          GetObjectPresignRequest.builder()
              .signatureDuration(properties.presignTtl())
              .getObjectRequest(spec -> spec.bucket(properties.bucket()).key(key))
              .build();
      String url = presigner.presignGetObject(request).url().toString();
      if (log.isDebugEnabled()) {
        log.debug("Presigned GET URL for key={} bucket={}", key, properties.bucket());
      }
      return url;
    } catch (Exception exception) {
      log.warn("Failed to presign GET URL for key={} bucket={}", key, properties.bucket(), exception);
      throw new ObjectStorageException("Failed to presign GET URL: " + key, exception);
    }
  }

  private static void requireArgument(boolean condition, String message) {
    if (!condition) {
      throw new ObjectStorageException(message);
    }
  }
}
