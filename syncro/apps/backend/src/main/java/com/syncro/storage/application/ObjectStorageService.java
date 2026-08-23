package com.syncro.storage.application;

/**
 * Storage boundary for object servers (Garage S3-compatible). Service layer for later stories
 * (8-4 image upload) that keeps the storage vendor out of domain logic.
 *
 * <p>An object is identified by a stable, bucket-relative {@code key}: UTF-8, no leading slash,
 * caller-owned uniqueness within the bucket (e.g. {@code spareparts/{id}/image.png}). Callers
 * must persist only the key — {@link #presignGetUrl} returns a short-TTL temporary GET URL that
 * expires and is bound to the host it was minted for, so it must never be stored.
 */
public interface ObjectStorageService {

  /**
   * Uploads {@code data} under {@code key} and returns the bucket-relative key used for the
   * object reference.
   *
   * @param key         bucket-relative object key (see class doc)
   * @param data        object bytes
   * @param contentType MIME type (e.g. {@code image/jpeg}); required, never blank
   * @return the provided {@code key} on success
   * @throws ObjectStorageException if the upload fails or arguments are invalid
   */
  String store(String key, byte[] data, String contentType);

  /**
   * Generates a short-TTL presigned HTTPS GET URL for {@code key}.
   *
   * @param key bucket-relative object key
   * @return a temporary HTTP GET URL
   * @throws ObjectStorageException if presigning fails
   */
  String presignGetUrl(String key);
}
