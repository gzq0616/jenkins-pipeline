/**
 * 全局配置参数
 *
 * 项目配置参数
 * REPO =   // 仓库地址
 * BRANCH = release  // 构建分支
 * ENV = //环境
 * TARGET_NAME = job-executor-exec.jar
 * BUILD_PATH = job-executor-web
 * BUILD_SCRIPT = 构建脚本内容
 * APP_TYPE = java-msv、nodejs，static
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

            if (params.APP_TYPE == "static") {
                stage('upload') {
                    sh "~/aws-bin/aws s3 cp --recursive ${params.BUILD_PATH} s3://hoorah-deploy/${params.ENV}/${JOB_NAME}/${BUILD_NUMBER}/"
                    stash(name: 'app', includes: "${params.BUILD_PATH}/index.html")

                    sh "~/aws-bin/aws s3 cp --recursive ${params.BUILD_PATH} s3://hoorahgo-s1s4/${params.ENV}/${JOB_NAME}/"
                    stash(name: 'app', includes: "${params.BUILD_PATH}/index.html")
                }
            } else if (params.APP_TYPE == "nodejs") {
                stage('upload') {
                    sh "~/aws-bin/aws s3 cp ${params.BUILD_PATH}/${JOB_NAME}.tar.gz s3://hoorah-deploy/${params.ENV}/${JOB_NAME}/${BUILD_NUMBER}/"
                    stash(name: 'app', includes: "${params.BUILD_PATH}/${JOB_NAME}.tar.gz")
                }
            } else {
                stage('upload') {
                    sh "~/aws-bin/aws s3 cp ${params.BUILD_PATH}/target/${JOB_NAME}-exec.jar s3://hoorah-deploy/${params.ENV}/${JOB_NAME}/${BUILD_NUMBER}/"
                    stash(name: 'app', includes: "${params.BUILD_PATH}/target/${JOB_NAME}-exec.jar")
                }
            }
        }

        stage('deploy') {
            script {
                for (Node in nodeFilter("${JOB_NAME}-${params.ENV}")) {
                    node("${Node.trim()}") {
                        if (params.APP_TYPE == "nodejs") {
                            unstash "app"

                            sh """
                            # 创建目录
                            mkdir -pv /data/www-data
                            mkdir -pv /data0/log-data/${JOB_NAME}

                            # 设置文件和目录的所有者为 www 用户
                            chown www.www -R /data0
                            chown www.www -R /data/www-data

                            # 发布时先删除代码目录
                            if [ -d /data/www-data/${JOB_NAME} ]
                            then
                                rm -rf /data/www-data/${JOB_NAME}
                            fi
                            mkdir -p /data/www-data/${JOB_NAME}/
                            cp -f ${params.BUILD_PATH}/${JOB_NAME}.tar.gz /data/www-data/${JOB_NAME}/
                            tar -xf /data/www-data/${JOB_NAME}/${JOB_NAME}.tar.gz -C /data/www-data/${JOB_NAME}/

                            # 重新启动服务
                            sudo systemctl restart ${JOB_NAME}
                            """
                        } else {
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
                        /**
                        生产环境是多台机器，发布时如果太快会造成老机器尚未启动完成，新机器服务又进行重启，服务不可用。
                        单台机器发布后，sleep 120秒再对下一台机器操作
                        **/
                        if (params.ENV == "prod") {
                            unstash "app"

                            sh """
                            sleep 120
                            """
                        }
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

/**
 field hudson.model.Slave name
 method hudson.model.AbstractCIBase getNodes
 method hudson.model.Node getLabelString
 staticMethod jenkins.model.Jenkins getInstance
 * @param labelString
 * @return
 */
@NonCPS
def nodeFilter(labelString) {
    def nodes = []
    jenkins.model.Jenkins.instance.nodes.collect { node ->
        if (node.labelString.contains(labelString)) {
            nodes.add(node.name.toString())
        }
    }
    return nodes
}
