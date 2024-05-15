/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.tasks.compile;

import org.gradle.api.internal.ClassPathRegistry;
import org.gradle.api.internal.tasks.compile.daemon.AbstractDaemonCompiler;
import org.gradle.api.internal.tasks.compile.daemon.CompilerWorkerExecutor;
import org.gradle.api.problems.Severity;
import org.gradle.api.problems.internal.GradleCoreProblemGroup;
import org.gradle.api.problems.internal.InternalProblems;
import org.gradle.internal.classloader.VisitableURLClassLoader;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.jvm.JavaInfo;
import org.gradle.internal.jvm.Jvm;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.process.JavaForkOptions;
import org.gradle.process.internal.JavaForkOptionsFactory;
import org.gradle.workers.internal.DaemonForkOptions;
import org.gradle.workers.internal.DaemonForkOptionsBuilder;
import org.gradle.workers.internal.FlatClassLoaderStructure;
import org.gradle.workers.internal.KeepAliveMode;

import java.io.File;
import java.util.Collections;

public class DaemonJavaCompiler extends AbstractDaemonCompiler<JavaCompileSpec> {
    private final Class<? extends Compiler<JavaCompileSpec>> compilerClass;
    private final Object[] compilerConstructorArguments;
    private final JavaForkOptionsFactory forkOptionsFactory;
    private final File daemonWorkingDir;
    private final ClassPathRegistry classPathRegistry;
    private final InternalProblems problems;

    public DaemonJavaCompiler(
        File daemonWorkingDir,
        Class<? extends Compiler<JavaCompileSpec>> compilerClass,
        Object[] compilerConstructorArguments, CompilerWorkerExecutor compilerWorkerExecutor,
        JavaForkOptionsFactory forkOptionsFactory,
        ClassPathRegistry classPathRegistry,
        InternalProblems problems
    ) {
        super(compilerWorkerExecutor);
        this.compilerClass = compilerClass;
        this.compilerConstructorArguments = compilerConstructorArguments;
        this.forkOptionsFactory = forkOptionsFactory;
        this.daemonWorkingDir = daemonWorkingDir;
        this.classPathRegistry = classPathRegistry;
        this.problems = problems;
    }

    @Override
    protected CompilerWorkerExecutor.CompilerParameters getCompilerParameters(JavaCompileSpec spec) {
        return new JavaCompilerParameters(compilerClass.getName(), compilerConstructorArguments, spec);
    }

    @Override
    protected DaemonForkOptions toDaemonForkOptions(JavaCompileSpec spec) {
        if (!(spec instanceof ForkingJavaCompileSpec)) {
            throw new IllegalArgumentException(String.format("Expected a %s, but got %s", ForkingJavaCompileSpec.class.getSimpleName(), spec.getClass().getSimpleName()));
        }
        ForkingJavaCompileSpec forkingSpec = (ForkingJavaCompileSpec) spec;

        JavaInfo jvm = Jvm.forHome(((ForkingJavaCompileSpec) spec).getJavaHome());

        MinimalJavaCompilerDaemonForkOptions forkOptions = spec.getCompileOptions().getForkOptions();
        JavaForkOptions javaForkOptions = new BaseForkOptionsConverter(forkOptionsFactory).transform(forkOptions);
        javaForkOptions.setWorkingDir(daemonWorkingDir);
        javaForkOptions.setExecutable(jvm.getJavaExecutable());

        ClassPath compilerClasspath = classPathRegistry.getClassPath("JAVA-COMPILER");

        JavaLanguageVersion javaLanguageVersion = JavaLanguageVersion.of(forkingSpec.getJavaLanguageVersion());
        if (javaLanguageVersion.canCompileOrRun(9)) {
            // In JDK 9 and above the compiler internal classes are bundled with the rest of the JDK, but we need to export it to gain access.
            javaForkOptions.jvmArgs(
                "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
            );
        } else {
            // In JDK 8 and below, the compiler internal classes are in tools.jar.
            File toolsJar = jvm.getToolsJar();
            if (toolsJar == null) {
                String contextualMessage = String.format("The 'tools.jar' cannot be found in the JDK located at '%s'.", jvm.getJavaHome());
                throw problems.getInternalReporter().throwing(problemSpec -> problemSpec
                    .id("missing-tools-jar", "Missing tools.jar", GradleCoreProblemGroup.compilation().groovy())
                    .contextualLabel(contextualMessage)
                    .solution("Check if the installation is a JDK and not a JRE.")
                    .solution("Check if the JDK is corrupted or incomplete. The 'lib' directory should contain a 'tools.jar'.")
                    .severity(Severity.ERROR)
                    .withException(new IllegalStateException(contextualMessage))
                );
            }

            compilerClasspath = compilerClasspath.plus(
                Collections.singletonList(toolsJar)
            );
        }

        FlatClassLoaderStructure classLoaderStructure = new FlatClassLoaderStructure(new VisitableURLClassLoader.Spec("compiler", compilerClasspath.getAsURLs()));
        return new DaemonForkOptionsBuilder(forkOptionsFactory)
            .javaForkOptions(javaForkOptions)
            .withClassLoaderStructure(classLoaderStructure)
            .keepAliveMode(KeepAliveMode.DAEMON)
            .build();
    }

    public static class JavaCompilerParameters extends CompilerWorkerExecutor.CompilerParameters {
        private final JavaCompileSpec compileSpec;

        public JavaCompilerParameters(String compilerClassName, Object[] compilerInstanceParameters, JavaCompileSpec compileSpec) {
            super(compilerClassName, compilerInstanceParameters);
            this.compileSpec = compileSpec;
        }

        @Override
        public JavaCompileSpec getCompileSpec() {
            return compileSpec;
        }
    }
}
