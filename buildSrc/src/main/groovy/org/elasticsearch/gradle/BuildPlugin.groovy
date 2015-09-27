package org.elasticsearch.gradle

import org.elasticsearch.gradle.precommit.PrecommitTasks
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.compile.JavaCompile

/**
 * Encapsulates build configuration for elasticsearch projects.
 */
class BuildPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.pluginManager.apply('java')
        project.pluginManager.apply('carrotsearch.randomizedtesting')

        configureCompile(project)
        configureTest(project)
        PrecommitTasks.setup(project)
    }

    /** Adds compiler settings to the project */
    static void configureCompile(Project project) {
        project.afterEvaluate {
            // fail on all javac warnings
            project.tasks.withType(JavaCompile) {
                options.compilerArgs << '-Werror' << '-Xlint:all' << '-Xdoclint:all/private' << '-Xdoclint:-missing'
            }
        }
    }

    /** Returns a closure of common configuration shared by unit and integration tests. */
    static Closure commonTestConfig(Project project) {
        return {
            // TODO: don't use JAVA_HOME env var, but instead sysprop set by java?
            jvm System.getenv('JAVA_HOME') + File.separator + 'bin' + File.separator + 'java'
            parallelism System.getProperty('tests.jvms', 'auto')

            // TODO: why are we not passing maxmemory to junit4?
            jvmArg '-Xmx' + System.getProperty('tests.heap.size', '512m')
            jvmArg '-Xms' + System.getProperty('tests.heap.size', '512m')
            if (JavaVersion.current().isJava7()) {
                // some tests need a large permgen, but that only exists on java 7
                jvmArg '-XX:MaxPermSize=128m'
            }
            jvmArg '-XX:MaxDirectMemorySize=512m'
            jvmArg '-XX:+HeapDumpOnOutOfMemoryError'
            File heapdumpDir = new File(project.buildDir, 'heapdump')
            heapdumpDir.mkdirs()
            jvmArg '-XX:HeapDumpPath=' + heapdumpDir

            // we use './temp' since this is per JVM and tests are forbidden from writing to CWD
            sysProp 'java.io.tmpdir', './temp'
            sysProp 'java.awt.headless', 'true'
            sysProp 'tests.maven', 'true' // TODO: rename this once we've switched to gradle!
            sysProp 'tests.task', path
            sysProp 'tests.security.manager', 'true'
            // default test sysprop values
            sysProp 'tests.ifNoTests', 'fail'
            sysProp 'es.logger.level', 'WARN'
            copySysPropPrefix 'tests.'
            copySysPropPrefix 'es.'

            // System assertions (-esa) are disabled for now because of what looks like a
            // JDK bug triggered by Groovy on JDK7. We should look at re-enabling system
            // assertions when we upgrade to a new version of Groovy (currently 2.4.4) or
            // require JDK8. See https://issues.apache.org/jira/browse/GROOVY-7528.
            enableSystemAssertions false

            testLogging {
                slowTests {
                    heartbeat 10
                    summarySize 5
                }
                stackTraceFilters {
                    // custom filters: we carefully only omit test infra noise here
                    contains '.SlaveMain.'
                    regex(/^(\s+at )(org\.junit\.)/)
                    // also includes anonymous classes inside these two:
                    regex(/^(\s+at )(com\.carrotsearch\.randomizedtesting\.RandomizedRunner)/)
                    regex(/^(\s+at )(com\.carrotsearch\.randomizedtesting\.ThreadLeakControl)/)
                    regex(/^(\s+at )(com\.carrotsearch\.randomizedtesting\.rules\.)/)
                    regex(/^(\s+at )(org\.apache\.lucene\.util\.TestRule)/)
                    regex(/^(\s+at )(org\.apache\.lucene\.util\.AbstractBeforeAfterRule)/)
                }
            }

            balancers {
                executionTime cacheFilename: ".local-${project.version}-${name}-execution-times.log"
            }

            listeners {
                junitReport()
            }

            exclude '**/*$*.class'
        }
    }

    /** Configures the test task */
    static Task configureTest(Project project) {
        Task test = project.tasks.getByName('test')
        test.configure(commonTestConfig(project))
        test.configure {
            include '**/*Tests.class'
        }
        return test
    }
}