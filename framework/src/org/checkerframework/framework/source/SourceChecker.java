package org.checkerframework.framework.source;

/*>>>
import org.checkerframework.checker.compilermsgs.qual.CompilerMessageKey;
import org.checkerframework.checker.nullness.qual.*;
*/

import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.qual.TypeQualifiers;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.util.CFContext;
import org.checkerframework.framework.util.OptionConfiguration;
import org.checkerframework.javacutil.AbstractTypeProcessor;
import org.checkerframework.javacutil.AnnotationProvider;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.ElementUtils;
import org.checkerframework.javacutil.ErrorHandler;
import org.checkerframework.javacutil.ErrorReporter;
import org.checkerframework.javacutil.InternalUtils;
import org.checkerframework.javacutil.TreeUtils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Stack;
import java.util.regex.Pattern;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.Diagnostic.Kind;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;

/**
 * An abstract annotation processor designed for implementing a
 * source-file checker for a JSR-308 conforming compiler plug-in. It provides an
 * interface to {@code javac}'s annotation processing API, routines for error
 * reporting via the JSR 199 compiler API, and an implementation for using a
 * {@link SourceVisitor} to perform the type-checking.
 *
 * <p>
 *
 * TODO update: Subclasses must implement the following methods:
 *
 * <ul>
 *  <li>{link SourceChecker#getMessages} (for type-qualifier specific error messages)
 *  <li>{link SourceChecker#createSourceVisitor(CompilationUnitTree)} (for a custom {@link SourceVisitor})
 *  <li>{link SourceChecker#createFactory} (for a custom {@link AnnotatedTypeFactory})
 *  <li>{link SourceChecker#getSuppressWarningsKeys} (for honoring
 *      {link SuppressWarnings} annotations)
 * </ul>
 *
 * Most type-checker plug-ins will want to extend {@link BaseTypeChecker},
 * instead of this class.  Only checkers that require annotated types but not
 * subtype checking (e.g. for testing purposes) should extend this.
 * Non-type checkers (e.g. for enforcing coding styles) should extend
 * {@link AbstractProcessor} (or even this class) as the Checker Framework is
 * not designed for such checkers.
 */

