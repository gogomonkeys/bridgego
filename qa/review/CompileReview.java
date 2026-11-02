import javax.tools.ToolProvider;
class CompileReview {
 public static void main(String[] args) {
  int result=ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",args.length>1?args[1]:"/tmp/bridge-review-classes",args[0]);
  if(result!=0)throw new IllegalStateException("compile status "+result);
 }
}
