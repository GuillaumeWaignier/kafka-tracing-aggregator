package org.ianitrix.kafka;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import com.ginsberg.junit.exit.ExpectSystemExitWithStatus;
import org.ianitrix.kafka.AggregatorTraceStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Properties;

class MainTest {

    /** Mocked appender */
    @Mock
    private Appender<ILoggingEvent> mockAppender;

    /** Logging event captore */
    @Captor
    private ArgumentCaptor<LoggingEvent> captorLoggingEvent;

    @BeforeEach
    public void init() {
        // mock the logger
        MockitoAnnotations.initMocks(this);
        final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(this.mockAppender);
    }

    @Test
    @ExpectSystemExitWithStatus(1)
    void testWrongParameterNumber() throws InterruptedException {
        AggregatorTraceStream.main(new String[0]);
        Assertions.fail("Exit with status 1 expected");
    }

    @Test
    void testLoadConfigurationFile() {

        final File f2 = new File("src/test/resources/config2.properties");

        final Properties properties = AggregatorTraceStream.loadConfigurationFile(f2.getAbsolutePath());

        Assertions.assertEquals("localhost:9095", properties.get("bootstrap.servers"));
        Assertions.assertEquals("some values", properties.get("properties"));
    }

    @Test
    @ExpectSystemExitWithStatus(1)
    void testLoadConfigurationFileNotFound() {
        final File f2 = new File("src/test/resources/configNotFound.properties");
        AggregatorTraceStream.loadConfigurationFile(f2.getAbsolutePath());
        Assertions.fail("Exit with status 1 expected");
    }

    @Test
    void testSuccessStart() throws InterruptedException {
        final File f2 = new File("src/test/resources/config2.properties");
        AggregatorTraceStream.main(new String[] { f2.getAbsolutePath() });

        Mockito.verify(this.mockAppender, Mockito.atLeastOnce()).doAppend(this.captorLoggingEvent.capture());
        final LoggingEvent loggingEvent = captorLoggingEvent
                .getAllValues()
                .stream()
                .filter(event -> event.getMessage().startsWith("Topologies:"))
                .findFirst()
                .get();

        Assertions.assertTrue(loggingEvent.getMessage().contains("Source: KSTREAM-SOURCE-0000000000 (topics: [_tracing])"));

    }
}
