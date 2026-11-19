import java.io.File;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import com.sun.source.util.JavacTask;

/** Syntax check without requiring Android SDK (does NOT prove Android compiles). */
public class ParseJava {
    public static void main(String[] files) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var input = manager.getJavaFileObjectsFromStrings(List.of(files));
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null, input);
            task.parse();
            long errors=diagnostics.getDiagnostics().stream().filter(d->d.getKind()==javax.tools.Diagnostic.Kind.ERROR).count();
            if(errors>0) {
                diagnostics.getDiagnostics().forEach(System.err::println);
                throw new RuntimeException("Falha na sintaxe Java: "+errors+" erro(s)");
            }
            System.out.println("PASS: sintaxe Java analisada (não equivale a compilação Android)");
        }
    }
}
