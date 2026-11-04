package com.sparxilium.gitstatus;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.sparxilium.gitstatus.config.StartupCheckException;

/**
 * Entry point. Two run modes share one JAR and one database:
 * <ul>
 *   <li>{@code run}   – long-running daemon (webhook receiver, router, Discord sender)</li>
 *   <li>{@code admin} – interactive server-side TUI, exits when the administrator leaves</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
public class GitStatusApplication {

    public static final String PROFILE_DAEMON = "daemon";
    public static final String PROFILE_ADMIN = "admin";

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "";
        String[] rest = args.length > 0 ? Arrays.copyOfRange(args, 1, args.length) : args;

        switch (mode) {
            case "run" -> runDaemon(rest);
            case "admin" -> runAdmin(rest);
            default -> {
                System.err.println("Usage: java -jar gitstatus.jar <run|admin>");
                System.err.println("  run    start the notification daemon");
                System.err.println("  admin  start the server-side admin TUI");
                System.exit(2);
            }
        }
    }

    private static void runDaemon(String[] args) {
        SpringApplication app = new SpringApplication(GitStatusApplication.class);
        app.setAdditionalProfiles(PROFILE_DAEMON);
        app.run(args);
    }

    private static void runAdmin(String[] args) {
        SpringApplication app = new SpringApplication(GitStatusApplication.class);
        app.setAdditionalProfiles(PROFILE_ADMIN);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        // The TUI owns the terminal: keep log output away from the console.
        String logFile = System.getProperty("java.io.tmpdir") + "/gitstatus-admin.log";
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("logging.threshold.console", "OFF");
        defaults.put("logging.file.name", logFile);
        app.setDefaultProperties(defaults);
        ConfigurableApplicationContext ctx;
        try {
            ctx = app.run(args);
        } catch (RuntimeException e) {
            // console logging is off, so the failure report would otherwise be invisible
            StartupCheckException check = StartupCheckException.find(e);
            if (check != null) {
                System.err.println("Admin TUI failed to start: " + check.getMessage());
                System.err.println(check.action());
            } else {
                System.err.println("Admin TUI failed to start: " + e.getMessage());
                System.err.println("Details: " + logFile);
            }
            System.exit(1);
            return;
        }
        System.exit(SpringApplication.exit(ctx));
    }
}
