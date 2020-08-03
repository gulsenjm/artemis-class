def k8slabel = "jenkins-pipeline-${UUID.randomUUID().toString()}"
def slavePodTemplate = """
      metadata:
        labels:
          k8s-label: ${k8slabel}
        annotations:
          jenkinsjoblabel: ${env.JOB_NAME}-${env.BUILD_NUMBER}
      spec:
        affinity:
          podAntiAffinity:
            requiredDuringSchedulingIgnoredDuringExecution:
            - labelSelector:
                matchExpressions:
                - key: component
                  operator: In
                  values:
                  - jenkins-jenkins-master
              topologyKey: "kubernetes.io/hostname"
        containers:
        - name: docker
          image: docker:latest
          imagePullPolicy: IfNotPresent
          command:
          - cat
          tty: true
          volumeMounts:
            - mountPath: /var/run/docker.sock
              name: docker-sock
        serviceAccountName: default
        securityContext:
          runAsUser: 0
          fsGroup: 0
        volumes:
          - name: docker-sock
            hostPath:
              path: /var/run/docker.sock
    """

    // def environment = ""
    // def docker_image = ""
    def branch = "${scm.branches[0].name}".replaceAll(/^\*\//, '').replace("/", "-").toLowerCase()
    
    //Scheduling the node to run the build
    podTemplate(name: k8slabel, label: k8slabel, yaml: slavePodTemplate, showRawYaml: false) {
      node(k8slabel) {
        
        stage('Pull SCM') {     //Responsible to pull the source from GitHub in this case. NOTE: before we pull the code we are using params.branch to get exactly the version to be pulled
            checkout scm
        }
        
        container("docker") {
            dir('deployments/docker') {
                stage("Docker Build") {
                        sh "docker build -t gulsenjm/artemis:${branch.replace('version/','v')} . "
                        // sh "docker build -t gulsenjm/artemis:${branch.replace('version/', 'v')}  ."
                }

                //We have created a credential call docker-hub-creds which is contains our username and passworrd so Jenkins can use that securely
                stage("Docker Login") {
                    withCredentials([usernamePassword(credentialsId: 'docker-hub-creds', passwordVariable: 'password', usernameVariable: 'username')]) {
                    sh "docker login --username ${username} --password ${password}"
                    }
                }
                //We have created a credential call docker-hub-creds which is contains our username and passworrd so Jenkins can use that securely
                stage("Docker Push") {
                    sh "docker push gulsenjm/artemis:${branch.replace('version/','v')}"
                }

                stage("TRigger Deploy") {
                  build 'artemis-deploy',
                  build job: 'artemis-deploy', 
                  parameters: [
                      [$class: 'BooleanParameterValue', name: 'terraformApply', value: true],
                      [$class: 'StringParameterValue',  name: 'environment', value: "${dev}"]
                      // [$class: 'StringParameterValue',  name: 'docker_image', value: "${docker_image}"]
                      ]
                }
            }
        }
        //pull the source code we will need to run the build
      }
    }
    
