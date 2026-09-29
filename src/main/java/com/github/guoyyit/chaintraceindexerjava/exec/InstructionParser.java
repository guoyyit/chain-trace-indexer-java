package com.github.guoyyit.chaintraceindexerjava.exec;

import com.github.guoyyit.chaintraceindexerjava.model.Instruction;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the textual mini-instruction language embedded in raw transactions.
 *
 * <p>Grammar (one instruction per line, {@code #} starts a comment, blank lines ignored):
 * <pre>
 * transfer TO VALUE
 * call TO VALUE
 *   ... nested instructions ...
 * end
 * revert REASON
 * sset KEY VALUE
 * sdel KEY
 * </pre>
 * Nested bodies are delimited by indentation-independent {@code call}/{@code end} blocks.
 * Addresses and keys are non-whitespace tokens. Values are decimal or {@code 0x}-prefixed hex.
 */
public final class InstructionParser {

    private InstructionParser() {
    }

    public static List<Instruction> parse(List<String> lines) {
        Parser parser = new Parser(lines);
        return parser.parseBlock(true);
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int cursor;

        Parser(List<String> lines) {
            this.tokens = tokenize(lines);
        }

        List<Instruction> parseBlock(boolean topLevel) {
            List<Instruction> body = new ArrayList<>();
            while (cursor < tokens.size()) {
                Token token = tokens.get(cursor);
                String op = token.text.toLowerCase();
                if ("end".equals(op)) {
                    if (topLevel) {
                        throw new InstructionParseException(
                                "unmatched 'end' at line " + token.line);
                    }
                    cursor++;
                    return body;
                }
                body.add(parseInstruction(token));
            }
            if (!topLevel) {
                throw new InstructionParseException("missing 'end' for call block");
            }
            return List.copyOf(body);
        }

        private Instruction parseInstruction(Token token) {
            String op = token.text.toLowerCase();
            cursor++;
            switch (op) {
                case "transfer" -> {
                    String to = next(token, "transfer TO VALUE");
                    long value = parseLong(next(token, "transfer TO VALUE"), token.line);
                    expectEnd(token);
                    return new Instruction.Transfer(to, value);
                }
                case "call" -> {
                    String to = next(token, "call TO VALUE");
                    long value = parseLong(next(token, "call TO VALUE"), token.line);
                    expectEnd(token);
                    List<Instruction> nested = parseBlock(false);
                    return new Instruction.Call(to, value, nested);
                }
                case "revert" -> {
                    StringBuilder reason = new StringBuilder();
                    while (cursor < tokens.size() && tokens.get(cursor).line() == token.line) {
                        if (reason.length() > 0) {
                            reason.append(' ');
                        }
                        reason.append(tokens.get(cursor).text);
                        cursor++;
                    }
                    return new Instruction.Revert(reason.toString());
                }
                case "sset" -> {
                    String key = next(token, "sset KEY VALUE");
                    String value = next(token, "sset KEY VALUE");
                    expectEnd(token);
                    return new Instruction.SetStorage(key, value);
                }
                case "sdel" -> {
                    String key = next(token, "sdel KEY");
                    expectEnd(token);
                    return new Instruction.DelStorage(key);
                }
                default -> throw new InstructionParseException(
                        "unknown instruction '" + token.text + "' at line " + token.line);
            }
        }

        private String next(Token current, String expected) {
            if (cursor >= tokens.size() || tokens.get(cursor).line() != current.line) {
                throw new InstructionParseException(
                        "malformed instruction at line " + current.line + ", expected: " + expected);
            }
            return tokens.get(cursor++).text;
        }

        private void expectEnd(Token current) {
            if (cursor < tokens.size() && tokens.get(cursor).line() == current.line) {
                throw new InstructionParseException(
                        "unexpected token '" + tokens.get(cursor).text
                                + "' at line " + current.line);
            }
        }
    }

    private static long parseLong(String raw, int line) {
        try {
            String value = raw.trim().replace("_", "");
            if (value.startsWith("0x") || value.startsWith("0X")) {
                return Long.parseUnsignedLong(value.substring(2), 16);
            }
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new InstructionParseException("invalid numeric value '" + raw + "' at line " + line);
        }
    }

    private record Token(String text, int line) {
    }

    private static List<Token> tokenize(List<String> lines) {
        List<Token> tokens = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            int hash = raw.indexOf('#');
            String line = hash >= 0 ? raw.substring(0, hash) : raw;
            int lineNo = i + 1;
            for (String word : line.trim().split("\\s+")) {
                if (!word.isEmpty()) {
                    tokens.add(new Token(word, lineNo));
                }
            }
        }
        return List.copyOf(tokens);
    }
}
