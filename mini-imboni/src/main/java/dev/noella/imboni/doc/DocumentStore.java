package dev.noella.imboni.doc;

import java.util.List;
import java.util.Optional;

public interface DocumentStore {

    Document save(Document d);

    /** Only returns the document if {@code ownerId} owns it. Callers never see other teachers' rows. */
    Optional<Document> findForOwner(String id, String ownerId);

    /** Internal lookup for the background worker (no owner check: the worker acts on a known job). */
    Optional<Document> findById(String id);

    Optional<Document> findByOwnerAndHash(String ownerId, String contentHash);

    List<Document> listForOwner(String ownerId);
}
