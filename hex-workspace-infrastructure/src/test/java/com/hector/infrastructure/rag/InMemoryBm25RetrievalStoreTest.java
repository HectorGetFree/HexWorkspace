package com.hector.infrastructure.rag;

import com.alibaba.fastjson.JSON;
import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.valobj.DocumentStatusVO;
import com.hector.domain.document.repository.*;
import com.hector.domain.knowledge.model.entity.*;
import com.hector.domain.knowledge.model.valobj.KnowledgeChunkVO;
import com.hector.domain.knowledge.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InMemoryBm25RetrievalStoreTest {
    @Test void coldStartReuseFailureAndConfigIsolation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.store.searchCandidates(1L, 7L, "maple", 30).isEmpty());
            awaitReady(fixture.store);
            var status = fixture.store.prepareAndStatus(1L);
            assertEquals(true, status.get("ready"));
            assertEquals(1, status.get("chunkCount"));
            assertEquals(14L, status.get("sourceChars"));
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("g".getBytes(StandardCharsets.UTF_8))),
                    status.get("generationHash"));
            assertEquals("maple protocol", fixture.store.searchCandidates(1L, 7L, "maple", 30).get(0).content());
            fixture.store.searchCandidates(1L, 7L, "protocol", 30);
            verify(fixture.storage, times(1)).download("chunks");
            when(fixture.storage.download("chunks")).thenReturn("bad checksum".getBytes(StandardCharsets.UTF_8));
            fixture.refreshSynchronouslyExpectFailure();
            assertEquals(1, fixture.store.searchCandidates(1L, 7L, "maple", 30).size());
            assertTrue(fixture.store.searchCandidates(1L, 8L, "maple", 30).isEmpty());
        }
    }
    @Test void incompleteRefreshIsInvisibleAndConcurrentColdQueriesAreCoalesced() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            when(fixture.storage.download("chunks")).thenAnswer(invocation -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return fixture.bytes;
            });
            fixture.store.searchCandidates(1L, 7L, "maple", 30);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                for (int i = 0; i < 10; i++) assertTrue(fixture.store.searchCandidates(1L, 7L, "maple", 30).isEmpty());
                verify(fixture.storage, times(1)).download("chunks");
            } finally { release.countDown(); }
            awaitReady(fixture.store);
        }
    }
    @Test void disabledDoesNotLoadArtifacts() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ReflectionTestUtils.setField(fixture.store, "enabled", false);
            assertTrue(fixture.store.searchCandidates(1L, 7L, "maple", 30).isEmpty());
            fixture.store.refreshKnownWorkspaces();
            verifyNoInteractions(fixture.storage);
            var status = fixture.store.prepareAndStatus(1L);
            assertEquals(false, status.get("enabled"));
            assertEquals(false, status.get("ready"));
            verifyNoInteractions(fixture.storage);
        }
    }
    private static void awaitReady(InMemoryBm25RetrievalStore store) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (store.searchCandidates(1L, 7L, "maple", 30).isEmpty() && System.nanoTime() < end) Thread.sleep(5);
        assertEquals(1, store.searchCandidates(1L, 7L, "maple", 30).size());
    }
    private static class Fixture implements AutoCloseable {
        final InMemoryBm25RetrievalStore store = new InMemoryBm25RetrievalStore();
        final IDocumentStorageRepository storage = mock(IDocumentStorageRepository.class);
        final byte[] bytes;
        Fixture() throws Exception {
            var documents = mock(IDocumentRepository.class);
            var states = mock(IKnowledgeIndexStateRepository.class);
            var generations = mock(IKnowledgeIndexGenerationRepository.class);
            var serving = mock(IServingVectorSpaceRepository.class);
            ReflectionTestUtils.setField(store, "documentRepository", documents);
            ReflectionTestUtils.setField(store, "storage", storage);
            ReflectionTestUtils.setField(store, "states", states);
            ReflectionTestUtils.setField(store, "generations", generations);
            ReflectionTestUtils.setField(store, "serving", serving);
            ReflectionTestUtils.setField(store, "enabled", true);
            when(serving.findServingConfig()).thenReturn(Optional.of(EmbeddingConfigEntity.builder().id(7L).build()));
            when(documents.listByWorkspaceId(1L)).thenReturn(List.of(DocumentEntity.builder().id(2L).workspaceId(1L)
                    .status(DocumentStatusVO.ACTIVE).currentVersionNo(1L).indexInputRevision(1L).build()));
            when(states.listByDocumentIds(List.of(2L))).thenReturn(List.of(KnowledgeIndexStateEntity.builder()
                    .documentId(2L).activeGenerationId("g").build()));
            bytes = JSON.toJSONString(List.of(new KnowledgeChunkVO("g", 2L, 1L, 0, "maple protocol", List.of(),
                    0, 0, null, null, "hash"))).getBytes(StandardCharsets.UTF_8);
            when(generations.listByGenerationIds(List.of("g"))).thenReturn(List.of(KnowledgeIndexGenerationEntity.builder()
                    .generationId("g").workspaceId(1L).documentId(2L).versionNo(1L).indexInputRevision(1L)
                    .embeddingConfigId(7L).status(1).chunkArtifactKey("chunks").chunkCount(1)
                    .chunkArtifactChecksum(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).build()));
            when(storage.download("chunks")).thenReturn(bytes);
        }
        void refreshSynchronouslyExpectFailure() {
            var slots = (Map<?, ?>) ReflectionTestUtils.getField(store, "slots");
            assertThrows(Exception.class, () -> ReflectionTestUtils.invokeMethod(store, "refresh", 1L, slots.get(1L)));
        }
        public void close() { store.close(); }
    }
}
