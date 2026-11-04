package com.sparxilium.gitstatus.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConfigVersionCheckTest {

    @Test
    void acceptsTheCurrentVersion() {
        assertEquals(1, ConfigVersionCheck.check("1"));
        assertEquals(1, ConfigVersionCheck.check(" 1 "));
    }

    @Test
    void missingVersionIsAFileFromBeforeVersioning() {
        assertEquals(0, ConfigVersionCheck.check(""));
        assertEquals(0, ConfigVersionCheck.check(null));
    }

    @Test
    void refusesNewerOrInvalidVersions() {
        StartupCheckException newer = assertThrows(StartupCheckException.class,
                () -> ConfigVersionCheck.check(String.valueOf(ConfigVersionCheck.CURRENT + 1)));
        assertTrue(newer.getMessage().contains("newer gitstatus"));
        assertThrows(StartupCheckException.class, () -> ConfigVersionCheck.check("v1"));
        assertThrows(StartupCheckException.class, () -> ConfigVersionCheck.check("0"));
    }
}
