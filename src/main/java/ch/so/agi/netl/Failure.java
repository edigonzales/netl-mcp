package ch.so.agi.netl;

final class Failure extends RuntimeException {
    final String code;
    final String logPath;
    Failure(String code, String message) { this(code,message,null); }
    Failure(String code, String message, String logPath) { super(message); this.code = code; this.logPath=logPath; }
}
