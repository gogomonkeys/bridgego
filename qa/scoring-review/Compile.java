import java.util.ArrayList;
import java.util.List;
import javax.tools.ToolProvider;

/** Offline compile helper for environments with Java compiler modules but no javac executable. */
class Compile {
  public static void main(String[] args) {
    List<String> options = new ArrayList<>(List.of("-d", args[0]));
    for (int i = 1; i < args.length; i++) options.add(args[i]);
    int code = ToolProvider.getSystemJavaCompiler().run(null, null, null, options.toArray(String[]::new));
    if (code != 0) throw new IllegalStateException("Compile failed: " + code);
  }
}
