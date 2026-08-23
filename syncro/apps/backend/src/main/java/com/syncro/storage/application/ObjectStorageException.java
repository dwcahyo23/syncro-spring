package com.syncro.storage.application;

/**
 * Raised when an object-storage operation fails. Carries no credential material and is safe to
 * surface at an API boundary or log.
 */
public class ObjectStorageException extends RuntimeException {

  public ObjectStorageException(String message) {
    super(message);
  }

  public ObjectStorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
