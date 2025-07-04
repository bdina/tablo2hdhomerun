package compiler

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction

class ScalaCliTask extends DefaultTask {
    enum Option {
        ASYNC('--Xasync')
      , PACKAGE('--power package')
      String arg
      private Option(String s) { this.arg = s }
    }

    static final List<String> EXECUTABLE = [ 'scala-cli' ]

    @Input
    List<Option> parameters = [ Option.ASYNC ]

    @Input
    Integer minHeap = 1
    @Input
    Integer maxHeap = 32

    @TaskAction
    void runCommand() {
        project.buildDir.mkdirs()

        logger.lifecycle "Loaded project ${project} (scala ${project.ext.scala_ver})"
        def conf = project.getConfigurations()
        def deps = conf.getByName('compileClasspath').getAllDependencies()
        def dStr =
          deps
            .collect { it ->
              "--dependency ${it.group}:${it.name}:${it.version}"
            }
            .join(' ')
        def sourceSet = project.getSourceSets().getByName('main').getAllSource()
        def sources =
          sourceSet
            .findAll { file ->
              file.path.endsWith('.scala')
            }
            .join(' ')

        def command = "scala-cli --power package ${sources} --scala ${project.ext.scala_ver} --native -Xasync ${dStr} -o ${project.name}"
        logger.lifecycle "Executing scala-cli command -> ${command}"

        def process = command.execute(null, project.buildDir)
        process.consumeProcessOutput(System.out, System.err)
        process.waitFor()

        if (process.exitValue() != 0) {
            logger.error "Unable to execute scala-cli: '${process.exitValue}'"
            throw new GradleException()
        }
    }
}

import org.gradle.api.Plugin
import org.gradle.api.Project

class ScalaCliPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.task('scalaCli', type: ScalaCliTask)
    }
}
