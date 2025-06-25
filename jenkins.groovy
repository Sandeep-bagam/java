pipeline {
    agent none
    
    parameters {
        choice(name: 'ENVIRONMENT', choices: ['dev', 'staging', 'production'], description: 'Target environment')
        booleanParam(name: 'SKIP_TESTS', defaultValue: false, description: 'Skip all tests')
        booleanParam(name: 'REBUILD_ALL', defaultValue: false, description: 'Force rebuild all services')
        choice(name: 'DEPLOY_SERVICES', choices: ['all', 'java-only', 'node-only', 'python-only'], description: 'Services to deploy')
        string(name: 'IMAGE_TAG', defaultValue: '', description: 'Custom image tag (leave empty for auto)')
    }
    
    environment {
        DOCKER_REGISTRY = 'myregistry.azurecr.io'
        JAVA_VERSION = '17'
        NODE_VERSION = '18'
        PYTHON_VERSION = '3.11'
        MAVEN_OPTS = '-Xmx2048m -Xms1024m'
        SONAR_TOKEN = credentials('sonar-token')
        DOCKER_CREDENTIALS = credentials('docker-registry-creds')
    }
    
    stages {
        stage('Checkout & Change Detection') {
            agent any
            steps {
                checkout scm
                script {
                    env.GIT_COMMIT_SHORT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    env.IMAGE_TAG = params.IMAGE_TAG ?: "${env.BUILD_NUMBER}-${env.GIT_COMMIT_SHORT}"
                    
                    // Detect which services changed
                    def changes = sh(script: '''
                        if [ "${REBUILD_ALL}" = "true" ]; then
                            echo "java,node,python"
                        else
                            changed=""
                            if git diff --name-only HEAD~1 HEAD | grep -q "^product-service/"; then
                                changed="${changed}java,"
                            fi
                            if git diff --name-only HEAD~1 HEAD | grep -q "^api-gateway/"; then
                                changed="${changed}node,"
                            fi
                            if git diff --name-only HEAD~1 HEAD | grep -q "^recommendation-service/"; then
                                changed="${changed}python,"
                            fi
                            echo "${changed%,}"
                        fi
                    ''', returnStdout: true).trim()
                    
                    env.CHANGED_SERVICES = changes
                    echo "Services to build: ${env.CHANGED_SERVICES}"
                }
                
                stash includes: '**', name: 'source-code'
            }
        }
        
        stage('Database Migrations') {
            when {
                anyOf {
                    expression { env.CHANGED_SERVICES.contains('java') }
                    expression { params.REBUILD_ALL }
                }
            }
            agent any
            environment {
                DB_URL = credentials("${params.ENVIRONMENT}-db-url")
                DB_USERNAME = credentials("${params.ENVIRONMENT}-db-username")
                DB_PASSWORD = credentials("${params.ENVIRONMENT}-db-password")
            }
            steps {
                unstash 'source-code'
                dir('database') {
                    sh '''
                        # Install Flyway
                        wget -qO- https://repo1.maven.org/maven2/org/flywaydb/flyway-commandline/9.0.0/flyway-commandline-9.0.0-linux-x64.tar.gz | tar xvz
                        
                        # Run migrations
                        ./flyway-9.0.0/flyway -url="${DB_URL}" -user="${DB_USERNAME}" -password="${DB_PASSWORD}" migrate
                    '''
                }
            }
        }
        
        stage('Build Services') {
            parallel {
                stage('Build Java Service') {
                    when {
                        anyOf {
                            expression { env.CHANGED_SERVICES.contains('java') }
                            expression { params.DEPLOY_SERVICES in ['all', 'java-only'] }
                        }
                    }
                    agent {
                        docker {
                            image 'maven:3.9-openjdk-17'
                            args '-v /var/run/docker.sock:/var/run/docker.sock -v maven-cache:/root/.m2'
                        }
                    }
                    steps {
                        unstash 'source-code'
                        dir('product-service') {
                            sh 'mvn clean compile'
                            
                            script {
                                if (!params.SKIP_TESTS) {
                                    sh 'mvn test'
                                    sh 'mvn jacoco:report'
                                    sh 'mvn sonar:sonar -Dsonar.token=${SONAR_TOKEN} -Dsonar.projectKey=product-service'
                                }
                                
                                sh 'mvn package -DskipTests'
                                sh "docker build -t ${DOCKER_REGISTRY}/product-service:${IMAGE_TAG} ."
                                
                                withCredentials([usernamePassword(credentialsId: 'docker-registry-creds', 
                                               usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                                    sh 'docker login ${DOCKER_REGISTRY} -u ${DOCKER_USER} -p ${DOCKER_PASS}'
                                    sh "docker push ${DOCKER_REGISTRY}/product-service:${IMAGE_TAG}"
                                }
                            }
                        }
                    }
                    post {
                        always {
                            publishTestResults testResultsPattern: 'product-service/target/surefire-reports/*.xml'
                            publishCoverageReport sourceFileResolver: sourceFiles('product-service/src/main/java')
                            archiveArtifacts artifacts: 'product-service/target/*.jar'
                        }
                    }
                }
                
                stage('Build Node.js Service') {
                    when {
                        anyOf {
                            expression { env.CHANGED_SERVICES.contains('node') }
                            expression { params.DEPLOY_SERVICES in ['all', 'node-only'] }
                        }
                    }
                    agent {
                        docker {
                            image 'node:18-alpine'
                            args '-v /var/run/docker.sock:/var/run/docker.sock'
                        }
                    }
                    environment {
                        REDIS_URL = credentials("${params.ENVIRONMENT}-redis-url")
                    }
                    steps {
                        unstash 'source-code'
                        dir('api-gateway') {
                            sh 'npm ci'
                            sh 'npm run build'
                            
                            script {
                                if (!params.SKIP_TESTS) {
                                    sh 'npm run lint'
                                    sh 'npm run test:unit'
                                    sh 'npm run test:integration'
                                    sh 'npm audit --audit-level moderate'
                                }
                                
                                sh "docker build -t ${DOCKER_REGISTRY}/api-gateway:${IMAGE_TAG} ."
                                
                                withCredentials([usernamePassword(credentialsId: 'docker-registry-creds', 
                                               usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                                    sh 'docker login ${DOCKER_REGISTRY} -u ${DOCKER_USER} -p ${DOCKER_PASS}'
                                    sh "docker push ${DOCKER_REGISTRY}/api-gateway:${IMAGE_TAG}"
                                }
                            }
                        }
                    }
                    post {
                        always {
                            publishTestResults testResultsPattern: 'api-gateway/test-results.xml'
                            publishCoverageReport sourceFileResolver: sourceFiles('api-gateway/src')
                            archiveArtifacts artifacts: 'api-gateway/dist/**'
                        }
                    }
                }
                
                stage('Build Python Service') {
                    when {
                        anyOf {
                            expression { env.CHANGED_SERVICES.contains('python') }
                            expression { params.DEPLOY_SERVICES in ['all', 'python-only'] }
                        }
                    }
                    agent {
                        docker {
                            image 'python:3.11-slim'
                            args '-v /var/run/docker.sock:/var/run/docker.sock'
                        }
                    }
                    environment {
                        MONGODB_URL = credentials("${params.ENVIRONMENT}-mongodb-url")
                        ML_MODEL_PATH = '/app/models'
                    }
                    steps {
                        unstash 'source-code'
                        dir('recommendation-service') {
                            sh '''
                                python -m pip install --upgrade pip
                                pip install -r requirements.txt
                                pip install -r requirements-dev.txt
                            '''
                            
                            script {
                                if (!params.SKIP_TESTS) {
                                    sh 'python -m flake8 src/'
                                    sh 'python -m black --check src/'
                                    sh 'python -m pytest tests/ --cov=src --cov-report=xml --junitxml=test-results.xml'
                                    sh 'python -m bandit -r src/ -f json -o security-report.json'
                                }
                                
                                sh '''
                                    # Download pre-trained ML model
                                    mkdir -p models
                                    wget -O models/recommendation_model.pkl "https://storage.example.com/models/latest.pkl"
                                '''
                                
                                sh "docker build -t ${DOCKER_REGISTRY}/recommendation-service:${IMAGE_TAG} ."
                                
                                withCredentials([usernamePassword(credentialsId: 'docker-registry-creds', 
                                               usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                                    sh 'docker login ${DOCKER_REGISTRY} -u ${DOCKER_USER} -p ${DOCKER_PASS}'
                                    sh "docker push ${DOCKER_REGISTRY}/recommendation-service:${IMAGE_TAG}"
                                }
                            }
                        }
                    }
                    post {
                        always {
                            publishTestResults testResultsPattern: 'recommendation-service/test-results.xml'
                            publishCoverageReport sourceFileResolver: sourceFiles('recommendation-service/src')
                            archiveArtifacts artifacts: 'recommendation-service/models/*'
                        }
                    }
                }
            }
        }
        
        stage('Integration Tests') {
            when {
                not { params.SKIP_TESTS }
            }
            agent any
            environment {
                COMPOSE_PROJECT_NAME = "test-${BUILD_NUMBER}"
            }
            steps {
                unstash 'source-code'
                script {
                    try {
                        sh '''
                            # Start all services with docker-compose
                            export IMAGE_TAG=${IMAGE_TAG}
                            docker-compose -f docker-compose.test.yml up -d
                            
                            # Wait for services to be healthy
                            sleep 30
                            
                            # Run health checks
                            curl -f http://localhost:8080/health || exit 1  # Java service
                            curl -f http://localhost:3000/health || exit 1  # Node.js service
                            curl -f http://localhost:8000/health || exit 1  # Python service
                        '''
                        
                        dir('integration-tests') {
                            sh '''
                                npm ci
                                npm run test:integration
                                npm run test:e2e
                                npm run test:performance
                            '''
                        }
                        
                        // Contract testing
                        sh '''
                            # Pact contract testing
                            docker run --rm --network="test-${BUILD_NUMBER}_default" \
                                -v $(pwd)/pacts:/app/pacts \
                                pactfoundation/pact-cli:latest \
                                pact-broker publish /app/pacts \
                                --consumer-app-version ${IMAGE_TAG} \
                                --broker-base-url https://pact-broker.example.com
                        '''
                        
                    } finally {
                        sh 'docker-compose -f docker-compose.test.yml down -v'
                    }
                }
            }
            post {
                always {
                    publishTestResults testResultsPattern: 'integration-tests/test-results.xml'
                    archiveArtifacts artifacts: 'integration-tests/reports/**'
                }
            }
        }
        
        stage('Security & Compliance') {
            parallel {
                stage('Container Security Scan') {
                    agent any
                    steps {
                        script {
                            def services = ['product-service', 'api-gateway', 'recommendation-service']
                            services.each { service ->
                                sh """
                                    # Trivy security scan
                                    docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
                                        aquasec/trivy image --format json --output ${service}-security.json \
                                        ${DOCKER_REGISTRY}/${service}:${IMAGE_TAG}
                                """
                            }
                        }
                    }
                    post {
                        always {
                            archiveArtifacts artifacts: '*-security.json'
                        }
                    }
                }
                
                stage('Dependency Check') {
                    agent any
                    steps {
                        unstash 'source-code'
                        sh '''
                            # OWASP Dependency Check for Java
                            if [[ "${CHANGED_SERVICES}" == *"java"* ]]; then
                                cd product-service
                                mvn org.owasp:dependency-check-maven:check
                            fi
                            
                            # NPM Audit for Node.js
                            if [[ "${CHANGED_SERVICES}" == *"node"* ]]; then
                                cd api-gateway
                                npm audit --json > npm-audit.json || true
                            fi
                            
                            # Safety check for Python
                            if [[ "${CHANGED_SERVICES}" == *"python"* ]]; then
                                cd recommendation-service
                                pip install safety
                                safety check --json > safety-report.json || true
                            fi
                        '''
                    }
                    post {
                        always {
                            archiveArtifacts artifacts: '**/dependency-check-report.html,**/npm-audit.json,**/safety-report.json', allowEmptyArchive: true
                        }
                    }
                }
            }
        }
        
        stage('Deploy to Environment') {
            agent any
            environment {
                KUBECONFIG = credentials("${params.ENVIRONMENT}-kubeconfig")
                HELM_CHART_VERSION = "${IMAGE_TAG}"
            }
            steps {
                unstash 'source-code'
                script {
                    if (params.ENVIRONMENT == 'production') {
                        def deployInput = input(
                            id: 'deployApproval',
                            message: "Deploy to Production?",
                            parameters: [
                                choice(choices: ['Deploy', 'Cancel'], name: 'action'),
                                string(defaultValue: 'Production deployment', name: 'deploymentNotes')
                            ]
                        )
                        
                        if (deployInput.action != 'Deploy') {
                            error("Deployment cancelled by user")
                        }
                        
                        echo "Deployment notes: ${deployInput.deploymentNotes}"
                    }
                }
                
                dir('helm-charts') {
                    sh '''
                        # Install/upgrade services based on what changed
                        if [[ "${DEPLOY_SERVICES}" == "all" ]] || [[ "${DEPLOY_SERVICES}" == "java-only" ]]; then
                            if [[ "${CHANGED_SERVICES}" == *"java"* ]] || [[ "${REBUILD_ALL}" == "true" ]]; then
                                helm upgrade --install product-service ./product-service \
                                    --namespace ${ENVIRONMENT} \
                                    --set image.tag=${IMAGE_TAG} \
                                    --set environment=${ENVIRONMENT} \
                                    --wait --timeout=5m
                            fi
                        fi
                        
                        if [[ "${DEPLOY_SERVICES}" == "all" ]] || [[ "${DEPLOY_SERVICES}" == "node-only" ]]; then
                            if [[ "${CHANGED_SERVICES}" == *"node"* ]] || [[ "${REBUILD_ALL}" == "true" ]]; then
                                helm upgrade --install api-gateway ./api-gateway \
                                    --namespace ${ENVIRONMENT} \
                                    --set image.tag=${IMAGE_TAG} \
                                    --set environment=${ENVIRONMENT} \
                                    --wait --timeout=5m
                            fi
                        fi
                        
                        if [[ "${DEPLOY_SERVICES}" == "all" ]] || [[ "${DEPLOY_SERVICES}" == "python-only" ]]; then
                            if [[ "${CHANGED_SERVICES}" == *"python"* ]] || [[ "${REBUILD_ALL}" == "true" ]]; then
                                helm upgrade --install recommendation-service ./recommendation-service \
                                    --namespace ${ENVIRONMENT} \
                                    --set image.tag=${IMAGE_TAG} \
                                    --set environment=${ENVIRONMENT} \
                                    --wait --timeout=5m
                            fi
                        fi
                    '''
                }
                
                // Post-deployment tests
                sh '''
                    sleep 60  # Wait for services to stabilize
                    
                    # Smoke tests
                    kubectl port-forward -n ${ENVIRONMENT} service/api-gateway 3000:3000 &
                    PID=$!
                    sleep 5
                    
                    curl -f http://localhost:3000/health || exit 1
                    curl -f http://localhost:3000/api/products || exit 1
                    curl -f http://localhost:3000/api/recommendations || exit 1
                    
                    kill $PID
                '''
            }
        }
        
        stage('Performance Testing') {
            when {
                allOf {
                    not { params.SKIP_TESTS }
                    anyOf {
                        expression { params.ENVIRONMENT == 'staging' }
                        expression { params.ENVIRONMENT == 'production' }
                    }
                }
            }
            agent any
            steps {
                unstash 'source-code'
                dir('performance-tests') {
                    script {
                        sh '''
                            # K6 performance tests
                            docker run --rm -v $(pwd):/app -w /app \
                                grafana/k6 run --out json=results.json api-tests.js
                                
                            # Artillery load tests
                            npm install -g artillery
                            artillery run load-test.yml --output load-test-results.json
                        '''
                        
                        // Parse results and set build status
                        def k6Results = readJSON file: 'results.json'
                        if (k6Results.metrics.http_req_failed.rate > 0.05) {
                            unstable("Performance test failure rate above 5%")
                        }
                    }
                }
            }
            post {
                always {
                    archiveArtifacts artifacts: 'performance-tests/*.json'
                }
            }
        }
    }
    
    post {
        always {
            node('master') {
                cleanWs()
            }
        }
        success {
            script {
                def message = """
✅ Microservices deployment completed successfully!
Environment: ${params.ENVIRONMENT}
Services: ${params.DEPLOY_SERVICES}
Image Tag: ${env.IMAGE_TAG}
Changed Services: ${env.CHANGED_SERVICES}
"""
                slackSend channel: '#deployments', color: 'good', message: message
                
                // Update deployment tracking
                sh """
                    curl -X POST https://api.example.com/deployments \
                        -H "Content-Type: application/json" \
                        -d '{
                            "environment": "${params.ENVIRONMENT}",
                            "version": "${env.IMAGE_TAG}",
                            "services": "${env.CHANGED_SERVICES}",
                            "status": "success"
                        }'
                """
            }
        }
        failure {
            slackSend channel: '#deployments', color: 'danger', 
                     message: "❌ Microservices deployment failed! Environment: ${params.ENVIRONMENT}"
        }
    }
}