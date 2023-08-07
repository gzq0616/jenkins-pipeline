import jenkins.model.*

/**
 * 全局配置参数
 *
 * 项目配置参数
 * REPO =   // 仓库地址
 * BRANCH = release  // 构建分支
 * TARGET_NAME = job-executor-exec.jar
 * BUILD_PATH = job-executor-web
 * BUILD_SCRIPT = 构建脚本内容
 */

timestamps {
    currentBuild.result = "SUCCESS"
    currentBuild.description = "构建分支:${params.BRANCH}\n构建环境:${params.ENV}"

    try {
        echo "---------开始执行--------"

        node('build') {
            checkout scm

            stage('checkout') {
                git_checkout()
            }

            stage('build') {
                sh "${params.BUILD_SCRIPT}"
            }

            stage('upload') {
                sh "~/aws-bin/aws s3 cp ${params.BUILD_PATH}/target/${JOB_NAME}-exec.jar s3://hoorah-deploy/${params.ENV}/${JOB_NAME}/${BUILD_NUMBER}/"
                stash(name: 'app', includes: "${params.BUILD_PATH}/target/${JOB_NAME}-exec.jar")
            }
        }

        stage('deploy') {
            script {
                for (node in get_node("${JOB_NAME}-${params.ENV}")) {
                    node("${node}") {
                        unstash "app"

                        sh """
                        # 创建目录
                        mkdir -pv /data/www-data
                        mkdir -pv /data0/log-data/${JOB_NAME}

                        # 设置文件和目录的所有者为 www 用户
                        chown www.www -R /data0
                        chown www.www -R /data/www-data

                        # 复制文件并覆盖同名文件
                        cp -f ${params.BUILD_PATH}/target/${JOB_NAME}-exec.jar /data/www-data/${JOB_NAME}.jar

                        # 重新启动服务
                        sudo systemctl restart ${JOB_NAME}
                        """
                    }
                }
            }
        }
    } catch (err) {
        currentBuild.result = "FAILURE"
        echo "${err}"
    } finally {
        echo "---------结束执行--------"
    }
}

// 拉取代码
def git_checkout() {
    echo "checkout code from ${params.BRANCH}"
    def credentialsId = env.SCM_ID
    checkout([
            $class                           : 'GitSCM',
            branches                         : [[name: "*/${params.BRANCH}"]],
            doGenerateSubmoduleConfigurations: false,
            extensions                       : [[$class: 'CleanBeforeCheckout'], [$class: 'AuthorInChangelog'],
                                                [$class : 'CloneOption', depth: 1, noTags: false, reference: '',
                                                 shallow: false]],
            submoduleCfg                     : [],
            userRemoteConfigs                : [[credentialsId: credentialsId, url: params.REPO]]
    ])
    echo "checkout:PASS"
}

@NonCPS
def get_node(labelString) {
    Jenkins.instance.nodes.findAll { node ->
        node.labels.any { label -> label.name == labelString }
    }
}