import java.util.*;
import javax.tools.ToolProvider;
class Compile {
  public static void main(String[] args) {
    List<String> options = new ArrayList<>(List.of("-classpath", args[0], "-d", args[1]));
    options.addAll(Arrays.asList(args).subList(2, args.length));
    int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, options.toArray(String[]::new));
    if (status != 0) throw new IllegalStateException("Compiler exit " + status);
  }
}
