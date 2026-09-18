package ch.so.agi.netl;

final class Failure extends RuntimeException {
    final String code;
    Failure(String code, String message) { super(message); this.code = code; }
}
