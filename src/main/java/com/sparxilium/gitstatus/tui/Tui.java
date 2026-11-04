package com.sparxilium.gitstatus.tui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.googlecode.lanterna.TerminalTextUtils;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.terminal.Terminal;

/**
 * Plain line-based text UI: every screen is printed from the top-left corner as a list of lines.
 *
 * <p>Nothing depends on the terminal size, so a terminal that reports a wrong size (common over SSH)
 * still shows every screen. Lanterna is only used for raw keyboard input and cursor control.
 */
final class Tui {

    static final String RULE = "=".repeat(64);
    /** Rows of a list shown at once; longer lists scroll with the cursor. */
    static final int VISIBLE_ROWS = 15;

    private final Terminal terminal;
    private List<String> lastFrame = List.of();
    private int lastCursorRow = -1;
    private int lastCursorColumn;

    Tui(Terminal terminal) {
        this.terminal = terminal;
        terminal.addResizeListener((t, size) -> redraw());
    }

    /**
     * A list screen: title, header lines, selectable rows, a status message and key help.
     * The cursor is moved by Tui (Up/Down/PageUp/PageDown/Home/End); every other key goes to {@link #onKey}.
     */
    abstract static class View {
        int cursor;
        String message = "";

        abstract String title();

        List<String> header() {
            return List.of();
        }

        List<String> rows() {
            return List.of();
        }

        abstract String keys();

        /** Handles a key; returns false to close the view. Esc closes it by default. */
        boolean onKey(KeyStroke key) {
            return key.getKeyType() != KeyType.Escape;
        }
    }

    /** Shows a view until its {@link View#onKey} returns false. */
    void show(View view) {
        while (true) {
            List<String> rows = view.rows();
            view.cursor = Math.max(0, Math.min(view.cursor, rows.size() - 1));
            render(view, rows);
            KeyStroke key = read();
            int page = VISIBLE_ROWS;
            switch (key.getKeyType()) {
                case ArrowUp -> view.cursor = Math.max(0, view.cursor - 1);
                case ArrowDown -> view.cursor = Math.min(rows.size() - 1, view.cursor + 1);
                case PageUp -> view.cursor = Math.max(0, view.cursor - page);
                case PageDown -> view.cursor = Math.min(rows.size() - 1, view.cursor + page);
                case Home -> view.cursor = 0;
                case End -> view.cursor = rows.size() - 1;
                default -> {
                    view.message = "";
                    if (!view.onKey(key)) {
                        return;
                    }
                }
            }
        }
    }

    // ---- dialogs ---------------------------------------------------------

    /** Lets the user pick one item; null when cancelled with Esc. */
    <T> T choose(String title, String description, List<T> items) {
        if (items.isEmpty()) {
            return null;
        }
        List<T> picked = new ArrayList<>(1);
        show(new View() {
            String title() {
                return title;
            }

            List<String> header() {
                return lines(description);
            }

            List<String> rows() {
                return items.stream().map(String::valueOf).toList();
            }

            String keys() {
                return "Up/Down Move    Enter Select    Esc Cancel";
            }

            boolean onKey(KeyStroke key) {
                if (key.getKeyType() == KeyType.Enter) {
                    picked.add(items.get(cursor));
                    return false;
                }
                return super.onKey(key);
            }
        });
        return picked.isEmpty() ? null : picked.getFirst();
    }

    /** Yes / No question; the cursor starts on No. */
    boolean confirm(String title, String text) {
        boolean[] yes = {false};
        View v = new View() {
            String title() {
                return title;
            }

            List<String> header() {
                return lines(text);
            }

            List<String> rows() {
                return List.of("No", "Yes");
            }

            String keys() {
                return "Up/Down Move    Enter Select    Y Yes    N / Esc No";
            }

            boolean onKey(KeyStroke key) {
                if (isChar(key, 'y') || (key.getKeyType() == KeyType.Enter && cursor == 1)) {
                    yes[0] = true;
                    return false;
                }
                return !(isChar(key, 'n') || key.getKeyType() == KeyType.Enter || key.getKeyType() == KeyType.Escape);
            }
        };
        show(v);
        return yes[0];
    }

    void info(String title, String text) {
        List<String> body = new ArrayList<>(frameTop(title));
        body.addAll(indent(lines(text)));
        body.add("");
        body.add("Press any key to continue");
        body.add(RULE);
        draw(body, -1, 0);
        read();
    }

    void error(String text) {
        info("Error", text);
    }

