package org.ekstep.ep.samza.task;

import com.google.gson.Gson;
import org.apache.samza.config.Config;
import org.apache.samza.metrics.Counter;
import org.apache.samza.metrics.MetricsRegistry;
import org.apache.samza.storage.kv.KeyValueStore;
import org.apache.samza.system.IncomingMessageEnvelope;
import org.apache.samza.system.OutgoingMessageEnvelope;
import org.apache.samza.system.SystemStream;
import org.apache.samza.task.MessageCollector;
import org.apache.samza.task.TaskContext;
import org.apache.samza.task.TaskCoordinator;
import org.ekstep.ep.samza.cache.CacheEntry;
import org.ekstep.ep.samza.fixture.ContentFixture;
import org.ekstep.ep.samza.fixture.EventFixture;
import org.ekstep.ep.samza.search.domain.Content;
import org.ekstep.ep.samza.search.service.SearchServiceClient;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static junit.framework.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.*;

public class ObjectDeNormalizationTaskTest {

    private static final String SUCCESS_TOPIC = "telemetry.content.de_normalized";
    private static final String FAILED_TOPIC = "telemetry.content.de_normalized.fail";
    private static final String CONTENT_CACHE_TTL = "60000";
    private MessageCollector collectorMock;
    private SearchServiceClient searchServiceMock;
    private TaskContext contextMock;
    private MetricsRegistry metricsRegistry;
    private Counter counter;
    private TaskCoordinator coordinatorMock;
    private IncomingMessageEnvelope envelopeMock;
    private Config configMock;
    private ObjectDeNormalizationTask contentDeNormalizationTask;
    private KeyValueStore objectStoreMock;

    @Before
    public void setUp() {
        collectorMock = mock(MessageCollector.class);
        searchServiceMock = mock(SearchServiceClient.class);
        contextMock = Mockito.mock(TaskContext.class);
        metricsRegistry = Mockito.mock(MetricsRegistry.class);
        counter = Mockito.mock(Counter.class);
        coordinatorMock = mock(TaskCoordinator.class);
        envelopeMock = mock(IncomingMessageEnvelope.class);
        configMock = Mockito.mock(Config.class);
        objectStoreMock = mock(KeyValueStore.class);

        stub(configMock.get("output.success.topic.name", SUCCESS_TOPIC)).toReturn(SUCCESS_TOPIC);
        stub(configMock.get("output.failed.topic.name", FAILED_TOPIC)).toReturn(FAILED_TOPIC);
        stub(configMock.get("gid.overridden.events", "")).toReturn("me.gid.field");
        stub(configMock.get("me.gid.field", "")).toReturn("dimensions.content_id");
        stub(configMock.containsKey("me.gid.field")).toReturn(true);
        stub(configMock.get("object.store.ttl", "60000")).toReturn(CONTENT_CACHE_TTL);
        stub(metricsRegistry.newCounter(anyString(), anyString()))
                .toReturn(counter);
        stub(contextMock.getMetricsRegistry()).toReturn(metricsRegistry);

        contentDeNormalizationTask = new ObjectDeNormalizationTask(configMock, contextMock, searchServiceMock, objectStoreMock);
    }

    @Test
    public void shouldSkipIfObjectIdIsBlank() throws Exception {
        stub(envelopeMock.getMessage()).toReturn(EventFixture.EventWithEmptyObjectID());

        contentDeNormalizationTask.process(envelopeMock, collectorMock, coordinatorMock);
        verify(collectorMock).send(argThat(validateOutputTopic(envelopeMock.getMessage(), SUCCESS_TOPIC)));
    }

    @Test
    public void shouldProcessEventFromCacheIfPresentAndSkipServiceCall() throws Exception {
        stub(envelopeMock.getMessage()).toReturn(EventFixture.OeEvent());

        CacheEntry contentCache = new CacheEntry(ContentFixture.getContent(), new Date().getTime());
        String contentCacheJson = new Gson().toJson(contentCache, CacheEntry.class);

        stub(objectStoreMock.get(ContentFixture.getContentCacheKey())).toReturn(contentCacheJson);

        contentDeNormalizationTask.process(envelopeMock, collectorMock, coordinatorMock);

        verify(objectStoreMock, times(1)).get(ContentFixture.getContentCacheKey());
        verify(searchServiceMock, times(0)).searchContent(ContentFixture.getContentID());
        verify(collectorMock).send(argThat(validateOutputTopic(envelopeMock.getMessage(), SUCCESS_TOPIC)));
    }

    @Test
    public void shouldCallSearchApiAndUpdateCacheIfEventIsNotPresentInCache() throws Exception {
        stub(envelopeMock.getMessage()).toReturn(EventFixture.OeEvent());
        when(objectStoreMock.get(ContentFixture.getContentID())).thenReturn(null, getContentCacheJson());

        stub(searchServiceMock.searchContent(ContentFixture.getContentID())).toReturn(ContentFixture.getContent());

        contentDeNormalizationTask.process(envelopeMock, collectorMock, coordinatorMock);

        verify(objectStoreMock, times(2)).get(ContentFixture.getContentCacheKey());
        verify(searchServiceMock, times(1)).searchContent(ContentFixture.getContentID());
        verify(objectStoreMock, times(1)).put(anyString(), anyString());
        verify(collectorMock).send(argThat(validateOutputTopic(envelopeMock.getMessage(), SUCCESS_TOPIC)));
    }

