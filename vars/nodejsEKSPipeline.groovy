def call(Map configMap){
    pipeline {
        agent {
            node { label "AGENT-1" }
        } 
        options {
            timeout(time: 30, unit: 'MINUTES')
            disableConcurrentBuilds()
            ansiColor('xterm') 
        }
        environment {
            PROJECT = configMap.get('project')
            COMPONENT = configMap.get('component')
            AWS_REGION = 'us-east-1'
            AWS_ACC_ID = '793770371113'
        }
        stages {
            stage('Read Version') {
                steps {
                    script {
                        def packageJson = readJSON file: 'package.json'
                        def appVersion = packageJson.version
                        echo "The application version is: ${appVersion}"
                        env.APP_VERSION = appVersion

                        echo """
                        component name is : ${env.COMPONENT}
                        Project name is : ${env.PROJECT}
                        """
                        
                    }
                }
            }

            stage('Install Dependancies'){
                steps{
                    sh '''
                        npm install
                    '''
                }
            }

            stage('unit test'){
                steps{
                    sh'''
                        npm test
                    '''
                }
            }

            stage('Check Dependabot Alerts'){
                environment {
                    GITHUB_OWNER = 'AnuragBojja'
                    GITHUB_REPO  = 'catalogue-soner'
                    GITHUB_TOKEN = credentials('github-token')
                }
                steps {
                    script {
                        sh '''
                            set -e

                            echo "Fetching open Dependabot alerts..."

                            curl -fsS -L \
                            -H "Accept: application/vnd.github+json" \
                            -H "Authorization: token ${GITHUB_TOKEN}" \
                            -H "X-GitHub-Api-Version: 2022-11-28" \
                            "https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/dependabot/alerts?state=open&per_page=100" \
                            -o dependabot_alerts.json

                            echo "Dependabot response saved to dependabot_alerts.json"
                        '''

                        def alerts = readJSON file: 'dependabot_alerts.json'

                        if (alerts.isEmpty()) {
                            echo 'No open Dependabot alerts found.'
                            return
                        }

                        def blockingAlerts = []

                        alerts.each { alert ->
                            def dependency = alert.dependency.package.name
                            def ecosystem = alert.dependency.package.ecosystem
                            def severity = alert.security_advisory.severity
                            def summary = alert.security_advisory.summary
                            def cve = alert.security_advisory.cve_id ?: 'Not available'
                            def vulnerableVersions =
                                alert.security_vulnerability.vulnerable_version_range
                            def patchedVersion =
                                alert.security_vulnerability.first_patched_version?.identifier ?: 'Not available'
                            def manifest = alert.dependency.manifest_path
                            def alertUrl = alert.html_url

                            echo """
                            --------------------------------------------------
                            Dependency       : ${dependency}
                            Ecosystem        : ${ecosystem}
                            Severity         : ${severity}
                            Summary          : ${summary}
                            CVE              : ${cve}
                            Manifest         : ${manifest}
                            Vulnerable range : ${vulnerableVersions}
                            Patched version  : ${patchedVersion}
                            Alert URL        : ${alertUrl}
                            --------------------------------------------------
                            """.stripIndent()

                            if (severity in ['high', 'critical']) {
                                blockingAlerts.add(alert)
                            }
                        }
                    }
                }
            }

            stage(build){
                steps{
                    withAWS(region: 'us-east-1', credentials: 'aws-cred'){
                        sh """
                            aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${AWS_ACC_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com
                            docker build -t ${AWS_ACC_ID}.dkr.ecr.us-east-1.amazonaws.com/${PROJECT}/${COMPONENT}:${APP_VERSION} .
                            docker images
                            docker push ${AWS_ACC_ID}.dkr.ecr.us-east-1.amazonaws.com/${PROJECT}/${COMPONENT}:${APP_VERSION}
                        """
                    }
                }
            }
            stage('trivy scan'){
                steps{
                    script{
                        sh """
                        trivy image \
                        --scanners vuln \
                        --pkg-types os \
                        --format table \
                        --exit-code 1 \
                        --severity HIGH,CRITICAL,MEDIUM ${AWS_ACC_ID}.dkr.ecr.us-east-1.amazonaws.com/${PROJECT}/${COMPONENT}:${APP_VERSION}
                        """
                    }
                }
            }
            stage('Deploy to Dev Env'){
                steps{
                    script{
                        build (
                            job: '../catalogue-deploy',
                            wait: false,
                            propagate: false,
                            parameters: [
                                string(name: 'ENVIRONMENT',value: 'dev'), 
                                string(name: 'APP_VERSION',value: ${APP_VERSION}),
                                string(name: 'DEPLOY',value: 'true'),

                            ]
                        )
                    }
                }
            }
        }
        post {
            always {
                echo "This run Evey time"
            }
            success {
                echo "✅ Current build is Success"
                
            }
            failure {
                echo "❌ Build failed — check stage logs above."
            }
            // cleanup {
            //     cleanWs()          // wipe workspace (Workspace Cleanup plugin)
            // }
        }
    }
}