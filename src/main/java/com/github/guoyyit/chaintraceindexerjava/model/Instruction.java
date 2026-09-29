package com.github.guoyyit.chaintraceindexerjava.model;

public sealed interface Instruction
        permits Instruction.Transfer, Instruction.Call, Instruction.Revert,
        Instruction.SetStorage, Instruction.DelStorage {

    record Transfer(String to, long value) implements Instruction {
    }

    record Call(String to, long value, java.util.List<Instruction> body) implements Instruction {
        public Call {
            body = body == null ? java.util.List.of() : java.util.List.copyOf(body);
        }
    }

    record Revert(String reason) implements Instruction {
    }

    record SetStorage(String key, String value) implements Instruction {
    }

    record DelStorage(String key) implements Instruction {
    }
}
