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
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("logging.threshold.console", "OFF");
        defaults.put("logging.file.name", System.getProperty("java.io.tmpdir") + "/gitstatus-admin.log");
        app.setDefaultProperties(defaults);
        ConfigurableApplicationContext ctx = app.run(args);
        System.exit(SpringApplication.exit(ctx));
    }
}
