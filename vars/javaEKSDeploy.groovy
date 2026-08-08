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
            APP_VERSION = configMap.get('appVersion')
            ENVIRONMENT = configMap.get('environment')
            DEPLOY = configMap.get('deploy')
            AWS_REGION = 'us-east-1'
            AWS_ACC_ID = '793770371113'
        }
        stages {
            stage('Deploying'){ 
                when {
                    expression {
                        return env.DEPLOY
                    }
                } 
                stages {
                    stage('Checking the Enviroment'){
                        when{
                            expression{
                                return env.ENVIRONMENT != 'dev'
                            }
                        }
                        steps{
                            error "Need permissions for QA or Prod deployment."
                        }
                    }
                    stage('Creating Name Space'){
                        steps{
                            withAWS(region: 'us-east-1', credentials: 'aws-cred'){
                                script{
                                    sh """
                                        set -e
                                        aws eks update-kubeconfig --region '${AWS_REGION}' --name '${PROJECT}-${env.ENVIRONMENT}-EKS'
                                        kubectl get nodes
                                        echo env is: ${env.ENVIRONMENT}
                                        echo deploy to: ${env.DEPLOY}
                                        echo app version is: ${env.APP_VERSION}
                                        kubectl apply -f namespace.yaml
                                    """
                                }
                            }
                        }
                    }
                    stage('EKS Updating configure file'){
                        steps{
                            withAWS(region: 'us-east-1', credentials: 'aws-cred'){
                                script{
                                    sh """
                                        set -e
                                        aws eks update-kubeconfig --region '${AWS_REGION}' --name '${PROJECT}-${env.ENVIRONMENT}-EKS'
                                        helm upgrade --install ${env.COMPONENT} . \
                                            -f values-${env.ENVIRONMENT}.yaml \
                                            -n ${PROJECT}-${env.ENVIRONMENT} \
                                            --set-string deployment.imageVersion="${env.APP_VERSION}" \
                                            --rollback-on-failure \
                                            --timeout 5m

                                    """
                                }
                            }
                        }
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