    @Test
    public void shouldCallSearchApiAndUpdateCacheIfCacheIsExpired() throws Exception {

        CacheEntry contentCacheExpired = new CacheEntry(ContentFixture.getContent(), new Date().getTime() - 100000);
        String contentCacheExpiredJson = new Gson().toJson(contentCacheExpired, CacheEntry.class);

        CacheEntry contentCacheNew = new CacheEntry(ContentFixture.getContent(), new Date().getTime() + 100000);
        String contentCacheNewJson = new Gson().toJson(contentCacheNew, CacheEntry.class);

        when(objectStoreMock.get(ContentFixture.getContentCacheKey())).thenReturn(contentCacheExpiredJson,contentCacheNewJson);
        stub(envelopeMock.getMessage()).toReturn(EventFixture.OeEvent());
        stub(searchServiceMock.searchContent(ContentFixture.getContentID())).toReturn(ContentFixture.getContent());

        contentDeNormalizationTask.process(envelopeMock, collectorMock, coordinatorMock);

        verify(objectStoreMock, times(2)).get(ContentFixture.getContentCacheKey());
        verify(searchServiceMock, times(1)).searchContent(ContentFixture.getContentID());
        verify(objectStoreMock, times(1)).put(anyString(), anyString());
        verify(collectorMock).send(argThat(validateOutputTopic(envelopeMock.getMessage(), SUCCESS_TOPIC)));
    }

    @Test
    public void shouldProcessAllOeEventsAndUpdateContentData() throws Exception {
        when(objectStoreMock.get(ContentFixture.getContentCacheKey())).thenReturn(null, getContentCacheJson());
        stub(envelopeMock.getMessage()).toReturn(EventFixture.OeEvent());

        verifyEventHasBeenProcessed();
    }

    @Test
    public void shouldProcessMeEventsAndUpdateContentCache() throws Exception {
        stub(envelopeMock.getMessage()).toReturn(EventFixture.MeEvent());

        verifyEventHasBeenProcessed();
    }

    @Test
    public void shouldNotProcessEventIfObjectIdIsAbsent() throws Exception {
        stub(envelopeMock.getMessage()).toReturn(EventFixture.EventWithoutObjectID());

        contentDeNormalizationTask.process(envelopeMock, collectorMock, coordinatorMock);

        verify(objectStoreMock, times(0)).get(anyString());
        verify(searchServiceMock, times(0)).searchContent(anyString());
        verify(collectorMock).send(argThat(validateOutputTopic(envelopeMock.getMessage(), SUCCESS_TOPIC)));
    }

    @Test
    public void shouldNotProcessEventIfObjectIdIsEmpty() throws Exception {
        stub(envelopeMock.getMessage()).toReturn(EventFixture.EventWithoutObjectID());

        contentDeNormalizationTask.process(envelopeMock, collectorMock, coordinatorMock);

        verify(objectStoreMock, times(0)).get(anyString());
        verify(searchServiceMock, times(0)).searchContent(anyString());
        verify(collectorMock).send(argThat(validateOutputTopic(envelopeMock.getMessage(), SUCCESS_TOPIC)));
    }

    @Test
    public void shouldSkipIfDeNormalizationIsNotSupported() throws Exception {
        stub(envelopeMock.getMessage()).toReturn(EventFixture.EventWithOtherObjectType());

        contentDeNormalizationTask.process(envelopeMock, collectorMock, coordinatorMock);

        verify(objectStoreMock, times(0)).get(anyString());
        verify(searchServiceMock, times(0)).searchContent(anyString());
        verify(collectorMock).send(argThat(validateOutputTopic(envelopeMock.getMessage(), SUCCESS_TOPIC)));
    }

    private ArgumentMatcher<OutgoingMessageEnvelope> validateOutputTopic(final Object message, final String stream) {
        return new ArgumentMatcher<OutgoingMessageEnvelope>() {
            @Override
            public boolean matches(Object o) {
                OutgoingMessageEnvelope outgoingMessageEnvelope = (OutgoingMessageEnvelope) o;
                SystemStream systemStream = outgoingMessageEnvelope.getSystemStream();
                assertEquals("kafka", systemStream.getSystem());
                assertEquals(stream, systemStream.getStream());
                assertEquals(message, outgoingMessageEnvelope.getMessage());
                return true;
            }
        };
    }

    private String getContentCacheJson() {
        Content content = ContentFixture.getContent();
        CacheEntry contentCache = new CacheEntry(content, new Date().getTime());
        return new Gson().toJson(contentCache, CacheEntry.class);
    }

    private void verifyEventHasBeenProcessed() throws Exception {
        stub(searchServiceMock.searchContent(ContentFixture.getContentID())).toReturn(ContentFixture.getContent());

        CacheEntry expiredContent = new CacheEntry(ContentFixture.getContent(), new Date().getTime() - 100000);
        CacheEntry validContent = new CacheEntry(ContentFixture.getContent(), new Date().getTime() + 100000);

        when(objectStoreMock.get(ContentFixture.getContentCacheKey()))
                .thenReturn(
                        new Gson().toJson(expiredContent, CacheEntry.class),
                        new Gson().toJson(validContent, CacheEntry.class));

        contentDeNormalizationTask.process(envelopeMock, collectorMock, coordinatorMock);

        verify(searchServiceMock, times(1)).searchContent("do_30076072");
        Map<String, Object> processedMessage = (Map<String, Object>) envelopeMock.getMessage();

        assertTrue(processedMessage.containsKey("contentdata"));

        HashMap<String, Object> contentData = (HashMap<String, Object>) processedMessage.get("contentdata");
        assertEquals(contentData.get("name"), ContentFixture.getContentMap().get("name"));
        assertEquals(contentData.get("description"), ContentFixture.getContentMap().get("description"));

        verify(collectorMock).send(argThat(validateOutputTopic(envelopeMock.getMessage(), SUCCESS_TOPIC)));
    }

}