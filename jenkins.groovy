pipeline {
    agent any
    
    parameters {
        booleanParam(name: 'SKIP_TESTS', defaultValue: false, description: 'Skip test execution')
        choice(name: 'DEPLOY_ENV', choices: ['dev', 'staging', 'production'], description: 'Target environment')
    }
    
    environment {
        DOCKER_REGISTRY = 'myregistry.com'
        APP_NAME = 'ecommerce-app'
        DATABASE_URL = credentials('database-url')
    }
    
    stages {
        stage('Checkout') {
            steps {
                checkout scm
                sh 'git rev-parse HEAD > commit.txt'
            }
        }
        
        stage('Install Dependencies') {
            parallel {
                stage('Backend Dependencies') {
                    steps {
                        dir('backend') {
                            sh 'npm install'
                        }
                    }
                }
                stage('Frontend Dependencies') {
                    steps {
                        dir('frontend') {
                            sh 'npm install'
                        }
                    }
                }
            }
        }
        
        stage('Lint and Test') {
            when {
                not { params.SKIP_TESTS }
            }
            parallel {
                stage('Backend Tests') {
                    steps {
                        dir('backend') {
                            sh 'npm run lint'
                            sh 'npm run test:unit'
                            sh 'npm run test:integration'
                        }
                    }
                    post {
                        always {
                            publishTestResults testResultsPattern: 'backend/test-results.xml'
                            publishCoverageReport sourceFileResolver: sourceFiles('backend/src')
                        }
                    }
                }
                stage('Frontend Tests') {
                    steps {
                        dir('frontend') {
                            sh 'npm run lint'
                            sh 'npm run test'
                            sh 'npm run e2e'
                        }
                    }
                    post {
                        always {
                            publishTestResults testResultsPattern: 'frontend/test-results.xml'
                        }
                    }
                }
            }
        }
        
        stage('Build') {
            parallel {
                stage('Build Backend') {
                    steps {
                        dir('backend') {
                            sh 'npm run build'
                            sh "docker build -t ${DOCKER_REGISTRY}/${APP_NAME}-backend:${env.BUILD_NUMBER} ."
                        }
                    }
                }
                stage('Build Frontend') {
                    steps {
                        dir('frontend') {
                            sh 'npm run build'
                            sh "docker build -t ${DOCKER_REGISTRY}/${APP_NAME}-frontend:${env.BUILD_NUMBER} ."
                        }
                    }
                }
            }
        }
        
        stage('Security Scan') {
            steps {
                sh "docker run --rm -v \$(pwd):/app security-scanner:latest /app"
            }
            post {
                always {
                    archiveArtifacts artifacts: 'security-report.json'
                }
            }
        }
        
        stage('Deploy') {
            when {
                anyOf {
                    branch 'main'
                    expression { params.DEPLOY_ENV != 'dev' }
                }
            }
            steps {
                script {
                    if (params.DEPLOY_ENV == 'production') {
                        input message: 'Deploy to production?', ok: 'Deploy'
                    }
                }
                sh "kubectl set image deployment/${APP_NAME}-backend backend=${DOCKER_REGISTRY}/${APP_NAME}-backend:${env.BUILD_NUMBER}"
                sh "kubectl set image deployment/${APP_NAME}-frontend frontend=${DOCKER_REGISTRY}/${APP_NAME}-frontend:${env.BUILD_NUMBER}"
                sh "kubectl rollout status deployment/${APP_NAME}-backend"
                sh "kubectl rollout status deployment/${APP_NAME}-frontend"
            }
        }
    }
    
    post {
        always {
            cleanWs()
        }
        success {
            slackSend channel: '#deployments', color: 'good', message: "✅ ${env.JOB_NAME} - ${env.BUILD_NUMBER} deployed successfully to ${params.DEPLOY_ENV}"
        }
        failure {
            slackSend channel: '#deployments', color: 'danger', message: "❌ ${env.JOB_NAME} - ${env.BUILD_NUMBER} failed"
        }
    }