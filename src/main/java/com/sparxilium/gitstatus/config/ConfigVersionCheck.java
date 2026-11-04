package com.sparxilium.gitstatus.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Checks {@code config.version} in config.conf. The version tells {@code deploy/setup.sh} which
 * upgrade steps a config file still needs when a release adds or renames settings, and stops this
 * release from running on a file written for a newer one.
 * <p>
 * When a release changes config.conf: raise {@link #CURRENT}, add the step to {@code upgrade_config}
 * in setup.sh and to {@code config.conf.example}, and keep a default for older files where possible.
 */
@Component
public class ConfigVersionCheck implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ConfigVersionCheck.class);

    /** The config.conf format this release reads and setup.sh writes. */
    public static final int CURRENT = 1;

    private final String configured;

    public ConfigVersionCheck(@Value("${config.version:}") String configured) {
        this.configured = configured;
    }

    @Override
    public void afterPropertiesSet() {
        if (check(configured) == 0) {
            log.warn("Setup: config.conf has no config.version; treating it as version {}. Run 'sudo ./setup.sh' "
                    + "in the deploy directory to add it", CURRENT);
        }
    }

    /** @return the configured version, or 0 when the setting is missing (files from before versioning) */
    static int check(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            return 0;
        }
        int version;
        try {
            version = Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new StartupCheckException("config.version in config.conf is '" + v + "', not a number.",
                    "Set it to config.version=" + CURRENT + ", or run 'sudo ./setup.sh' in the deploy directory.");
        }
        if (version < 1) {
            throw new StartupCheckException("config.version in config.conf is " + version + ", which does not exist.",
                    "Set it to config.version=" + CURRENT + ", or run 'sudo ./setup.sh' in the deploy directory.");
        }
        if (version > CURRENT) {
            throw new StartupCheckException("config.conf is version " + version + ", but this release only understands "
                    + "versions up to " + CURRENT + ". It was written for a newer gitstatus.",
                    "Run the newer release again (git pull && docker compose up -d --build), or restore the "
                            + "config.conf that came with this release.");
        }
        return version;
    }
}
