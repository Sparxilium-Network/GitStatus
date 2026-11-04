package com.sparxilium.gitstatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import com.sparxilium.gitstatus.config.AppSettings;
import com.sparxilium.gitstatus.db.ConfigStore;
import com.sparxilium.gitstatus.db.SchemaInitializer;

class RestartWatcherTest {

    @TempDir
    Path tmp;

    private ConfigStore store;
    private final AtomicInteger shutdowns = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        String db = tmp.resolve("bot.db").toString();
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + db + "?foreign_keys=true");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        new SchemaInitializer(jdbc, new AppSettings(db, "https://api.github.com", "", "", "", "", "", 5000, 1000, 33444))
                .afterPropertiesSet();
        store = new ConfigStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    @Test
    void noRequestKeepsRunningAndRecordsTheStartTime() {
        RestartWatcher watcher = new RestartWatcher(store, shutdowns::incrementAndGet);
        watcher.checkRestartRequest();
        assertEquals(0, shutdowns.get());
        assertTrue(store.status(ConfigStore.STATUS_DAEMON_STARTED).isPresent());
    }

    @Test
    void requestMadeAfterStartShutsDownOnce() {
        RestartWatcher watcher = new RestartWatcher(store, shutdowns::incrementAndGet);
        store.requestRestart();
        watcher.checkRestartRequest();
        watcher.checkRestartRequest();
        assertEquals(1, shutdowns.get());
    }

    @Test
    void requestHandledBeforeThisStartIsIgnored() {
        store.requestRestart(); // the request that restarted us
        RestartWatcher watcher = new RestartWatcher(store, shutdowns::incrementAndGet);
        watcher.checkRestartRequest();
        assertEquals(0, shutdowns.get());
    }
}
