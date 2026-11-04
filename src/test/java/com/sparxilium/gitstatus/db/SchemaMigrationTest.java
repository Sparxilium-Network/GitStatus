package com.sparxilium.gitstatus.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import com.sparxilium.gitstatus.config.AppSettings;
import com.sparxilium.gitstatus.model.Language;
import com.sparxilium.gitstatus.model.Records.InstallationStatus;

class SchemaMigrationTest {

    @TempDir
    Path tmp;

    @Test
    void addsLanguageColumnToExistingDatabase() throws Exception {
        String db = tmp.resolve("bot.db").toString();
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + db);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        // destination table as created by the first release (no language column)
        jdbc.execute("""
                CREATE TABLE discord_destination (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, guild_id TEXT,
                    channel_id TEXT NOT NULL, bot_profile TEXT NOT NULL DEFAULT 'default',
                    enabled INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL)
                """);
        jdbc.update("INSERT INTO discord_destination (name, channel_id, created_at) VALUES ('old', '111111111111111111', 'x')");
        // installation table from before multi-App support (no app_id column)
        jdbc.execute("""
                CREATE TABLE provider_installation (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, provider TEXT NOT NULL, external_id TEXT NOT NULL,
                    owner TEXT NOT NULL, type TEXT NOT NULL, status TEXT NOT NULL,
                    permissions TEXT NOT NULL DEFAULT '{}', events TEXT NOT NULL DEFAULT '[]',
                    updated_at TEXT NOT NULL, UNIQUE (provider, external_id))
                """);
        jdbc.update("INSERT INTO provider_installation (provider, external_id, owner, type, status, updated_at) "
                + "VALUES ('github', '7', 'acme', 'Organization', 'SELECTED', 'x')");

        AppSettings settings = new AppSettings(db, "https://api.github.com", "", "", "", "", "", 5000, 1000, 33444);
        SchemaInitializer init = new SchemaInitializer(jdbc, settings);
        init.afterPropertiesSet();
        init.afterPropertiesSet(); // idempotent

        ConfigStore store = new ConfigStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
        assertEquals(Language.EN, store.destinations().get(0).language());
        store.setDestinationLanguage(store.destinations().get(0).id(), Language.ZH_TW);
        assertEquals(Language.ZH_TW, store.destinations().get(0).language());

        assertNull(store.installations("github").get(0).appId());
        assertEquals(1, store.installationsWithoutApp("github"));
        store.assignInstallationsWithoutApp("github", "1234567");
        assertEquals("1234567", store.installations("github").get(0).appId());
        assertEquals(InstallationStatus.SELECTED, store.installations("github").get(0).status());
    }
}
