package compiler

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction

class NativeImageTask extends DefaultTask {
    enum Option {
        STATIC('--static')
      , MUSL('--libc=musl')
      , LINK_BUILD('--link-at-build-time')
      , G1GC('--gc=G1')
      , CPU('-march=native')
      , PGO('--pgo')
      String arg
      private Option(String s) { this.arg = s }
    }

    static final List<String> EXECUTABLE = [ 'native-image' ]

    @Input
    List<Option> parameters = [ Option.STATIC, Option.MUSL ]

    @Input
    Integer minHeap = 1
    @Input
    Integer maxHeap = 32
    @Input
    Integer maxNew = 32

    @InputDirectory
    File buildDir = project.buildDir

    @TaskAction
    void runCommand() {
        def heap = [
          "-R:MinHeapSize=${minHeap}m"
        , "-R:MaxHeapSize=${maxHeap}m"
        , "-R:MaxNewSize=${maxNew}m"
        ]
        def source = [ '-jar', "${project.buildDir}/libs/${project.name}-${project.version}.jar", '-o', "${project.name}" ]
        def command = EXECUTABLE + parameters*.arg + heap + source
        logger.lifecycle "Executing native-image command -> '${command.join(' ')}'"

        def process = command.execute(null, buildDir)
        process.consumeProcessOutput(System.out, System.err)
        process.waitFor()

        if (process.exitValue() != 0) {
            logger.error "Unable to execute native-image: '${process.exitValue}'"
            throw new GradleException()
        }
    }
}

import org.gradle.api.Plugin
import org.gradle.api.Project

class NativeImagePlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        // Delay task registration until after evaluation
        project.afterEvaluate {
            if (!project.plugins.hasPlugin("com.github.johnrengelman.shadow")) {
                throw new IllegalStateException("The Shadow plugin must be applied for 'nativeImage' to work.")
            }

            project.tasks.register('nativeImage', NativeImageTask) { task ->
                dependsOn project.tasks.named('shadowJar')
                group = 'verification'
                description = 'Builds a native image from a shadowJar'
            }
        }
    }
}