    /** Single-line text input; null when cancelled with Esc. */
    String input(String title, String description, String initial) {
        StringBuilder value = new StringBuilder(initial == null ? "" : initial);
        while (true) {
            List<String> body = new ArrayList<>(frameTop(title));
            body.addAll(indent(lines(description)));
            body.add("");
            String prompt = "  > " + value;
            body.add(prompt);
            body.add("");
            body.add("Enter OK    Backspace Delete    Esc Cancel");
            body.add(RULE);
            draw(body, body.size() - 4, TerminalTextUtils.getColumnWidth(prompt));
            KeyStroke key = read();
            switch (key.getKeyType()) {
                case Enter -> {
                    return value.toString();
                }
                case Escape -> {
                    return null;
                }
                case Backspace -> {
                    if (!value.isEmpty()) {
                        value.deleteCharAt(value.length() - 1);
                    }
                }
                case Character -> {
                    if (!key.isCtrlDown() && !key.isAltDown()) {
                        value.append(key.getCharacter());
                    }
                }
                default -> {
                    // ignored
                }
            }
        }
    }

    /** Runs a blocking call while a "please wait" message is on screen. */
    <T> T busy(String message, Supplier<T> work) {
        List<String> body = new ArrayList<>(frameTop("Please wait"));
        body.add("  " + message);
        body.add(RULE);
        draw(body, -1, 0);
        return work.get();
    }

    // ---- rendering -------------------------------------------------------

    private void render(View view, List<String> rows) {
        List<String> out = new ArrayList<>(frameTop(view.title()));
        List<String> header = view.header();
        if (!header.isEmpty()) {
            out.addAll(indent(header));
            out.add("");
        }
        if (!rows.isEmpty()) {
            int from = Math.max(0, Math.min(view.cursor - VISIBLE_ROWS / 2, rows.size() - VISIBLE_ROWS));
            int to = Math.min(rows.size(), from + VISIBLE_ROWS);
            if (from > 0) {
                out.add("   ... " + from + " more above");
            }
            for (int i = from; i < to; i++) {
                out.add((i == view.cursor ? " > " : "   ") + rows.get(i));
            }
            if (to < rows.size()) {
                out.add("   ... " + (rows.size() - to) + " more below");
            }
            out.add("");
        }
        if (view.message != null && !view.message.isBlank()) {
            out.addAll(indent(lines(view.message)));
            out.add("");
        }
        out.addAll(lines(view.keys()));
        out.add(RULE);
        draw(out, -1, 0);
    }

    private static List<String> frameTop(String title) {
        return List.of(RULE, title, "");
    }

    /** Clears the screen and prints the lines from the top-left corner. */
    private synchronized void draw(List<String> frame, int cursorRow, int cursorColumn) {
        lastFrame = frame;
        lastCursorRow = cursorRow;
        lastCursorColumn = cursorColumn;
        try {
            terminal.setCursorVisible(false);
            terminal.clearScreen();
            for (int i = 0; i < frame.size(); i++) {
                terminal.setCursorPosition(0, i);
                terminal.putString(frame.get(i));
            }
            if (cursorRow >= 0) {
                terminal.setCursorPosition(cursorColumn, cursorRow);
                terminal.setCursorVisible(true);
            } else {
                terminal.setCursorPosition(0, frame.size());
            }
            terminal.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private synchronized void redraw() {
        draw(lastFrame, lastCursorRow, lastCursorColumn);
    }

    private KeyStroke read() {
        try {
            while (true) {
                KeyStroke key = terminal.readInput();
                if (key == null || key.getKeyType() == KeyType.CursorLocation
                        || key.getKeyType() == KeyType.MouseEvent || key.getKeyType() == KeyType.Unknown) {
                    continue;
                }
                if (key.getKeyType() == KeyType.EOF) {
                    throw new UncheckedIOException(new IOException("Terminal input closed"));
                }
                return key;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- text helpers ----------------------------------------------------

    static boolean isChar(KeyStroke key, char c) {
        return key.getKeyType() == KeyType.Character && Character.toLowerCase(key.getCharacter()) == c;
    }

    static boolean isSpace(KeyStroke key) {
        return isChar(key, ' ');
    }

    static String checkbox(boolean checked) {
        return checked ? "[*] " : "[ ] ";
    }

    static List<String> lines(String text) {
        return text == null || text.isEmpty() ? List.of() : List.of(text.split("\n", -1));
    }

    private static List<String> indent(List<String> lines) {
        return lines.stream().map(l -> l.isEmpty() ? l : "  " + l).toList();
    }

    static String pad(String s, int width) {
        StringBuilder sb = new StringBuilder(s == null ? "" : s);
        int len = TerminalTextUtils.getColumnWidth(sb.toString());
        while (len < width) {
            sb.append(' ');
            len++;
        }
        return sb.toString();
    }

    static String message(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
