package dev.noella.imboni.doc;

/** PENDING → PROCESSING → READY, or FAILED (retryable). Only READY documents are searchable. */
public enum DocumentStatus { PENDING, PROCESSING, READY, FAILED }
