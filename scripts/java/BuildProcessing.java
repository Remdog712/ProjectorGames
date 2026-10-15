import javax.tools.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
/** Run with `java scripts/java/BuildProcessing.java <repo> <core.jar>`. */
class BuildProcessing {
  public static void main(String[] args)throws Exception {
    Path root=Paths.get(args[0]),sketch=root.resolve("processing/ProjectorCalibrator"),out=root.resolve(".processing-build/classes");
    Files.createDirectories(out);
    List<String> options=new ArrayList<>(Arrays.asList("-encoding","UTF-8","-d",out.toString(),"-classpath"));
    String jars;
    try(Stream<Path> paths=Files.list(sketch.resolve("code"))){jars=paths.filter(p->p.toString().endsWith(".jar")).map(Path::toString).collect(Collectors.joining(java.io.File.pathSeparator));}
    options.add(jars+java.io.File.pathSeparator+args[1]);
    for(Path dir:Arrays.asList(sketch,root.resolve("processing/tests")))try(Stream<Path> paths=Files.list(dir)){paths.filter(p->p.toString().endsWith(".java")).forEach(p->options.add(p.toString()));}
    JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
    if(compiler==null)throw new IllegalStateException("A JDK is needed for this developer build; Processing IDE supplies its own compiler for the sketch.");
    int result=compiler.run(null,System.out,System.err,options.toArray(new String[0]));
    if(result!=0)System.exit(result);
    System.out.println("Compiled Processing/Java sources and checks.");
  }
}
