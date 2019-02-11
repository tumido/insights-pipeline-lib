/*
 * Run an e2e smoke test
 *
 * Required plugins:
 * Blue Ocean / all the 'typical' plugins for GitHub multi-branch pipelines
 * GitHub Branch Source Plugin
 * SCM Filter Branch PR Plugin
 * Pipeline GitHub Notify Step Plugin
 * Pipeline: GitHub Plugin
 * SSH Agent Plugin
 * Lockable Resources Plugin
 * Kubernetes Plugin
 *
 * Configuration:
 * Discover branches:
 *   Strategy: Exclude branches that are also filed as PRs
 * Discover pull requests from forks:
 *   Strategy: Merging the pull request with the current target branch revision
 *   Trust: From users with Admin or Write permission
 * Discover pull requests from origin
 *   Strategy: Merging the pull request with the current target branch revision
 * Filter by name including PRs destined for this branch (with regular expression):
 *   Regular expression: .*
 * Clean before checkout
 * Clean after checkout
 * Check out to matching local branch
 *
 * Script whitelisting is needed for 'currentBuild.rawBuild' and 'currentBuild.rawBuild.getCause()'
 *
 * Configure projects in OpenShift, create lockable resources for them with label "smoke_test_projects"
 * Add Jenkins service account as admin on those projects
 *
 */

def call(parameters = [:]) {
    def ocDeployerBuilderPath = parameters['ocDeployerBuilderPath']
    def ocDeployerComponentPath = parameters['ocDeployerComponentPath']
    def ocDeployerServiceSets = parameters['ocDeployerServiceSets']
    def pytestMarker = parameters['pytestMarker']
    def iqePlugins = parameters.get('iqePlugins')
    def extraEnvVars = parameters.get('extraEnvVars', [:])

    withStatusContext.smoke {
        lock(label: pipelineVars.smokeTestResourceLabel, quantity: 1, variable: "PROJECT") {
            echo "Using project: ${env.PROJECT}"

            openShift.withNode(image: 'docker-registry.default.svc:5000/jenkins/jenkins-slave-iqe:latest', namespace: env.PROJECT) {
                runPipeline(env.PROJECT, ocDeployerBuilderPath, ocDeployerComponentPath, 
                            ocDeployerServiceSets, pytestMarker, iqePlugins, extraEnvVars)
            }
        }
    }
}


private def deployEnvironment(refspec, project, ocDeployerBuilderPath, ocDeployerComponentPath, ocDeployerServiceSets) {
    stage("Deploy test environment") {
        dir(pipelineVars.e2eDeployDir) {
            // First, deploy the builder for only this app to build the PR image in this project
            sh "echo \"${ocDeployerBuilderPath}:\" > env.yml"
            sh "echo \"  parameters:\" >> env.yaml"
            sh "echo \"    SOURCE_REPOSITORY_REF: ${refspec}\" >> env.yml"
            sh  "ocdeployer deploy -f -l e2esmoke=true -p ${ocDeployerBuilderPath} -t buildfactory -e env.yml ${project}"

            // Now deploy the full env, set the image for this app to be pulled from this local project instead of buildfactory
            sh "echo \"${ocDeployerComponentPath}:\" > env.yml"
            sh "echo \"  parameters:\" >> env.yaml"
            sh "echo \"    IMAGE_NAMESPACE: ${project}\" >> env.yml"   
            sh  "ocdeployer deploy -f -l e2esmoke=true -s ${ocDeployerServiceSets} -e env.yml --scale-resources 0.75 ${project}"
        }
    }
}


private def runPipeline(String project, String ocDeployerBuilderPath, String ocDeployerComponentPath,
                        String ocDeployerServiceSets, String pytestMarker, List<String> iqePlugins, Map extraEnvVars) {
    cancelPriorBuilds()

    currentBuild.result = "SUCCESS"

    /* Deploy a test env to 'project' in openshift, checkout e2e-tests, run the smoke tests */

    // cache creds so we can git 'ls-remote' below...
    sh "git config --global credential.helper cache"
    checkout scm

    // get refspec so we can set up the OpenShift build config to point to this PR
    // there's gotta be a better way to get the refspec, somehow 'checkout scm' knows what it is ...
    def refspec

    stage("Get refspec") {
        refspec = "refs/pull/${env.CHANGE_ID}/merge"
        def refspecExists = sh(returnStdout: true, script: "git ls-remote | grep ${refspec}").trim()
        if (!refspecExists) {
            error("Unable to find git refspec: ${refspec}")
        }
    }

    // check out e2e-deploy
    stage("Check out repos") {
        checkOutRepo(targetDir: pipelineVars.e2eDeployDir, repoUrl: pipelineVars.e2eDeployRepo)
    }

    stage("Install ocdeployer") {
        sh "devpi use http://devpi.devpi.svc:3141/root/psav --set-cfg"
        sh "devpi refresh ocdeployer"

        dir(pipelineVars.e2eDeployDir) {
            sh "pip install -r requirements.txt"
        }
    }

    stage("Wipe test environment") {
        sh "ocdeployer wipe -l e2esmoke=true --no-confirm ${project}"
    }

    stage("Install iqe and plugins") {
        sh "pip install --upgrade iqe-integration-tests"
        sh "pip install --upgrade iqe-red-hat-internal-envs-plugin"
        for (String plugin : iqePlugins) {
            sh "pip install ${plugin}"
        }
    }

    try {
        deployEnvironment(refspec, project, ocDeployerBuilderPath, ocDeployerComponentPath, ocDeployerServiceSets)
    } catch (err) {
        openShift.collectLogs(project: project)
        throw err
    }

    stage("Run tests (pytest marker: ${pytestMarker})") {
        extraEnvVars.each { key, val ->
            sh "export ${key}=${val}"
        }

        // defining the URLs here is temporary until we have a better solution
        sh """
            export ENV_FOR_DYNACONF=smoke
            export DYNACONF_OCPROJECT=${project}
            export DYNACONF_LOGGING='@json {"log_root": "."}'

            set +e
            iqe tests all --junitxml=junit.xml -s -v -m ${pytestMarker} 2>&1 | tee pytest-stdout.log
            set -e
        """

        archiveArtifacts "pytest-stdout.log"
        archiveArtifacts "iqe.log"
    }

    openShift.collectLogs(project: project)

    stage("Wipe test environment") {
        sh "ocdeployer wipe -l e2esmoke=true --no-confirm ${project}"
    }

    junit "junit.xml"

    if (currentBuild.result != "SUCCESS") {
        throw new Exception("Smoke test failed");
    }
}
