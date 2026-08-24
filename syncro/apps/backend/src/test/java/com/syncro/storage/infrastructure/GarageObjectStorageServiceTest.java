package com.syncro.storage.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.syncro.config.GarageProperties;
import com.syncro.storage.application.ObjectStorageException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

@ExtendWith(MockitoExtension.class)
class GarageObjectStorageServiceTest {

  private static final GarageProperties PROPERTIES =
      new GarageProperties(
          "http://localhost:3900", "access-key", "secret-key", "syncro-spareparts", "garage", 300L);

  @Mock
  private S3Client s3Client;

  @Mock
  private S3Presigner presigner;

  private GarageObjectStorageService service;

  @BeforeEach
  void setUp() {
    service = new GarageObjectStorageService(s3Client, presigner, PROPERTIES);
  }

  @Test
  void store_uploadsObjectWithConfiguredBucketKeyAndContentType_andReturnsKey() {
    byte[] data = "image-bytes".getBytes();

    String stored = service.store("img/sparepart-1.png", data, "image/png");

    ArgumentCaptor<PutObjectRequest> requestCaptor =
        ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
    PutObjectRequest request = requestCaptor.getValue();
    assertThat(request.bucket()).isEqualTo("syncro-spareparts");
    assertThat(request.key()).isEqualTo("img/sparepart-1.png");
    assertThat(request.contentType()).isEqualTo("image/png");
    assertThat(stored).isEqualTo("img/sparepart-1.png");
  }

  @Test
  void store_rejectsBlankKeyDataOrContentType_withoutTouchingClient() {
    assertThatThrownBy(() -> service.store(" ", new byte[] {1}, "image/png"))
        .isInstanceOf(ObjectStorageException.class);
    assertThatThrownBy(() -> service.store("img/a.png", new byte[0], "image/png"))
        .isInstanceOf(ObjectStorageException.class);
    assertThatThrownBy(() -> service.store("img/a.png", new byte[] {1}, " "))
        .isInstanceOf(ObjectStorageException.class);
    assertThatThrownBy(() -> service.presignGetUrl(null))
        .isInstanceOf(ObjectStorageException.class);
    verifyNoInteractions(s3Client);
    verifyNoInteractions(presigner);
  }

  @Test
  void store_wrapsClientFailureInObjectStorageException() {
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(RuntimeException.class);

    assertThatThrownBy(() -> service.store("img/a.png", new byte[] {1}, "image/png"))
        .isInstanceOf(ObjectStorageException.class)
        .hasMessageContaining("img/a.png")
        .hasCauseInstanceOf(RuntimeException.class);
  }

  @Test
  void presignGetUrl_requestsConfiguredTtlForConfiguredBucketAndKey_andReturnsUrl()
      throws Exception {
    PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
    when(presigned.url())
        .thenReturn(URI.create(
            "https://localhost:3900/syncro-spareparts/img/sparepart-1.png?X-Amz-Expires=300")
            .toURL());
    when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

    String url = service.presignGetUrl("img/sparepart-1.png");

    ArgumentCaptor<GetObjectPresignRequest> requestCaptor =
        ArgumentCaptor.forClass(GetObjectPresignRequest.class);
    verify(presigner).presignGetObject(requestCaptor.capture());
    GetObjectPresignRequest request = requestCaptor.getValue();
    assertThat(request.signatureDuration()).isEqualTo(Duration.ofSeconds(300));
    assertThat(request.getObjectRequest().bucket()).isEqualTo("syncro-spareparts");
    assertThat(request.getObjectRequest().key()).isEqualTo("img/sparepart-1.png");

    assertThat(url).contains("/syncro-spareparts/img/sparepart-1.png").contains("X-Amz-Expires=300");
  }

  @Test
  void presignGetUrl_wrapsPresignerFailureInObjectStorageException() {
    when(presigner.presignGetObject(any(GetObjectPresignRequest.class)))
        .thenThrow(new IllegalStateException("boom"));

    assertThatThrownBy(() -> service.presignGetUrl("img/a.png"))
        .isInstanceOf(ObjectStorageException.class)
        .hasMessageContaining("img/a.png")
        .hasCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void delete_deletesObjectWithConfiguredBucketAndKey() {
    service.delete("img/sparepart-1.png");

    ArgumentCaptor<DeleteObjectRequest> requestCaptor =
        ArgumentCaptor.forClass(DeleteObjectRequest.class);
    verify(s3Client).deleteObject(requestCaptor.capture());
    DeleteObjectRequest request = requestCaptor.getValue();
    assertThat(request.bucket()).isEqualTo("syncro-spareparts");
    assertThat(request.key()).isEqualTo("img/sparepart-1.png");
  }

  @Test
  void delete_rejectsBlankKey() {
    assertThatThrownBy(() -> service.delete(" "))
        .isInstanceOf(ObjectStorageException.class);
    verifyNoInteractions(s3Client);
  }

  @Test
  void delete_wrapsClientFailureInObjectStorageException() {
    when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
        .thenThrow(RuntimeException.class);

    assertThatThrownBy(() -> service.delete("img/a.png"))
        .isInstanceOf(ObjectStorageException.class)
        .hasMessageContaining("img/a.png")
        .hasCauseInstanceOf(RuntimeException.class);
  }
}
