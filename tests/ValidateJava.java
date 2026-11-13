import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import com.sun.source.util.JavacTask;
// Syntax check only: Android SDK/dependency type checks require Gradle.
class ValidateJava {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK 17 required");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var files = manager.getJavaFileObjectsFromStrings(Arrays.asList(args));
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    List.of("-proc:none"), null, files);
            task.parse();
            for (var diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR)
                    throw new IllegalStateException(diagnostic.toString());
            }
        }
        System.out.println("PASS Java syntax (not Android compilation)");
    }
}
