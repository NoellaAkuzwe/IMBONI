package dev.noella.imboni.doc;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("!pg")
public class InMemoryDocumentStore implements DocumentStore {

    private final Map<String, Document> docs = new ConcurrentHashMap<>();

    @Override
    public Document save(Document d) {
        docs.put(d.id(), d);
        return d;
    }

    @Override
    public Optional<Document> findForOwner(String id, String ownerId) {
        return Optional.ofNullable(docs.get(id)).filter(d -> d.ownerId().equals(ownerId));
    }

    @Override
    public Optional<Document> findById(String id) {
        return Optional.ofNullable(docs.get(id));
    }

    @Override
    public Optional<Document> findByOwnerAndHash(String ownerId, String contentHash) {
        return docs.values().stream()
                .filter(d -> d.ownerId().equals(ownerId) && d.contentHash().equals(contentHash))
                .findFirst();
    }

    @Override
    public List<Document> listForOwner(String ownerId) {
        return docs.values().stream()
                .filter(d -> d.ownerId().equals(ownerId))
                .sorted(Comparator.comparing(Document::createdAt))
                .toList();
    }
}
