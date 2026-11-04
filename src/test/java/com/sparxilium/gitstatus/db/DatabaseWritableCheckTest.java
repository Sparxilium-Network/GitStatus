package com.sparxilium.gitstatus.db;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sparxilium.gitstatus.config.StartupCheckException;

class DatabaseWritableCheckTest {

    @TempDir
    Path tmp;

    @Test
    void createsMissingDataDirectory() {
        Path db = tmp.resolve("data/bot.db");
        assertDoesNotThrow(() -> SchemaInitializer.checkWritable(db));
        assertTrue(Files.isDirectory(db.getParent()));
    }

    @Test
    void dataDirectoryThatCannotBeCreatedIsReportedWithFix() throws Exception {
        Files.writeString(tmp.resolve("data"), "not a directory");
        StartupCheckException e = assertThrows(StartupCheckException.class,
                () -> SchemaInitializer.checkWritable(tmp.resolve("data/bot.db")));
        assertTrue(e.getMessage().contains("is not writable"));
        assertTrue(e.action().contains("setup.sh"));
    }

    @Test
    void readOnlyDatabaseFileIsReported() throws Exception {
        Path db = Files.writeString(tmp.resolve("bot.db"), "");
        db.toFile().setWritable(false);
        try {
            assumeFalse(Files.isWritable(db), "running as a user that ignores file permissions");
            assertThrows(StartupCheckException.class, () -> SchemaInitializer.checkWritable(db));
        } finally {
            db.toFile().setWritable(true);
        }
    }

    @Test
    void foundInCauseChain() {
        StartupCheckException check = new StartupCheckException("d", "a");
        assertTrue(StartupCheckException.find(new RuntimeException(new IllegalStateException(check))) == check);
    }
}