@SupportedOptions({
    // When adding a new standard option:
    // 1. Add a brief blurb here about the use case
    //    and a pointer to one prominent use of the option.
    // 2. Update the Checker Framework manual:
    //     * introduction.tex contains an overview of all options, which
    //       should be in the same order as this source code file.
    //     * a specific section should contain a detailed discussion.


    ///
    /// Unsound checking: ignore some errors
    ///

    // Set inclusion/exclusion of type uses or definitions
    // org.checkerframework.framework.source.SourceChecker.shouldSkipUses and similar
    "skipUses",
    "onlyUses",
    "skipDefs",
    "onlyDefs",

    // A comma-separated list of warnings to suppress
    // org.checkerframework.framework.source.SourceChecker.createSuppressWarnings
    "suppressWarnings",

    // With each warning, in addition to the concrete error key,
    // output the suppress warning keys that can be used to
    // suppress that warning.
    "showSuppressWarningKeys",

    // Unsoundly ignore side effects
    "assumeSideEffectFree",

    // Whether to ignore all subtype tests for type arguments that
    // were inferred for a raw type
    // org.checkerframework.framework.type.TypeHierarchy.isSubtypeTypeArguments
    "ignoreRawTypeArguments",

    // The next ones *increase* rather than *decrease* soundness.
    // They will eventually be replaced by their complements.

    // TODO: Checking of bodies of @SideEffectFree, @Deterministic, and
    // @Pure methods is temporarily disabled unless -AcheckPurityAnnotations is
    // supplied on the command line.
    // Re-enable it after making the analysis more precise.
    // org.checkerframework.common.basetype.BaseTypeVisitor.visitMethod(MethodTree, Void)
    "checkPurityAnnotations",

    // TODO: Temporary option to make array subtyping invariant,
    // which will be the new default soon.
    "invariantArrays",

    // TODO:  Temporary option to make casts stricter, in particular when
    // casting to an array or generic type. This will be the new default soon.
    "checkCastElementType",

    // Whether to output errors or warnings only
    // org.checkerframework.framework.source.SourceChecker.report
    "warns",


    ///
    /// Type-checking modes:  enable/disable functionality
    ///

    // Lint options
    // org.checkerframework.framework.source.SourceChecker.getSupportedLintOptions() and similar
    "lint",

    // Whether to suggest methods that could be marked @SideEffectFree,
    // @Deterministic, or @Pure
    // org.checkerframework.common.basetype.BaseTypeVisitor.visitMethod(MethodTree, Void)
    "suggestPureMethods",

    // Whether to assume that assertions are enabled or disabled
    // org.checkerframework.framework.flow.CFCFGBuilder.CFCFGBuilder
    "assumeAssertionsAreEnabled",
    "assumeAssertionsAreDisabled",

    // Whether to assume sound concurrent semantics or
    // simplified sequential semantics
    // org.checkerframework.framework.flow.CFAbstractTransfer.sequentialSemantics
    "concurrentSemantics",

    // Whether to resolve reflective method invocations
    // resolveReflection=debug cause debugging information
    // to be output.
    "resolveReflection",


    ///
    /// Stub libraries
    ///

    // Additional stub files to use
    // org.checkerframework.framework.type.AnnotatedTypeFactory.buildIndexTypes()
    "stubs",
    // Whether to print warnings about types/members in a stub file
    // that were not found on the class path
    // org.checkerframework.framework.stub.StubParser.warnIfNotFound
    "stubWarnIfNotFound",

    ///
    /// Debugging
    ///

    /// Amount of detail in messages

    // Whether to print @InvisibleQualifier marked annotations
    // org.checkerframework.framework.type.AnnotatedTypeMirror.toString()
    "printAllQualifiers",

    // Print qualifier parameters using annotations instead of the <<Q>> format.
    "printQualifierParametersAsAnnotations",

    // Output detailed message in simple-to-parse format, useful
    // for tools parsing Checker Framework output.
    // org.checkerframework.framework.source.SourceChecker.message(Kind, Object, String, Object...)
    "detailedmsgtext",

    // Whether to output a stack trace for a framework error
    // org.checkerframework.framework.source.SourceChecker.logCheckerError
    "printErrorStack",

    // Only output error code, useful for testing framework
    // org.checkerframework.framework.source.SourceChecker.message(Kind, Object, String, Object...)
    "nomsgtext",

    /// Stub and JDK libraries

    // Ignore the standard jdk.astub file; primarily for testing or debugging.
    // org.checkerframework.framework.type.AnnotatedTypeFactory.buildIndexTypes()
    "ignorejdkastub",

    // Whether to check that the annotated JDK is correctly provided
    // org.checkerframework.common.basetype.BaseTypeVisitor.checkForAnnotatedJdk()
    "nocheckjdk",

    // Whether to print debugging messages while processing the stub files
    // org.checkerframework.framework.stub.StubParser.debugStubParser
    "stubDebug",

    /// Progress tracing

    // Output file names before checking
    // TODO: it looks like support for this was lost!
    "filenames",

    // Output all subtyping checks
    // org.checkerframework.common.basetype.BaseTypeVisitor
    "showchecks",

    /// Miscellaneous debugging options

    // Directory for .dot files generated from the CFG
    // org.checkerframework.framework.type.GenericAnnotatedTypeFactory.analyze
    "flowdotdir",

    // Enable additional output in the flow .dot files.
    "verbosecfg",

    // Whether to output resource statistics at JVM shutdown
    // org.checkerframework.framework.source.SourceChecker.shutdownHook()
    "resourceStats",

})
public abstract class SourceChecker
    extends AbstractTypeProcessor implements ErrorHandler, CFContext, OptionConfiguration {

    // TODO A checker should export itself through a separate interface,
    // and maybe have an interface for all the methods for which it's safe
    // to override.

    /** The @SuppressWarnings key that will suppress warnings for all checkers. */
    protected static final String SUPPRESS_ALL_KEY = "all";

    /** File name of the localized messages. */
    protected static final String MSGS_FILE = "messages.properties";

    /** Maps error keys to localized/custom error messages. */
    protected Properties messages;

    /** Used to report error messages and warnings via the compiler. */
    protected Messager messager;

    /** Used as a helper for the {@link SourceVisitor}. */
    protected Trees trees;

    /** The source tree that is being scanned. */
    protected CompilationUnitTree currentRoot;

    // If an error is detected in a CompilationUnitTree, skip
    // all future calls of typeProcess with that same CompilationUnitTree.
    private CompilationUnitTree previousErrorCompilationUnit;

    /** The visitor to use. */
    protected SourceVisitor<?, ?> visitor;

    /** Keys for warning suppressions specified on the command line */
    private String /*@Nullable*/ [] suppressWarnings;

    /**
     * Regular expression pattern to specify Java classes that are not
     * annotated, so warnings about uses of them should be suppressed.
     *
     * It contains the pattern specified by the user, through the option
     * {@code checkers.skipUses}; otherwise it contains a pattern that can
     * match no class.
     */
    private Pattern skipUsesPattern;

    /**
     * Regular expression pattern to specify Java classes that are
     * annotated, so warnings about them should be issued but warnings
     * about all other classes should be suppressed.
     *
     * It contains the pattern specified by the user, through the option
     * {@code checkers.onlyUses}; otherwise it contains a pattern
     * matches every class.
     */
    private Pattern onlyUsesPattern;

    /**
     * Regular expression pattern to specify Java classes whose
     * definition should not be checked.
     *
     * It contains the pattern specified by the user, through the option
     * {@code checkers.skipDefs}; otherwise it contains a pattern that can
     * match no class.
     */
    private Pattern skipDefsPattern;

    /**
     * Regular expression pattern to specify Java classes whose
     * definition should be checked.
     *
     * It contains the pattern specified by the user, through the option
     * {@code checkers.onlyDefs}; otherwise it contains a pattern that
     * matches every class.
     */
    private Pattern onlyDefsPattern;

    /** The supported lint options */
    private Set<String> supportedLints;

    /** The enabled lint options */
    private Set<String> activeLints;

    /** The active options for this checker.
     * This is a processed version of {@link ProcessingEnvironment#getOptions()}:
     * If the option is of the form "-ACheckerName@key=value" and the current checker class,
     * or one of its superclasses is named "CheckerName", then add key -> value.
     * If the option is of the form "-ACheckerName@key=value" and the current checker class,
     * and none of its superclasses is named "CheckerName", then do not add key -> value.
     * If the option is of the form "-Akey=value", then add key -> value.
     *
     * Both the simple and the canonical name of the checker can be used.
     * Superclasses of the current checker are also considered.
     */
    private Map<String, String> activeOptions;

    // The string that separates the checker name from the option name.
    // This string may only consist of valid Java identifier part characters,
    // because it will be used within the key of an option.
    private final static String OPTION_SEPARATOR = "_";

    /** The line separator */
    private final static String LINE_SEPARATOR = System.getProperty("line.separator").intern();

    @Override
    public final void init(ProcessingEnvironment env) {
        super.init(env);
        // The processingEnvironment field will also be set by the superclass' init method.
        // This is used to trigger AggregateChecker's setProcessingEnvironment.
        setProcessingEnvironment(env);
    }

    /**
     * @return the {@link ProcessingEnvironment} that was supplied to this
     *         checker
     */
    @Override   // from CFChecker
    public ProcessingEnvironment getProcessingEnvironment() {
        return this.processingEnv;
    }

    /* This method is protected only to allow the AggregateChecker and BaseTypeChecker to call it. */
    protected void setProcessingEnvironment(ProcessingEnvironment env) {
        this.processingEnv = env;
    }

    /** @return the {@link CFContext} used by this checker */
    public CFContext getContext() {
        return this;
    }

    @Override
    public SourceChecker getChecker() {
        return this;
    }

    @Override
    public OptionConfiguration getOptionConfiguration() {
        return this;
    }

    // getProcessingEnvironment is defined above.

    @Override
    public Elements getElementUtils() {
        return getProcessingEnvironment().getElementUtils();
    }

    @Override
    public Types getTypeUtils() {
        return getProcessingEnvironment().getTypeUtils();
    }

    @Override
    public Trees getTreeUtils() {
        return Trees.instance(getProcessingEnvironment());
    }

    @Override
    public SourceVisitor<?, ?> getVisitor() {
        return this.visitor;
    }

    /**
     * Provides the {@link SourceVisitor} that the checker should use to scan
     * input source trees.
     *
     * @return a {@link SourceVisitor} to use to scan source trees
     */
    protected abstract SourceVisitor<?, ?> createSourceVisitor();

    @Override
    public AnnotationProvider getAnnotationProvider() {
        throw new UnsupportedOperationException("getAnnotationProvider is not implemented for this class.");
    }

    /**
     * Provides a mapping of error keys to custom error messages.
     * <p>
     * As a default, this implementation builds a {@link Properties} out of
     * file {@code messages.properties}.  It accumulates all the properties files
     * in the Java class hierarchy from the checker up to {@code SourceChecker}.
     * This permits subclasses to inherit default messages while being able to
     * override them.
     *
     * @return a {@link Properties} that maps error keys to error message text
     */
    public Properties getMessages() {
        if (this.messages != null)
            return this.messages;

        this.messages = new Properties();
        Stack<Class<?>> checkers = new Stack<Class<?>>();

        Class<?> currClass = this.getClass();
        while (currClass != SourceChecker.class) {
            checkers.push(currClass);
            currClass = currClass.getSuperclass();
        }
        checkers.push(SourceChecker.class);

        while (!checkers.empty())
            messages.putAll(getProperties(checkers.pop(), MSGS_FILE));
        return this.messages;
    }

    private Pattern getSkipPattern(String patternName, Map<String, String> options) {
        // Default is an illegal Java identifier substring
        // so that it won't match anything.
        // Note that AnnotatedType's toString output format contains characters such as "():{}".
        return getPattern(patternName, options, "\\]'\"\\]");
    }

    private Pattern getOnlyPattern(String patternName, Map<String, String> options) {
        // default matches everything
        return getPattern(patternName, options, ".");
    }

    private Pattern getPattern(String patternName, Map<String, String> options, String defaultPattern) {
        String pattern = "";

        if (options.containsKey(patternName))
            pattern = options.get(patternName);
        else if (System.getProperty("checkers." + patternName) != null)
            pattern = System.getProperty("checkers." + patternName);
        else if (System.getenv(patternName) != null)
            pattern = System.getenv(patternName);

        if (pattern.indexOf("/") != -1) {
            message(Kind.WARNING,
              "The " + patternName + " property contains \"/\", which will never match a class name: " + pattern);
        }

        if (pattern.equals(""))
            pattern = defaultPattern;

        return Pattern.compile(pattern);
    }

    private Pattern getSkipUsesPattern(Map<String, String> options) {
        return getSkipPattern("skipUses", options);
    }

    private Pattern getOnlyUsesPattern(Map<String, String> options) {
        return getOnlyPattern("onlyUses", options);
    }

    private Pattern getSkipDefsPattern(Map<String, String> options) {
        return getSkipPattern("skipDefs", options);
    }

    private Pattern getOnlyDefsPattern(Map<String, String> options) {
        return getOnlyPattern("onlyDefs", options);
    }

    // TODO: do we want this?
    // Cache the keys that we already warned about to prevent repetitions.
    // private Set<String> warnedOnLint = new HashSet<String>();

    private Set<String> createActiveLints(Map<String, String> options) {
        if (!options.containsKey("lint"))
            return Collections.emptySet();

        String lintString = options.get("lint");
        if (lintString == null) {
            return Collections.singleton("all");
        }

        Set<String> activeLint = new HashSet<String>();
        for (String s : lintString.split(",")) {
            if (!this.getSupportedLintOptions().contains(s) &&
                    !(s.charAt(0) == '-' && this.getSupportedLintOptions().contains(s.substring(1))) &&
                    !s.equals("all") &&
                    !s.equals("none") /*&&
                    !warnedOnLint.contains(s)*/) {
                this.messager.printMessage(javax.tools.Diagnostic.Kind.WARNING,
                        "Unsupported lint option: " + s + "; All options: " + this.getSupportedLintOptions());
                // warnedOnLint.add(s);
            }

            activeLint.add(s);
            if (s.equals("none"))
                activeLint.add("-all");
        }

        return Collections.unmodifiableSet(activeLint);
    }

    private Map<String, String> createActiveOptions(Map<String, String> options) {
        if (options.isEmpty())
            return Collections.emptyMap();

        Map<String, String> activeOpts = new HashMap<String, String>();

        for (Map.Entry<String, String> opt : options.entrySet()) {
            String key = opt.getKey();
            String value = opt.getValue();

            String[] split = key.split(OPTION_SEPARATOR);

            switch (split.length) {
            case 1:
                // No separator, option always active
                activeOpts.put(key, value);
                break;
            case 2:
                // Valid class-option pair
                Class<?> clazz = this.getClass();

                do {
                    if (clazz.getCanonicalName().equals(split[0]) ||
                            clazz.getSimpleName().equals(split[0])) {
                        activeOpts.put(split[1], value);
                    }

                    clazz = clazz.getSuperclass();
                } while (clazz != null && !clazz.getName().equals(org.checkerframework.javacutil.AbstractTypeProcessor.class.getCanonicalName()));
                break;
            default:
                ErrorReporter.errorAbort("Invalid option name: " + key +
                        " At most one separator " + OPTION_SEPARATOR + " expected, but found " + split.length);
            }
        }
        return Collections.unmodifiableMap(activeOpts);
    }

    private String /*@Nullable*/ [] createSuppressWarnings(Map<String, String> options) {
        if (!options.containsKey("suppressWarnings"))
            return null;

        String swString = options.get("suppressWarnings");
        if (swString == null) {
            return null;
        }

        return swString.split(",");
    }


    /**
     * Exception type used only internally to abort
     * processing.
     * Only public to allow tests.AnnotationBuilderTest;
     * this class should be private. TODO: nicer way?
     */
    @SuppressWarnings("serial")
    public static class CheckerError extends RuntimeException {
        // Whether this error is caused by a user error, e.g. incorrect command-line arguments.
        public final boolean userError;

        public CheckerError(String msg, Throwable cause, boolean userError) {
            super(msg, cause);
            this.userError = userError;
        }
    }

    /**
     * Log an error message and abort processing.
     * Call this method instead of raising an exception.
     *
     * @param msg The error message to log.
     */
    @Override
    public void errorAbort(String msg) {
        throw new CheckerError(msg, new Throwable(), false);
    }


    /**
     * Log an error message and abort processing.
     * Call this method instead of raising an exception.
     *
     * @param msg The error message to log.
     * @param cause The original error cause.
     */
    @Override
    public void errorAbort(String msg, Throwable cause) {
        throw new CheckerError(msg, cause, false);
    }

    /**
     * Log a user error message and abort processing.
     * Call this method instead of raising an exception or
     * using System.out.
     * In contrast to {@link SourceChecker#errorAbort(String)} this method
     * presents a more user-friendly output.
     *
     * @param msg The error message to log.
     */
    public void userErrorAbort(String msg) {
        throw new CheckerError(msg, new Throwable(), true);
    }

    private void logCheckerError(CheckerError ce) {
        if (ce.getMessage() == null) {
            final String stackTrace = formatStackTrace(ce.getStackTrace());
            ErrorReporter.errorAbort("Null error message while logging Checker error.\nStack Trace:\n" + stackTrace);
        }

        StringBuilder msg = new StringBuilder(ce.getMessage());
        if ((processingEnv == null ||
                processingEnv.getOptions() == null ||
                processingEnv.getOptions().containsKey("printErrorStack")) &&
                ce.getCause() != null) {

            if (this.currentRoot != null &&
                    this.currentRoot.getSourceFile() != null) {
                msg.append("\nCompilation unit: " + this.currentRoot.getSourceFile().getName());
            }

            msg.append("\nException: " +
                            ce.getCause().toString() + "; " + formatStackTrace(ce.getCause().getStackTrace()));
            Throwable cause = ce.getCause().getCause();
            while (cause != null) {
                msg.append("\nUnderlying Exception: " +
                                (cause.toString() + "; " +
                                        formatStackTrace(cause.getStackTrace())));
                cause = cause.getCause();
            }
        } else {
            if (ce.userError) {
                msg.append('.');
            } else {
                msg.append("; invoke the compiler with -AprintErrorStack to see the stack trace.");
            }
        }

        if (this.messager == null) {
            messager = processingEnv.getMessager();
        }
        this.messager.printMessage(javax.tools.Diagnostic.Kind.ERROR, msg);
    }

    /**
     * {@inheritDoc}
     *
     * Type-checkers are not supposed to override this.
     * Instead use initChecker.
     * This allows us to handle CheckerError only here and doesn't
     * require all overriding implementations to be aware of CheckerError.
     *
     * @see AbstractProcessor#init(ProcessingEnvironment)
     * @see SourceChecker#initChecker()
     */
    @Override
    public void typeProcessingStart() {
        try {
            super.typeProcessingStart();
            initChecker();
            if (this.messager == null) {
                messager = processingEnv.getMessager();
                messager.printMessage(
                        javax.tools.Diagnostic.Kind.WARNING,
                        "You have forgotten to call super.initChecker in your " +
                                "subclass of SourceChecker, " + this.getClass() +
                                "! Please ensure your checker is properly initialized.");
            }
            if (shouldAddShutdownHook()) {
                Runtime.getRuntime().addShutdownHook(new Thread() {
                    @Override
                    public void run() {
                        shutdownHook();
                    }
                });
            }
        } catch (CheckerError ce) {
            logCheckerError(ce);
        } catch (Throwable t) {
            logCheckerError(wrapThrowableAsCheckerError("SourceChecker.typeProcessingStart", t, null));
        }
    }

    /**
     * Initialize the checker.
     *
     * @see AbstractProcessor#init(ProcessingEnvironment)
     */
    public void initChecker() {
        // Grab the Trees and Messager instances now; other utilities
        // (like Types and Elements) can be retrieved by subclasses.
        /*@Nullable*/ Trees trees = Trees.instance(processingEnv);
        assert trees != null; /*nninvariant*/
        this.trees = trees;

        this.messager = processingEnv.getMessager();
        this.messages = getMessages();

        this.visitor = createSourceVisitor();

        // TODO: hack to clear out static caches.
        // When the {@link org.checkerframework.qualframework.util.QualifierContext}
        // gets used by all utilities, this shouldn't be an issue anymore.
        AnnotationUtils.clear();
    }

    /**
     * Return true to indicate that method {@link #shutdownHook} should be
     * added as a shutdownHook of the JVM.
     */
    protected boolean shouldAddShutdownHook() {
        return getOptions().containsKey("resourceStats");
    }

    /**
     * Method that gets called exactly once at shutdown time of the JVM.
     * Checkers can override this method to customize the behavior.
     */
    protected void shutdownHook() {
        if (getOptions().containsKey("resourceStats")) {
            // Check for the "resourceStats" option and don't call shouldAddShutdownHook
            // to allow subclasses to override shouldXXX and shutdownHook and simply
            // call the super implementations.
            printStats();
        }
    }

    /** Print resource usage statistics */
    protected void printStats() {
        List<MemoryPoolMXBean> memoryPools = ManagementFactory.getMemoryPoolMXBeans();
        for (MemoryPoolMXBean memoryPool : memoryPools) {
            System.out.println("Memory pool " + memoryPool.getName() + " statistics");
            System.out.println("  Pool type: " + memoryPool.getType());
            System.out.println("  Peak usage: " + memoryPool.getPeakUsage());
        }
    }

    // Output the warning about source level at most once.
    private boolean warnedAboutSourceLevel = false;

    // The number of errors at the last exit of the type processor.
    // At entry to the type processor we check whether the current error count is
    // higher and then don't process the file, as it contains some Java errors.
    // Needs to be protected to allow access from AggregateChecker and BaseTypeChecker.
    protected int errsOnLastExit = 0;

    /**
     * Type-check the code with Java specifications and then runs the Checker
     * Rule Checking visitor on the processed source.
     *
     * @see Processor#process(Set, RoundEnvironment)
     */
    @Override
    public void typeProcess(TypeElement e, TreePath p) {
        if (e == null) {
            messager.printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Refusing to process empty TypeElement");
            return;
        }
        if (p == null) {
            messager.printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Refusing to process empty TreePath in TypeElement: " + e);
            return;
        }

        Context context = ((JavacProcessingEnvironment)processingEnv).getContext();
        com.sun.tools.javac.code.Source source = com.sun.tools.javac.code.Source.instance(context);
        if ((! warnedAboutSourceLevel) && (! source.allowTypeAnnotations())) {
            messager.printMessage(javax.tools.Diagnostic.Kind.WARNING,
                                  "-source " + source.name + " does not support type annotations");
            warnedAboutSourceLevel = true;
        }

        Log log = Log.instance(context);
        if (log.nerrors > this.errsOnLastExit) {
            this.errsOnLastExit = log.nerrors;
            previousErrorCompilationUnit = p.getCompilationUnit();
            return;
        }
        if (p.getCompilationUnit() == previousErrorCompilationUnit) {
            // If the same compilation unit was seen with an error before,
            // skip it. This is in particular necessary for Java errors, which
            // show up once, but further calls to typeProcess will happen.
            // See Issue 346.
            return;
        } else {
            previousErrorCompilationUnit = null;
        }
        if (p.getCompilationUnit() != currentRoot) {
            currentRoot = p.getCompilationUnit();
            visitor.setRoot(currentRoot);
        }

        // Visit the attributed tree.
        try {
            visitor.visit(p);
        } catch (CheckerError ce) {
            logCheckerError(ce);
        } catch (Throwable t) {
            logCheckerError(wrapThrowableAsCheckerError("SourceChecker.typeProcess", t, p));
        } finally {
            // Also add possibly deferred diagnostics, which will get published back in
            // AbstractTypeProcessor.
            this.errsOnLastExit = log.nerrors;
        }
    }

    private CheckerError wrapThrowableAsCheckerError(String where, Throwable t, /*@Nullable*/ TreePath p) {
        return new CheckerError(
           where + ": unexpected Throwable (" +
           t.getClass().getSimpleName() + ")" +
           ((p == null) ? "" : " while processing " + p.getCompilationUnit().getSourceFile().getName()) +
           (t.getMessage() == null ? "" : "; message: " + t.getMessage()),
           t,
           false);
    }

    /**
     * Format a list of {@link StackTraceElement}s to be printed out as an error
     * message.
     */
    protected String formatStackTrace(StackTraceElement[] stackTrace) {
        boolean first = true;
        StringBuilder sb = new StringBuilder();
        if (stackTrace.length == 0) {
            sb.append("no stack trace available.");
        } else {
            sb.append("Stack trace: ");
        }
        for (StackTraceElement ste : stackTrace) {
            if (!first) {
                sb.append("\n");
            }
            first = false;
            sb.append(ste.toString());
        }
        return sb.toString();
    }

    // Uses private fields, need to rewrite.
    // public void dumpState() {
    //     System.out.printf("SourceChecker = %s%n", this);
    //     System.out.printf("  env = %s%n", env);
    //     System.out.printf("    env.elementUtils = %s%n", ((JavacProcessingEnvironment) env).elementUtils);
    //     System.out.printf("      env.elementUtils.types = %s%n", ((JavacProcessingEnvironment) env).elementUtils.types);
    //     System.out.printf("      env.elementUtils.enter = %s%n", ((JavacProcessingEnvironment) env).elementUtils.enter);
    //     System.out.printf("    env.typeUtils = %s%n", ((JavacProcessingEnvironment) env).typeUtils);
    //     System.out.printf("  trees = %s%n", trees);
    //     System.out.printf("    trees.enter = %s%n", ((com.sun.tools.javac.api.JavacTrees) trees).enter);
    //     System.out.printf("    trees.elements = %s%n", ((com.sun.tools.javac.api.JavacTrees) trees).elements);
    //     System.out.printf("      trees.elements.types = %s%n", ((com.sun.tools.javac.api.JavacTrees) trees).elements.types);
    //     System.out.printf("      trees.elements.enter = %s%n", ((com.sun.tools.javac.api.JavacTrees) trees).elements.enter);
    // }

    /**
     * Returns the localized long message corresponding for this key, and
     * returns the defValue if no localized message is found.
     *
     */
    protected String fullMessageOf(String messageKey, String defValue) {
        String key = messageKey;

        do {
            if (messages.containsKey(key)) {
                return messages.getProperty(key);
            }

            int dot = key.indexOf('.');
            if (dot < 0) return defValue;
            key = key.substring(dot + 1);
        } while (true);
    }

    /**
     * Prints a message (error, warning, note, etc.) via JSR-269.
     *
     * @param kind
     *            the type of message to print
     * @param source
     *            the object from which to obtain source position information
     * @param msgKey
     *            the message key to print
     * @param args
     *            arguments for interpolation in the string corresponding to the
     *            given message key
     * @see Diagnostic
     * @throws IllegalArgumentException
     *             if {@code source} is neither a {@link Tree} nor an
     *             {@link Element}
     */
    public void message(Diagnostic.Kind kind, Object source, /*@CompilerMessageKey*/ String msgKey,
            Object... args) {

        assert messages != null : "null messages";

        if (args != null) {
            for (int i = 0; i < args.length; ++i) {
                if (args[i] == null)
                    continue;

                // Try to process the arguments
                args[i] = processArg(args[i]);
            }
        }

        if (kind == Diagnostic.Kind.NOTE) {
            System.err.println("(NOTE) " + String.format(msgKey, args));
            return;
        }

        final String defaultFormat = String.format("(%s)", msgKey);
        String fmtString;
        if (this.processingEnv.getOptions() != null /*nnbug*/
                && this.processingEnv.getOptions().containsKey("nomsgtext")) {
            fmtString = defaultFormat;
        } else if (this.processingEnv.getOptions() != null /*nnbug*/
                && this.processingEnv.getOptions().containsKey("detailedmsgtext")) {
            // The -Adetailedmsgtext command-line option was given, so output
            // a stylized error message for easy parsing by a tool.

            StringBuilder sb = new StringBuilder();

            // The parts, separated by " $$ " (DETAILS_SEPARATOR), are:

            // (1) error key
            // TODO: should we also have some type system identifier here?
            // E.g. Which subclass of SourceChecker we are? Or also the SuppressWarnings keys?
            sb.append(defaultFormat);
            sb.append(DETAILS_SEPARATOR);

            // (2) number of additional tokens, and those tokens; this
            // depends on the error message, and an example is the found
            // and expected types
            if (args != null) {
                sb.append(args.length);
                sb.append(DETAILS_SEPARATOR);
                for (Object arg : args) {
                    sb.append(arg);
                    sb.append(DETAILS_SEPARATOR);
                }
            } else {
                // Output 0 for null arguments.
                sb.append(0);
                sb.append(DETAILS_SEPARATOR);
            }

            // (3) The error position, as starting and ending characters in
            // the source file.
            final Tree tree;
            if (source instanceof Element) {
                tree = trees.getTree( (Element) source );
            } else if (source instanceof Tree) {
                tree = (Tree) source;
            } else {
                tree = null;
            }
            sb.append( treeToFilePositionString( tree, currentRoot, processingEnv ) );
            sb.append(DETAILS_SEPARATOR);

            // (4) The human-readable error message.
            sb.append(fullMessageOf(msgKey, defaultFormat));

            fmtString = sb.toString();

        } else {
            final String suppressing;
            if (this.processingEnv.getOptions().containsKey("showSuppressWarningKeys")) {
                suppressing = String.format("[%s:%s] ", this.getSuppressWarningsKeys(), msgKey);
            } else {
                suppressing = String.format("[%s] ", msgKey);
            }
            fmtString = suppressing + fullMessageOf(msgKey, defaultFormat);
        }
        String messageText;
        try {
            messageText = String.format(fmtString, args);
        } catch (Exception e) {
            messageText = "Invalid format string: \"" + fmtString + "\" args: " + Arrays.toString(args);
        }

        // Replace '\n' with the proper line separator
        if (LINE_SEPARATOR != "\n") // interned
            messageText = messageText.replaceAll("\n", LINE_SEPARATOR);

        if (source instanceof Element)
            messager.printMessage(kind, messageText, (Element) source);
        else if (source instanceof Tree)
            Trees.instance(processingEnv).printMessage(kind, messageText, (Tree) source,
                    currentRoot);
        else
            ErrorReporter.errorAbort("invalid position source: "
                    + source.getClass().getName());
    }

    /**
     * Process an argument to an error message before it is passed to String.format.
     * @param arg the argument
     * @return the result after processing
     */
    protected Object processArg(Object arg) {
        // Check to see if the argument itself is a property to be expanded
        return messages.getProperty(arg.toString(), arg.toString());
    }

    /**
     * Print a non-localized message using the javac messager.
     * This is preferable to using System.out or System.err, but should
     * only be used for exceptional cases that don't happen in correct usage.
     * Localized messages should be raised using
     * {@link SourceChecker#message(Diagnostic.Kind, Object, String, Object...)}.
     *
     * @param kind The kind of message to print.
     * @param msg The message text.
     * @param args Optional arguments to substitute in the message.
     *
     * @see SourceChecker#message(Diagnostic.Kind, Object, String, Object...)
     */
    public void message(Diagnostic.Kind kind, String msg, Object... args) {
        if (messager != null) {
            messager.printMessage(kind, String.format(msg, args));
        } else {
            System.err.println(kind + ": " + String.format(msg, args));
        }
    }

    /**
     * For the given tree, compute the source positions for that tree.  Return a "tuple" like string
     * (e.g. "( 1, 200 )" ) that contains the start and end position of the tree in the current compilation unit.
     *
     * @param tree Tree to locate within the current compilation unit
     * @param currentRoot The current compilation unit
     * @param processingEnv The current processing environment
     * @return A tuple string representing the range of characters that tree occupies in the source file
     */
    public String treeToFilePositionString(Tree tree, CompilationUnitTree currentRoot, ProcessingEnvironment processingEnv) {
        if (tree == null) {
            return null;
        }

        SourcePositions sourcePositions = trees.getSourcePositions();
        long start = sourcePositions.getStartPosition( currentRoot, tree);
        long end   = sourcePositions.getEndPosition( currentRoot, tree );

        return "( " + start + ", " + end  + " )";
    }

    public static final String DETAILS_SEPARATOR = " $$ ";

    /**
     * Determines whether an error (whose error key is {@code err}) should
     * be suppressed, according to the user's explicitly-written
     * SuppressWarnings annotation {@code anno} or the -AsuppressWarnings
     * command-line argument.
     * <p>
     *
     * A @SuppressWarnings value may be of the following pattern:
     *
     * <ol>
     * <li>{@code "suppress-key"}, where suppress-key is a supported warnings
     * key, as specified by {@link #getSuppressWarningsKey()}
     * (e.g., {@code "nullness"} for Nullness, {@code "igj"} for IGJ)</li>
     *
     * <li>{@code "suppress-key:error-key}, where the suppress-key
     * is as above, and error-key is a prefix of the errors
     * that it may suppress.  So "nullness:generic.argument", would
     * suppress any errors in the Nullness Checker related to
     * generic.argument.</li>
     * </ol>
     *
     * @param anno  the @SuppressWarnings annotation written by the user
     * @param err   the error key the checker is emitting
     * @return true if one of {@code annos} is a {@link SuppressWarnings}
     *         annotation with the key returned by {@link
     *         SourceChecker#getSuppressWarningsKey}
     */
    private boolean checkSuppressWarnings(/*@Nullable*/ SuppressWarnings anno, String err) {

        // Don't suppress warnings if this checker provides no key to do so.
        Collection<String> checkerSwKeys = this.getSuppressWarningsKeys();
        if (checkerSwKeys.isEmpty())
            return false;

        String[] userSwKeys = (anno == null ? null : anno.value());
        if (this.suppressWarnings == null) {
            this.suppressWarnings = createSuppressWarnings(getOptions());
        }
        String[] cmdLineSwKeys = this.suppressWarnings;

        return (checkSuppressWarnings(userSwKeys, err)
                || checkSuppressWarnings(cmdLineSwKeys, err));
    }

    /**
     * Return true if the given error should be suppressed, based on the
     * user-supplied @SuppressWarnings keys.
     */
    private boolean checkSuppressWarnings(String /*@Nullable*/ [] userSwKeys, String err) {
        if (userSwKeys == null)
            return false;

        Collection<String> checkerSwKeys = this.getSuppressWarningsKeys();

        // Check each value of the user-written @SuppressWarnings annotation.
        for (String suppressWarningValue : userSwKeys) {
            for (String checkerKey : checkerSwKeys) {
                if (suppressWarningValue.equalsIgnoreCase(checkerKey))
                    return true;

                String expected = checkerKey + ":" + err;
                if (expected.toLowerCase().contains(suppressWarningValue.toLowerCase()))
                    return true;
            }
        }

        return false;
    }

    /**
     * Determines whether all the warnings pertaining to a given tree
     * should be suppressed.  Returns true if the tree is within the scope
     * of a @SuppressWarnings annotation, one of whose values suppresses
     * the checker's warnings.  The list of keys that suppress a checker's
     * wornings is provided by the {@link
     * SourceChecker#getSuppressWarningsKey} method.
     *
     * @param tree the tree that might be a source of a warning
     * @return true if no warning should be emitted for the given tree because
     *         it is contained by a declaration with an appropriately-valued
     *         @SuppressWarnings annotation; false otherwise
     */
    private boolean shouldSuppressWarnings(Tree tree, String err) {

        // Don't suppress warnings if this checker provides no key to do so.
        Collection<String> checkerKeys = this.getSuppressWarningsKeys();
        if (checkerKeys.isEmpty())
            return false;

        /*@Nullable*/ TreePath path = trees.getPath(this.currentRoot, tree);
        if (path == null)
            return false;

        /*@Nullable*/ VariableTree var = TreeUtils.enclosingVariable(path);
        if (var != null && shouldSuppressWarnings(InternalUtils.symbol(var), err))
            return true;

        /*@Nullable*/ MethodTree method = TreeUtils.enclosingMethod(path);
        if (method != null && shouldSuppressWarnings(InternalUtils.symbol(method), err))
            return true;

        /*@Nullable*/ ClassTree cls = TreeUtils.enclosingClass(path);
        if (cls != null && shouldSuppressWarnings(InternalUtils.symbol(cls), err))
            return true;

        return false;
    }

    private boolean shouldSuppressWarnings(/*@Nullable*/ Element elt, String err) {

        if (elt == null)
            return false;

        return checkSuppressWarnings(elt.getAnnotation(SuppressWarnings.class), err)
                || shouldSuppressWarnings(elt.getEnclosingElement(), err);
    }

    /**
     * Reports a result. By default, it prints it to the screen via the
     * compiler's internal messenger if the result is non-success; otherwise,
     * the method returns with no side-effects.
     *
     * @param r
     *            the result to report
     * @param src
     *            the position object associated with the result
     */
    public void report(final Result r, final Object src) {

        String err = r.getMessageKeys().iterator().next();
        // TODO: SuppressWarnings checking for Elements
        if (src instanceof Tree && shouldSuppressWarnings((Tree)src, err))
            return;
        if (src instanceof Element && shouldSuppressWarnings((Element)src, err))
            return;

        if (r.isSuccess())
            return;

        for (Result.DiagMessage msg : r.getDiagMessages()) {
            if (r.isFailure())
                this.message(hasOption("warns") ? Diagnostic.Kind.MANDATORY_WARNING : Diagnostic.Kind.ERROR,
                        src, msg.getMessageKey(), msg.getArgs());
            else if (r.isWarning())
                this.message(Diagnostic.Kind.MANDATORY_WARNING, src, msg.getMessageKey(), msg.getArgs());
            else
                this.message(Diagnostic.Kind.NOTE, src, msg.getMessageKey(), msg.getArgs());
        }
    }

    /**
     * Determines the value of the lint option with the given name.  Just
     * as <a
     * href="https://docs.oracle.com/javase/7/docs/technotes/guides/javac/index.html">javac</a>
     * uses "-Xlint:xxx" to enable and "-Xlint:-xxx" to disable option xxx,
     * annotation-related lint options are enabled with "-Alint:xxx" and
     * disabled with "-Alint:-xxx".
     *
     * @throws IllegalArgumentException if the option name is not recognized
     *         via the {@link SupportedLintOptions} annotation or the {@link
     *         SourceChecker#getSupportedLintOptions} method
     * @param name the name of the lint option to check for
     * @return true if the lint option was given, false if it was not given or
     * was given prepended with a "-"
     *
     * @see SourceChecker#getLintOption(String, boolean)
     */
    public final boolean getLintOption(String name) {
        return getLintOption(name, false);
    }

    /**
     * Determines the value of the lint option with the given name.  Just
     * as <a
     * href="https://docs.oracle.com/javase/1.5.0/docs/tooldocs/solaris/javac.html">javac</a>
     * uses "-Xlint:xxx" to enable and "-Xlint:-xxx" to disable option xxx,
     * annotation-related lint options are enabled with "-Alint=xxx" and
     * disabled with "-Alint=-xxx".
     *
     * @throws IllegalArgumentException if the option name is not recognized
     *         via the {@link SupportedLintOptions} annotation or the {@link
     *         SourceChecker#getSupportedLintOptions} method
     * @param name the name of the lint option to check for
     * @param def the default option value, returned if the option was not given
     * @return true if the lint option was given, false if it was given
     *         prepended with a "-", or {@code def} if it was not given at all
     *
     * @see SourceChecker#getLintOption(String)
     * @see SourceChecker#getOption(String)
     */
    public final boolean getLintOption(String name, boolean def) {

        if (!this.getSupportedLintOptions().contains(name)) {
            ErrorReporter.errorAbort("Illegal lint option: " + name);
        }

        if (activeLints == null) {
            activeLints = createActiveLints(processingEnv.getOptions());
        }

        if (activeLints.isEmpty()) {
            return def;
        }

        String tofind = name;
        while (tofind != null) {
            if (activeLints.contains(tofind))
                return true;
            else if (activeLints.contains(String.format("-%s", tofind)))
                return false;

            tofind = parentOfOption(tofind);
        }

        return def;
    }

    /**
     * Set the value of the lint option with the given name.  Just
     * as <a
     * href="https://docs.oracle.com/javase/1.5.0/docs/tooldocs/solaris/javac.html">javac</a>
     * uses "-Xlint:xxx" to enable and "-Xlint:-xxx" to disable option xxx,
     * annotation-related lint options are enabled with "-Alint=xxx" and
     * disabled with "-Alint=-xxx".
     * This method can be used by subclasses to enforce having certain lint
     * options enabled/disabled.
     *
     * @throws IllegalArgumentException if the option name is not recognized
     *         via the {@link SupportedLintOptions} annotation or the {@link
     *         SourceChecker#getSupportedLintOptions} method
     * @param name the name of the lint option to set
     * @param val the option value
     *
     * @see SourceChecker#getLintOption(String)
     * @see SourceChecker#getLintOption(String,boolean)
     */
    protected final void setLintOption(String name, boolean val) {
        if (!this.getSupportedLintOptions().contains(name)) {
            ErrorReporter.errorAbort("Illegal lint option: " + name);
        }

        /* TODO: warn if the option is also provided on the command line(?)
        boolean exists = false;
        if (!activeLints.isEmpty()) {
            String tofind = name;
            while (tofind != null) {
                if (activeLints.contains(tofind) || // direct
                        activeLints.contains(String.format("-%s", tofind)) || // negation
                        activeLints.contains(tofind.substring(1))) { // name was negation
                    exists = true;
                }
                tofind = parentOfOption(tofind);
            }
        }

        if (exists) {
            // TODO: Issue warning?
        }
        TODO: assert that name doesn't start with '-'
        */

        Set<String> newlints = new HashSet<String>();
        newlints.addAll(activeLints);
        if (val) {
            newlints.add(name);
        } else {
            newlints.add(String.format("-%s", name));
        }
        activeLints = Collections.unmodifiableSet(newlints);
    }

    /**
     * Helper method to find the parent of a lint key.  The lint hierarchy
     * level is donated by a colon ':'.  'all' is the root for all hierarchy.
     *
     * Example
     *    cast:unsafe --> cast
     *    cast        --> all
     *    all         --> {@code null}
     */
    private String parentOfOption(String name) {
        if (name.equals("all"))
            return null;
        else if (name.contains(":")) {
            return name.substring(0, name.lastIndexOf(':'));
        } else {
            return "all";
        }
    }

    /**
     * Returns the lint options recognized by this checker. Lint options are
     * those which can be checked for via {@link SourceChecker#getLintOption}.
     *
     * @return an unmodifiable {@link Set} of the lint options recognized by
     *         this checker
     */
    public Set<String> getSupportedLintOptions() {
        if (supportedLints == null) {
            supportedLints = createSupportedLintOptions();
        }
        return supportedLints;
    }

    /**
     * Compute the set of supported lint options.
     */
    protected Set<String> createSupportedLintOptions() {
        /*@Nullable*/ SupportedLintOptions sl =
            this.getClass().getAnnotation(SupportedLintOptions.class);

        if (sl == null)
            return Collections.</*@NonNull*/ String>emptySet();

        /*@Nullable*/ String /*@Nullable*/ [] slValue = sl.value();
        assert slValue != null; /*nninvariant*/

        /*@Nullable*/ String [] lintArray = slValue;
        Set<String> lintSet = new HashSet<String>(lintArray.length);
        for (String s : lintArray)
            lintSet.add(s);
        return Collections.</*@NonNull*/ String>unmodifiableSet(lintSet);

    }

    /**
     * Set the supported lint options.
     * Use of this method should be limited to the AggregateChecker,
     * who needs to set the lint options to the union of all subcheckers.
     * Also, e.g. the NullnessSubchecker/RawnessSubchecker need to
     * use this method, as one is created by the other.
     */
    protected void setSupportedLintOptions(Set<String> newlints) {
        supportedLints = newlints;
    }

    /**
     * Add additional active options.
     * Use of this method should be limited to the AggregateChecker,
     * who needs to set the active options to the union of all subcheckers.
     */
    protected void addOptions(Map<String, String> moreopts) {
        Map<String, String> activeOpts = new HashMap<String, String>(getOptions());
        activeOpts.putAll(moreopts);
        activeOptions = Collections.unmodifiableMap(activeOpts);
    }

    /**
     * Determines the value of the option with the given name.
     *
     * @see SourceChecker#getLintOption(String,boolean)
     */
    @Override
    public final String getOption(String name) {
        return getOption(name, null);
    }

    /**
     * Return all active options for this checker.
     * @return all active options for this checker.
     */
    @Override
    public Map<String, String> getOptions() {
        if (activeOptions == null) {
            activeOptions = createActiveOptions(processingEnv.getOptions());
        }
        return activeOptions;
    }

    /**
     * Check whether the given option is provided.
     * Note that {@link #getOption(String)} can still return null even
     * if hasOption is true: this happens e.g. for -Amyopt
     *
     * @param name The option name to check
     * @return True if the option name was provided, false otherwise.
     */
    // TODO I would like to rename getLintOption to hasLintOption
    @Override
    public final boolean hasOption(String name) {
        return getOptions().containsKey(name);
    }

    /**
     * Determines the value of the lint option with the given name and
     * returns the default value if nothing is specified.
     *
     * @see SourceChecker#getOption(String)
     * @see SourceChecker#getLintOption(String)
     */
    @Override
    public final String getOption(String name, String def) {

        if (!this.getSupportedOptions().contains(name)) {
            ErrorReporter.errorAbort("Illegal option: " + name);
        }

        if (activeOptions == null) {
            activeOptions = createActiveOptions(processingEnv.getOptions());
        }

        if (activeOptions.isEmpty()) {
            return def;
        }

        if (activeOptions.containsKey(name)) {
            return activeOptions.get(name);
        } else {
            return def;
        }
    }

    /**
     * Map the Checker Framework version of {@link SupportedOptions} to
     * the standard annotation provided version
     * {@link javax.annotation.processing.SupportedOptions}.
     */
    @Override
    public Set<String> getSupportedOptions() {
        Set<String> options = new HashSet<String>();

        // Support all options provided with the standard
        // {@link javax.annotation.processing.SupportedOptions}
        // annotation.
        options.addAll(super.getSupportedOptions());

        // For the Checker Framework annotation
        // {@link org.checkerframework.framework.source.SupportedOptions}
        // we additionally add
        Class<?> clazz = this.getClass();
        List<Class<?>> clazzPrefixes = new LinkedList<>();

        do {
            clazzPrefixes.add(clazz);

            SupportedOptions so = clazz.getAnnotation(SupportedOptions.class);
            if  (so != null) {
                options.addAll(expandCFOptions(clazzPrefixes, so.value()));
            }
            clazz = clazz.getSuperclass();
        } while (clazz != null && !clazz.getName().equals(org.checkerframework.javacutil.AbstractTypeProcessor.class.getCanonicalName()));

        return Collections.</*@NonNull*/ String>unmodifiableSet(options);
    }

    /**
     * Generate the possible command-line option names by prefixing
     * each class name from {@code classPrefixes} to {@code options},
     * separated by OPTION_SEPARATOR.
     *
     * @param clazzPrefixes The classes to prefix
     * @param options The option names
     * @return the possible combinations that should be supported
     */
    protected Collection<String> expandCFOptions(
            List<? extends Class<?>> clazzPrefixes, String[] options) {
        Set<String> res = new HashSet<>();

        for (String option : options) {
            res.add(option);
            for (Class<?> clazz : clazzPrefixes) {
                res.add(clazz.getCanonicalName() + OPTION_SEPARATOR + option);
                res.add(clazz.getSimpleName() + OPTION_SEPARATOR + option);
            }
        }
        return res;
    }

    /**
     * Always returns a singleton set containing only "*".
     *
     * This method returns the argument to the {@link
     * SupportedAnnotationTypes} annotation, so the effect of returning "*"
     * is as if the checker were annotated by
     * {@code @SupportedAnnotationTypes("*")}:
     * javac runs the checker on every
     * class mentioned on the javac command line.  This method also checks
     * that subclasses do not contain a {@link SupportedAnnotationTypes}
     * annotation.  <p>
     *
     * To specify the annotations that a checker recognizes as type qualifiers,
     * use the {@link TypeQualifiers} annotation on the declaration of
     * subclasses of this class or override the
     * {@link BaseAnnotatedTypeFactory#getSupportedTypeQualifiers()} method.
     *
     * @throws Error if a subclass is annotated with
     *         {@link SupportedAnnotationTypes}
     *
     * @see TypeQualifiers
     */
    @Override
    public final Set<String> getSupportedAnnotationTypes() {

        SupportedAnnotationTypes supported = this.getClass().getAnnotation(
                SupportedAnnotationTypes.class);
        if (supported != null)
            ErrorReporter.errorAbort("@SupportedAnnotationTypes should not be written on any checker;"
                            + " supported annotation types are inherited from SourceChecker.");
        return Collections.singleton("*");
    }

    /**
     * @return String keys that a checker honors for suppressing warnings
     *         and errors that it issues.  Each such key suppresses all
     *         warnings issued by the checker.
     *
     * @see SuppressWarningsKeys
     */
    public Collection<String> getSuppressWarningsKeys() {
        return getStandardSuppressWarningsKeys();
    }

    /**
     * Determine the standard set of suppress warning keys usable for any checker.
     *
     * @see #getSuppressWarningsKeys()
     * @return Collection of warning keys
     */
    protected final Collection<String> getStandardSuppressWarningsKeys() {
        SuppressWarningsKeys annotation =
            this.getClass().getAnnotation(SuppressWarningsKeys.class);

        Set<String> result = new HashSet<>();
        result.add(SUPPRESS_ALL_KEY);

        if (annotation != null) {
            // Add from annotation
            for (String key : annotation.value()) {
                result.add(key);
            }

        } else {
            // No annotation, by default infer key from class name
            String className = this.getClass().getSimpleName();
            int indexOfChecker = className.lastIndexOf("Checker");
            if (indexOfChecker == -1)
                indexOfChecker = className.lastIndexOf("Subchecker");
            String key = (indexOfChecker == -1) ? className : className.substring(0, indexOfChecker);
            result.add(key.trim().toLowerCase());
        }

        return result;
    }

    /**
     * Tests whether the class owner of the passed element is an unannotated
     * class and matches the pattern specified in the
     * {@code checker.skipUses} property.
     *
     * @param element   an element
     * @return  true iff the enclosing class of element should be skipped
     */
    public final boolean shouldSkipUses(Element element) {
        if (element == null)
            return false;
        TypeElement typeElement = ElementUtils.enclosingClass(element);
        String name = typeElement.toString();
        return shouldSkipUses(name);
    }

    /**
     * Tests whether the class owner of the passed type matches
     * the pattern specified in the {@code checker.skipUses} property.
     * In contrast to {@link #shouldSkipUses(Element)} this version
     * can also be used from primitive types, which don't have an element.
     *
     * @param typeName   the fully-qualified name of a type
     * @return  true iff the enclosing class of element should be skipped
     */
    public final boolean shouldSkipUses(String typeName) {
        // System.out.printf("shouldSkipUses(%s) %s%nskipUses %s%nonlyUses %s%nresult %s%n",
        //                   element,
        //                   name,
        //                   skipUsesPattern.matcher(name).find(),
        //                   onlyUsesPattern.matcher(name).find(),
        //                   (skipUsesPattern.matcher(name).find()
        //                    || ! onlyUsesPattern.matcher(name).find()));
        // StackTraceElement[] stea = new Throwable().getStackTrace();
        // for (int i=0; i<3; i++) {
        //     System.out.println("  " + stea[i]);
        // }
        // System.out.println();
        if (skipUsesPattern == null) {
            skipUsesPattern = getSkipUsesPattern(getOptions());
        }
        if (onlyUsesPattern == null) {
            onlyUsesPattern = getOnlyUsesPattern(getOptions());
        }
        return (skipUsesPattern.matcher(typeName).find()
                || ! onlyUsesPattern.matcher(typeName).find());
    }

    /**
     * Tests whether the class definition should not be checked because it
     * matches the {@code checker.skipDefs} property.
     *
     * @param node class to potentially skip
     * @return true if checker should not test node
     */
    public final boolean shouldSkipDefs(ClassTree node) {
        String qualifiedName = InternalUtils.typeOf(node).toString();
        // System.out.printf("shouldSkipDefs(%s) %s%nskipDefs %s%nonlyDefs %s%nresult %s%n%n",
        //                   node,
        //                   qualifiedName,
        //                   skipDefsPattern.matcher(qualifiedName).find(),
        //                   onlyDefsPattern.matcher(qualifiedName).find(),
        //                   (skipDefsPattern.matcher(qualifiedName).find()
        //                    || ! onlyDefsPattern.matcher(qualifiedName).find()));
        if (skipDefsPattern == null) {
            skipDefsPattern = getSkipDefsPattern(getOptions());
        }
        if (onlyDefsPattern == null) {
            onlyDefsPattern = getOnlyDefsPattern(getOptions());
        }

        return (skipDefsPattern.matcher(qualifiedName).find()
                || ! onlyDefsPattern.matcher(qualifiedName).find());
    }

    /**
     * Tests whether the method definition should not be checked because it
     * matches the {@code checker.skipDefs} property.
     *
     * TODO: currently only uses the class definition. Refine pattern. Same for skipUses.
     *
     * @param cls class to potentially skip
     * @param meth method to potentially skip
     * @return true if checker should not test node
     */
    public final boolean shouldSkipDefs(ClassTree cls, MethodTree meth) {
        return shouldSkipDefs(cls);
    }


    /**
     * A helper function to parse a Properties file
     *
     * @param cls   the class whose location is the base of the file path
     * @param filePath the name/path of the file to be read
     * @return  the properties
     */
    protected Properties getProperties(Class<?> cls, String filePath) {
        Properties prop = new Properties();
        try {
            InputStream base = cls.getResourceAsStream(filePath);

            if (base == null)
                // No message customization file was given
                return prop;

            prop.load(base);
        } catch (IOException e) {
            message(Kind.WARNING, "Couldn't parse properties file: " + filePath);
            // e.printStackTrace();
            // ignore the possible customization file
        }
        return prop;
    }

    @Override
    public final SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }
}
