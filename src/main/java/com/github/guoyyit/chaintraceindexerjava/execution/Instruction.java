package com.github.guoyyit.chaintraceindexerjava.execution;

import java.util.List;

/**
 * One parsed trace script instruction.
 *
 * <p>{@code args} are the scalar arguments of the instruction (e.g. address,
 * amount, gas, key, value, text). {@code block} is non null only for
 * {@code CALL} and {@code DEPLOY} and carries the inline child program.
 */
record Instruction(String op, List<String> args, List<Instruction> block) {

    boolean hasBlock() {
        return block != null;
    }
}
