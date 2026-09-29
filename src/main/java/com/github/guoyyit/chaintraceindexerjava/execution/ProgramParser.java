package com.github.guoyyit.chaintraceindexerjava.execution;

import java.util.ArrayList;
import java.util.List;

/**
 * Token based parser for the trace script language.
 *
 * <p>The program is first flattened into whitespace separated tokens; braces
 * are standalone tokens regardless of whether they share a line with the
 * instruction:
 * <pre>
 * CALL alice 100 50000 { SSTORE k v FAIL oops }
 * </pre>
 * or the multi line equivalent. Lines beginning with {@code #} are comments.
 */
final class ProgramParser {

    private ProgramParser() {
    }

    static List<Instruction> parse(String program) {
        if (program == null) {
            return List.of();
        }
        List<String> tokens = tokenize(program);
        Cursor cursor = new Cursor(tokens);
        return parseBlock(cursor);
    }

    private static List<String> tokenize(String program) {
        List<String> tokens = new ArrayList<>();
        for (String rawLine : program.lines().toList()) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (Character.isWhitespace(ch)) {
                    flush(tokens, current);
                } else if (ch == '{' || ch == '}') {
                    flush(tokens, current);
                    tokens.add(String.valueOf(ch));
                } else {
                    current.append(ch);
                }
            }
            flush(tokens, current);
        }
        return tokens;
    }

    private static void flush(List<String> tokens, StringBuilder current) {
        if (!current.isEmpty()) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    private static List<Instruction> parseBlock(Cursor cursor) {
        List<Instruction> instructions = new ArrayList<>();
        while (cursor.hasNext() && !"}".equals(cursor.peek())) {
            String op = cursor.next().toUpperCase();
            List<String> args = new ArrayList<>();
            int arity = arityOf(op);
            while (cursor.hasNext() && !"{".equals(cursor.peek()) && !"}".equals(cursor.peek())
                    && args.size() < arity) {
                args.add(cursor.next());
            }
            List<Instruction> block = null;
            if (cursor.hasNext() && "{".equals(cursor.peek())) {
                cursor.next();
                block = parseBlock(cursor);
                if (!cursor.hasNext() || !"}".equals(cursor.next())) {
                    throw new IllegalArgumentException("missing closing brace for " + op);
                }
            }
            instructions.add(new Instruction(op, List.copyOf(args), block));
        }
        return instructions;
    }

    private static int arityOf(String op) {
        return switch (op) {
            case "TRANSFER" -> 2;
            case "SSTORE" -> 2;
            case "SLOAD", "FAIL" -> 1;
            case "LOG" -> Integer.MAX_VALUE;
            case "CALL" -> 3;
            case "DEPLOY" -> 2;
            default -> Integer.MAX_VALUE;
        };
    }

    private static final class Cursor {
        private final List<String> tokens;
        private int position;

        private Cursor(List<String> tokens) {
            this.tokens = tokens;
        }

        boolean hasNext() {
            return position < tokens.size();
        }

        String peek() {
            return tokens.get(position);
        }

        String next() {
            return tokens.get(position++);
        }
    }
}
