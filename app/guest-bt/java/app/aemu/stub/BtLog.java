package app.aemu.stub;

 
final class BtLog {
    private BtLog() { }
    static void i(String m) { System.out.println("aemu-bt: " + m); }
    static void w(String m, Throwable t) {
        System.out.println("aemu-bt: " + m + (t != null ? ": " + t : ""));
    }
}
