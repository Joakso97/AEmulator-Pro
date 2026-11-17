package app.aemu.test;

 
 
 
 
public class Main extends Base {
    String program() { return "A"; }
    String[] tests() { return new String[] { "device", "cpu", "disk", "net", "audio", "sensors", "canvas", "pattern", "ui", "glcheck", "gl1", "gl2", "camera1", "webview" }; }
    boolean run(String name) { return false; }
}
