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
        parameters {
            string(name: 'APP_VERSION', defaultValue: 'latest', description: 'What is app version?')
            choice(name: 'ENVIRONMENT', choices: ['dev', 'qa', 'prod'], description: 'Target environment')
            booleanParam(name: 'DEPLOY', defaultValue: false, description: 'Deploy to EKS after build?')
        }
        stages {
            stage('Deploying'){ 
                when {
                    expression {
                        return params.DEPLOY
                    }
                } 
                stages {
                    stage('Checking the Enviroment'){
                        when{
                            expression{
                                return params.ENVIRONMENT != 'dev'
                            }
                        }
                        steps{
                            error "Need permissions for QA or Prod deployment."
                        }
                    }
                    stage('EKS Updating configure file'){
                        steps{
                            withAWS(region: 'us-east-1', credentials: 'aws-cred'){
                                script{
                                    sh """
                                        aws eks update-kubeconfig --region '${AWS_REGION}' --name '${PROJECT}-${params.ENVIRONMENT}-EKS'
                                        kubectl get nodes
                                        echo env is: ${params.ENVIRONMENT}
                                        echo deploy to: ${params.DEPLOY}
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
