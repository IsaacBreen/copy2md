package bf.com.copy2md.analysis.impl;

import bf.com.copy2md.analysis.FunctionCallAnalyzer;
import bf.com.copy2md.model.ExtractionConfig;
import bf.com.copy2md.model.FunctionContext;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class PythonFunctionCallAnalyzer implements FunctionCallAnalyzer {
    private static final Logger LOG = Logger.getInstance(PythonFunctionCallAnalyzer.class);

    private final Project project;
    private final ExtractionConfig config;
    private final Set<String> processedFunctions = new HashSet<>();
    private static class ClassContext {
        final String className;
        final PsiElement classElement;
        final Map<String, PsiElement> methods;

        ClassContext(String className, PsiElement classElement) {
            this.className = className;
            this.classElement = classElement;
            this.methods = new HashMap<>();
        }
    }
    private final Map<String, ClassContext> classContextMap = new HashMap<>();

    public PythonFunctionCallAnalyzer(Project project, ExtractionConfig config) {
        this.project = project;
        this.config = config;
    }


    @Override
    public Set<FunctionContext> analyzeFunctionCalls(PsiElement element) {
        Set<FunctionContext> contexts = new LinkedHashSet<>();
        if (element != null) {
            // 清理缓存状态
            processedFunctions.clear();
            classContextMap.clear();

            // 初始化类上下文
            initializeClassContexts(element.getContainingFile());

            // 分析函数或方法
            if (isPythonFunctionOrMethod(element)) {
                ClassContext classContext = getClassContext(element);
                analyzePythonFunction(element, contexts, 0, classContext);
            }
        }
        return contexts;
    }
    private void collectPythonFiles(VirtualFile dir, List<VirtualFile> pythonFiles) {
        for (VirtualFile file : dir.getChildren()) {
            if (file.isDirectory()) {
                // 排除虚拟环境和缓存目录
                String name = file.getName();
                if (!name.equals("venv") && !name.equals("__pycache__") && !name.startsWith(".")) {
                    collectPythonFiles(file, pythonFiles);
                }
            } else if ("py".equals(file.getExtension())) {
                pythonFiles.add(file);
            }
        }
    }
    private Collection<VirtualFile> findProjectPythonFiles(Project project) {
        List<VirtualFile> pythonFiles = new ArrayList<>();
        VirtualFile baseDir = project.getBaseDir();
        if (baseDir != null) {
            collectPythonFiles(baseDir, pythonFiles);
        }
        return pythonFiles;
    }

    private void initializeAllClassContexts(PsiElement element) {
        // 初始化当前文件
        initializeClassContexts(element.getContainingFile());

        // 获取项目中所有相关Python文件
        Project project = element.getProject();
        Collection<VirtualFile> pythonFiles = findProjectPythonFiles(project);

        // 初始化所有文件的类上下文
        for (VirtualFile file : pythonFiles) {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (psiFile != null) {
                initializeClassContexts(psiFile);
            }
        }
    }
    // 修改原有的analyzeFunctionCalls方法名称，避免冲突
    private void analyzeFunctionCallsInternal(PsiElement function, Set<FunctionContext> contexts, int depth, ClassContext classContext) {
        function.acceptChildren(new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (isPythonFunctionCall(element)) {
                    PsiElement calledFunction = resolveFunction(element);
                    if (calledFunction != null) {
                        analyzePythonFunction(calledFunction, contexts, depth + 1, classContext);
                    }
                }
                element.acceptChildren(this);
            }
        });
    }
    private void initializeClassContexts(PsiFile file) {
        file.accept(new PsiRecursiveElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (isPythonClass(element)) {
                    processClassDefinition(element);
                }
                super.visitElement(element);
            }
        });
    }

    private void processClassDefinition(PsiElement classElement) {
        String className = extractClassName(classElement);
        ClassContext context = new ClassContext(className, classElement);

        // 收集所有方法，包括装饰器方法
        for (PsiElement child : classElement.getChildren()) {
            if (isPythonFunctionOrMethod(child)) {
                String methodName = extractFunctionName(child);
                context.methods.put(methodName, child);
            }
        }

        classContextMap.put(className, context);
    }
    private void collectClassMethods(PsiElement classElement, ClassContext context) {
        classElement.accept(new PsiRecursiveElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (isPythonFunctionOrMethod(element)) {
                    String methodName = extractFunctionName(element);
                    context.methods.put(methodName, element);
                }
                super.visitElement(element);
            }
        });
    }

    private ClassContext getClassContext(PsiElement element) {
        List<ClassContext> classStack = new ArrayList<>();
        PsiElement current = element;

        while (current != null) {
            if (isPythonClass(current)) {
                String className = extractClassName(current);
                ClassContext context = classContextMap.get(className);
                if (context != null) {
                    classStack.add(0, context);
                }
            }
            current = current.getParent();
        }

        // 返回最近的类上下文
        return classStack.isEmpty() ? null : classStack.get(0);
    }

    private boolean isPythonFunctionOrMethod(PsiElement element) {
        if (element == null) return false;

        String text = element.getText().trim();
        // 检查装饰器
        PsiElement prevSibling = element.getPrevSibling();
        while (prevSibling != null) {
            String siblingText = prevSibling.getText().trim();
            if (siblingText.startsWith("@")) {
                // 包含装饰器的情况
                text = siblingText + "\n" + text;
            }
            prevSibling = prevSibling.getPrevSibling();
        }

        // 检查函数定义
        return text.contains("def ") || text.matches("\\s*async\\s+def\\s+.*")
                || text.matches("\\s*def\\s+.*");
    }

    private boolean isPythonClass(PsiElement element) {
        if (element == null) return false;
        String text = element.getText().trim();
        return text.startsWith("class ") || text.matches("\\s*class\\s+.*");
    }

    private String extractClassName(PsiElement classElement) {
        String text = classElement.getText().trim();
        int startIndex = text.indexOf("class ") + 6;
        int endIndex = text.indexOf("(");
        if (endIndex == -1) {
            endIndex = text.indexOf(":");
        }
        if (endIndex == -1) {
            endIndex = text.length();
        }
        return text.substring(startIndex, endIndex).trim();
    }

    // 修改现有的resolveFunction方法
    private PsiElement resolveFunction(PsiElement callExpression) {
        try {
            String callName = extractCallName(callExpression);
            if (callName == null) return null;

            // 1. 检查是否是方法调用
            if (isMethodCall(callExpression)) {
                return resolveMethodCall(callExpression);
            }

            // 2. 检查本地作用域
            PsiElement localResult = resolveLocalFunction(callExpression, callName);
            if (localResult != null) return localResult;

            // 3. 检查当前文件
            PsiElement fileResult = resolveFileFunction(callExpression, callName);
            if (fileResult != null) return fileResult;

            // 4. 检查导入
            return resolveImportedFunction(callExpression, callName);

        } catch (Exception e) {
            LOG.warn("Error resolving function call: " + e.getMessage());
            return null;
        }
    }

    private boolean isMethodCall(PsiElement callExpression) {
        String text = callExpression.getText();
        return text.contains(".") && text.contains("(");
    }

    private PsiElement resolveMethodCall(PsiElement callExpression) {
        String text = callExpression.getText();
        int dotIndex = text.lastIndexOf(".");
        int parenIndex = text.indexOf("(");

        if (dotIndex > 0 && parenIndex > dotIndex) {
            String objectPart = text.substring(0, dotIndex).trim();
            String methodName = text.substring(dotIndex + 1, parenIndex).trim();

            // 查找类定义
            for (ClassContext context : classContextMap.values()) {
                PsiElement method = context.methods.get(methodName);
                if (method != null) {
                    return method;
                }
            }
        }
        return null;
    }
    private PsiElement resolveLocalFunction(PsiElement element, String name) {
        // 在当前作用域中查找函数定义
        PsiElement scope = element.getParent();
        while (scope != null && !(scope instanceof PsiFile)) {
            if (isPythonFunctionOrMethod(scope)) {
                PsiElement def = findLocalDefinition(scope, name);
                if (def != null) {
                    return def;
                }
            }
            scope = scope.getParent();
        }
        return null;
    }
    private PsiElement resolveImportedFunction(PsiElement callExpression, String callName) {
        // 获取当前文件的导入信息
        List<ImportInfo> imports = collectImports(callExpression.getContainingFile());

        // 遍历所有导入，尝试解析函数
        for (ImportInfo importInfo : imports) {
            PsiElement resolved = resolveImportedFunctionFromInfo(importInfo, callName);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private PsiElement resolveImportedFunctionFromInfo(ImportInfo importInfo, String callName) {
        // 获取项目根目录
        VirtualFile projectRoot = project.getBaseDir();
        if (projectRoot == null) {
            return null;
        }

        // 构建可能的模块路径
        String modulePath = importInfo.fromModule != null ?
                importInfo.fromModule.replace(".", "/") :
                importInfo.importedName.replace(".", "/");

        // 尝试不同的可能路径
        List<String> possiblePaths = Arrays.asList(
                modulePath + ".py",
                modulePath + "/__init__.py"
        );

        for (String path : possiblePaths) {
            VirtualFile moduleFile = projectRoot.findFileByRelativePath(path);
            if (moduleFile != null) {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(moduleFile);
                if (psiFile != null) {
                    // 在模块文件中查找函数
                    PsiElement[] functions = findFunctionsInFile(psiFile,
                            importInfo.fromModule != null ? importInfo.importedName : callName);
                    if (functions.length > 0) {
                        return functions[0];
                    }
                }
            }
        }

        return null;
    }
    private PsiElement resolveFileFunction(PsiElement element, String name) {
        // 在当前文件中查找函数定义
        PsiFile file = element.getContainingFile();
        PsiElement[] functions = findFunctionsInFile(file, name);
        return functions.length > 0 ? functions[0] : null;
    }
    private void analyzePythonFunction(PsiElement function, Set<FunctionContext> contexts, int depth, ClassContext classContext) {

        if (function == null || !isValidFunction(function)) {
            return;
        }

        String functionSignature = getFunctionSignature(function, classContext);
        if (processedFunctions.contains(functionSignature) || depth > config.getMaxDepth()) {
            return;
        }

        processedFunctions.add(functionSignature);

        if (isRelevantFunction(function)) {
            FunctionContext context = createFunctionContext(function, classContext);
            contexts.add(context);

            if (shouldAnalyzeDeeper(depth)) {
//                analyzeFunctionCallsInternal(function, contexts, depth, classContext);
                analyzeMethodCalls(function, contexts, depth + 1, classContext, context);
            }
        }
    }
    private void analyzeMethodCalls(PsiElement function, Set<FunctionContext> contexts,
                                    int depth, ClassContext classContext,
                                    FunctionContext parentContext) {
        if (depth > config.getMaxDepth()) return;

        function.accept(new PsiRecursiveElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (isPythonFunctionCall(element)) {
                    // 处理 self 调用
                    if (isClassMethodCall(element, classContext)) {
                        String methodName = extractMethodName(element);
                        PsiElement method = classContext.methods.get(methodName);
                        if (method != null) {
                            processFunctionCall(method, contexts, depth + 1,
                                    classContext, parentContext);
                        }
                    } else {
                        PsiElement calledFunction = resolveFunction(element);
                        if (calledFunction != null) {
                            processFunctionCall(calledFunction, contexts,
                                    depth + 1, getClassContext(calledFunction),
                                    parentContext);
                        }
                    }
                }
                super.visitElement(element);
            }
        });
    }

    private boolean isClassMethodCall(PsiElement callElement,
                                      ClassContext classContext) {
        if (classContext == null) return false;
        String text = callElement.getText();
        return text.startsWith("self.") ||
                text.startsWith(classContext.className + ".");
    }

    private String extractMethodName(PsiElement callElement) {
        String text = callElement.getText();
        int dotIndex = text.indexOf(".");
        int parenIndex = text.indexOf("(");
        if (dotIndex >= 0 && parenIndex > dotIndex) {
            return text.substring(dotIndex + 1, parenIndex).trim();
        }
        return null;
    }

    private void processFunctionCall(PsiElement function,
                                     Set<FunctionContext> contexts,
                                     int depth,
                                     ClassContext classContext,
                                     FunctionContext parentContext) {
        String signature = getFunctionSignature(function, classContext);
        if (processedFunctions.contains(signature)) return;

        FunctionContext context = createFunctionContext(function, classContext);
        contexts.add(context);
        parentContext.addDependency(context);

        analyzePythonFunction(function, contexts, depth, classContext);
    }

    private boolean isProcessed(PsiElement function) {
        ClassContext classContext = getClassContext(function);
        String signature = getFunctionSignature(function, classContext);
        return processedFunctions.contains(signature);
    }
    private boolean isPythonFunction(PsiElement element) {
        // 检查是否是Python函数定义
        return element.getText().startsWith("def ");
    }

    private boolean isValidFunction(PsiElement function) {
        VirtualFile virtualFile = function.getContainingFile().getVirtualFile();
        return virtualFile != null &&
                !virtualFile.getPath().contains("/venv/") &&
                !virtualFile.getPath().contains("/__pycache__/");
    }

    private String getFunctionSignature(PsiElement function, ClassContext classContext) {
        String signature = extractFunctionName(function);
        if (classContext != null) {
            signature = classContext.className + "." + signature;
        }
        String filePath = function.getContainingFile().getVirtualFile().getPath();
        return filePath + "::" + signature;
    }
    private String buildFullQualifiedName(PsiElement function, ClassContext classContext) {
        StringBuilder name = new StringBuilder();

        // 添加类名前缀
        if (classContext != null) {
            name.append(classContext.className).append(".");
        }

        // 添加函数名
        name.append(extractFunctionName(function));

        return name.toString();
    }
    private FunctionContext createFunctionContext(PsiElement function, ClassContext classContext) {
        // 1. 构建完整的函数名
        String name = buildFullQualifiedName(function, classContext);

        // 2. 提取完整的源代码
        String sourceText = extractSourceText(function);

        // 3. 创建上下文对象
        return new FunctionContext(
                function,
                name,
                function.getContainingFile().getName(),
                sourceText,
                extractPackageName(function),
                isProjectFunction(function)
        );
    }

    private boolean isRelevantFunction(PsiElement function) {
        // 排除测试函数
        String functionText = function.getText().toLowerCase();
        if (!config.isIncludeTests() &&
                (functionText.startsWith("def test_") ||
                        functionText.contains("@pytest") ||
                        functionText.contains("@unittest"))) {
            return false;
        }

        return true;
    }

    private String extractFunctionName(PsiElement function) {
        String functionText = function.getText();
        int startIndex = 0;

        // 1. 处理装饰器
        while (startIndex < functionText.length() && functionText.charAt(startIndex) == '@') {
            startIndex = functionText.indexOf("\n", startIndex) + 1;
        }

        // 2. 处理缩进
        int level = getIndentationLevel(functionText.substring(0, startIndex));
        startIndex += level * 4;

        // 3. async的情况
        if (functionText.startsWith("async ", startIndex)) {
            startIndex += 6;
        }

        // 4. def的情况
        startIndex += 3; // skip "def "
        int endIndex = functionText.indexOf("(", startIndex);
        return functionText.substring(startIndex, endIndex).trim();
    }
    private int getIndentationLevel(String text) {
        int level = 0;
        for (char c : text.toCharArray()) {
            if (c == ' ') level++;
            else if (c == '\t') level += 4;
            else break;
        }
        return level / 4;
    }
    private String extractSourceText(PsiElement function) {
        StringBuilder text = new StringBuilder();

        // 1. 获取完整的函数文本范围
        PsiElement startElement = function;
        while (startElement.getPrevSibling() != null &&
                startElement.getPrevSibling().getText().trim().startsWith("@")) {
            startElement = startElement.getPrevSibling();
        }

        // 2. 获取函数的起始和结束位置
        int startOffset = startElement.getTextRange().getStartOffset();
        int endOffset = findFunctionEndOffset(function);

        // 3. 获取原始文本
        String originalText = function.getContainingFile().getText()
                .substring(startOffset, endOffset);

        // 4. 保持原始缩进和格式

        // 5. 处理注释
        return config.isIncludeComments() ? originalText :
                removePythonComments(originalText);
    }

    private int findFunctionEndOffset(PsiElement function) {
        int baseIndent = getIndentationLevel(function.getText());
        PsiElement current = function.getNextSibling();

        while (current != null) {
            String text = current.getText();
            if (!text.trim().isEmpty()) {
                int currentIndent = getIndentationLevel(text);
                if (currentIndent <= baseIndent) {
                    break;
                }
            }
            current = current.getNextSibling();
        }

        return current != null ?
                current.getTextRange().getStartOffset() :
                function.getTextRange().getEndOffset();
    }


    private String extractPackageName(PsiElement function) {
        // 从__init__.py文件或目录结构推断包名
        PsiFile containingFile = function.getContainingFile();
        String path = containingFile.getVirtualFile().getPath();
        String[] parts = path.split("/");

        StringBuilder packageName = new StringBuilder();
        boolean foundPythonPackage = false;

        for (String part : parts) {
            if (part.endsWith(".py")) {
                break;
            }
            if (foundPythonPackage) {
                if (packageName.length() > 0) {
                    packageName.append(".");
                }
                packageName.append(part);
            }
            if (part.equals("src") || part.equals("python")) {
                foundPythonPackage = true;
            }
        }

        return packageName.toString();
    }

    private boolean isProjectFunction(PsiElement function) {
        VirtualFile virtualFile = function.getContainingFile().getVirtualFile();
        return virtualFile != null &&
                !virtualFile.getPath().contains("/site-packages/") &&
                !virtualFile.getPath().contains("/dist-packages/");
    }


    private boolean isPythonFunctionCall(PsiElement element) {
        String text = element.getText();
        return text.contains("(") && !text.startsWith("def ");
    }

    private static class ImportInfo {
        String fromModule;
        String importedName;
        String alias;

        ImportInfo(String fromModule, String importedName, String alias) {
            this.fromModule = fromModule;
            this.importedName = importedName;
            this.alias = alias;
        }
    }

    private List<ImportInfo> collectImports(PsiFile file) {
        List<ImportInfo> imports = new ArrayList<>();
        file.accept(new PsiRecursiveElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                String text = element.getText();
                if (text.startsWith("from ")) {
                    // 处理 from ... import ... 语句
                    processFromImport(text, imports);
                } else if (text.startsWith("import ")) {
                    // 处理 import ... 语句
                    processImport(text, imports);
                }
                super.visitElement(element);
            }
        });
        return imports;
    }

    private void processFromImport(String importText, List<ImportInfo> imports) {
        // 移除 'from ' 前缀
        String content = importText.substring(5).trim();
        int importIndex = content.indexOf(" import ");
        if (importIndex > 0) {
            String moduleName = content.substring(0, importIndex).trim();
            String importedItems = content.substring(importIndex + 8).trim();

            // 处理多个导入项
            for (String item : importedItems.split(",")) {
                item = item.trim();
                String importedName = item;
                String alias = null;

                // 处理别名
                int asIndex = item.indexOf(" as ");
                if (asIndex > 0) {
                    importedName = item.substring(0, asIndex).trim();
                    alias = item.substring(asIndex + 4).trim();
                }

                imports.add(new ImportInfo(moduleName, importedName, alias));
            }
        }
    }

    private void processImport(String importText, List<ImportInfo> imports) {
        // 移除 'import ' 前缀
        String content = importText.substring(7).trim();

        // 处理多个导入
        for (String item : content.split(",")) {
            item = item.trim();
            String importedName = item;
            String alias = null;

            // 处理别名
            int asIndex = item.indexOf(" as ");
            if (asIndex > 0) {
                importedName = item.substring(0, asIndex).trim();
                alias = item.substring(asIndex + 4).trim();
            }

            imports.add(new ImportInfo(null, importedName, alias));
        }
    }

    private PsiElement resolveImportedFunction(ImportInfo importInfo, String callName) {
        // 获取项目根目录
        VirtualFile projectRoot = project.getBaseDir();
        if (projectRoot == null) {
            return null;
        }

        // 构建可能的模块路径
        String modulePath = importInfo.fromModule != null ?
                importInfo.fromModule.replace(".", "/") :
                importInfo.importedName.replace(".", "/");

        // 尝试不同的可能路径
        List<String> possiblePaths = Arrays.asList(
                modulePath + ".py",
                modulePath + "/__init__.py"
        );

        for (String path : possiblePaths) {
            VirtualFile moduleFile = projectRoot.findFileByRelativePath(path);
            if (moduleFile != null) {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(moduleFile);
                if (psiFile != null) {
                    // 在模块文件中查找函数
                    PsiElement[] functions = findFunctionsInFile(psiFile,
                            importInfo.fromModule != null ? importInfo.importedName : callName);
                    if (functions.length > 0) {
                        return functions[0];
                    }
                }
            }
        }

        return null;
    }

    private PsiElement findLocalDefinition(PsiElement scope, String name) {
        AtomicReference<PsiElement> result = new AtomicReference<>();
        scope.accept(new PsiRecursiveElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (result.get() != null) {
                    return;
                }
                if (isPythonFunction(element) &&
                        extractFunctionName(element).equals(name)) {
                    result.set(element);
                }
                super.visitElement(element);
            }
        });
        return result.get();
    }

    private String extractCallName(PsiElement callExpression) {
        String text = callExpression.getText();
        int parenIndex = text.indexOf('(');
        if (parenIndex > 0) {
            // 处理方法调用 (例如: obj.method())
            String name = text.substring(0, parenIndex).trim();
            int lastDot = name.lastIndexOf('.');
            return lastDot > 0 ? name.substring(lastDot + 1) : name;
        }
        return null;
    }

    private PsiElement[] findFunctionsInFile(PsiFile file, String functionName) {
        List<PsiElement> functions = new ArrayList<>();
        file.accept(new PsiRecursiveElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (isPythonFunction(element)) {
                    String name = extractFunctionName(element);
                    if (functionName.equals(name)) {
                        functions.add(element);
                    }
                }
                super.visitElement(element);
            }
        });
        return functions.toArray(new PsiElement[0]);
    }


    private String removePythonComments(String sourceText) {
        // 移除Python风格的注释
        StringBuilder result = new StringBuilder();
        String[] lines = sourceText.split("\n");

        boolean inMultilineComment = false;

        for (String line : lines) {
            String trimmedLine = line.trim();

            // 处理多行注释
            if (trimmedLine.startsWith("'''") || trimmedLine.startsWith("\"\"\"")) {
                inMultilineComment = !inMultilineComment;
                continue;
            }

            if (!inMultilineComment) {
                // 移除单行注释
                int commentIndex = line.indexOf("#");
                if (commentIndex >= 0) {
                    line = line.substring(0, commentIndex);
                }

                if (!line.trim().isEmpty()) {
                    result.append(line).append("\n");
                }
            }
        }

        return result.toString();
    }

    private boolean shouldAnalyzeDeeper(int depth) {
        return depth < config.getMaxDepth();
    }
}