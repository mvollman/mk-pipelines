/**
 *
 * Launch Instances running FIO to benchmark Ceph
 *
 * Expected parameters:
 *
 *   BENCH_SCENARIO              Benchmarking scenario to test
 *
 *   SALT_MASTER_URL             URL of Salt master
 *   SALT_MASTER_CREDENTIALS     Credentials for login to Salt API
 *
 *   INSTANCE_COUNT              Number of benchmark clients to deploy
 *   INSTANCE_NAME               Name of benchmark clients to deploy
 *   INSTANCE_IMAGE              Image to use to deploy benchmark clients
 *   INSTANCE_NETWORK            Network to attach to benchmark clients to
 *   INSTANCE_FLAVOR             Flavor to use to deploy benchmark clients
 *   INSTANCE_KEY_NAME           SSH key to include in deployed benchmark clients
 *
 *   DEBUG_MODE                  Do not delete instances after test
 *
 */

import groovy.json.JsonOutput

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
openstack = new com.mirantis.mk.Openstack()

def runtime
def saltMaster
def instanceCount = INSTANCE_COUNT as Integer
def openstackEnv = "venv"
def artifactsDir = '_artifacts/'
def benchResults = []

// Get Credentials from Pillar and create Openstack RC File
def writeOpenStackCredentialsFile(config, file) {

    rc = """
set +x
export OS_USERNAME=${config.auth.username}
export OS_PASSWORD=${config.auth.password}
export OS_TENANT_NAME=${config.auth.project_name}
export OS_PROJECT_NAME=${config.auth.project_name}
export OS_AUTH_URL=${config.auth.auth_url}/v3
export OS_REGION_NAME=${config.region_name}
export OS_IDENTITY_API_VERSION=${config.identity_api_version}
export OS_ENDPOINT_TYPE=admin
export OS_PROJECT_DOMAIN_NAME=${config.auth.project_domain_name}
export OS_USER_DOMAIN_NAME=${config.auth.user_domain_name}
export OS_CACERT=/etc/ssl/certs/ca-certificates.crt
set -x
"""
   writeFile file: file, text: rc

}


// Get FIO config and write userdata file
def writeUserDataFile(config, file) {

    _cloud_init = """#!/bin/bash

cat <<_EOF_ > /etc/resolvconf/resolv.conf.d/head
nameserver 8.8.8.8
nameserver 8.8.4.4
_EOF_
systemctl restart resolvconf
apt-get update
export DEBIAN_FRONTEND=noninteractive
apt-get -y install fio apache2
mkdir /var/fio_workspace
cat <<_EOF_ > /var/fio_workspace/fio.cfg
[global]
randrepeat=0
runtime=${config.runtime}

[randwrite]
filename=/dev/vdc
direct=${config.direct}
rw=${config.rw}
ioengine=${config.ioengine}
bs=${config.bs}
iodepth=${config.iodepth}
numjobs=${config.numjobs}
_EOF_

cd /var/fio_workspace
fio --output-format=json fio.cfg | tee /var/www/html/fio.json
endtag=`tail -n1 /var/www/html/fio.json`
while [ "\$endtag" != '}' ] ; do
  sleep 5
  endtag=`tail -n1 /var/www/html/fio.json`
done
echo "BENCH_COMPLETE" > /var/www/html/bench_complete
"""

    writeFile file: file, text: _cloud_init

}


node() {
    try{
        stage('Initialize OpenStack Connection') {
            sh "mkdir -p ${artifactsDir}"
            saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            pillar_name = 'keystone:client:os_client_config:cfgs:root:content:clouds:admin_identity'
            pillar = salt.getPillar(saltMaster, 'I@keystone:client', pillar_name)['return'][0].values()[0]

            openstack.setupOpenstackVirtualenv(openstackEnv)
            rcFile = "${openstackEnv}/keystonerc"
            writeOpenStackCredentialsFile(pillar, rcFile)

        }

        stage('Create Volumes') {
            for (int x=0; x<instanceCount; x++){
                cmd = "openstack --insecure volume create --size 1 ${INSTANCE_NAME}-${env.BUILD_ID}-${x}"
                openstack.runOpenstackCommand(cmd, rcFile, openstackEnv)
            }
        }

        stage('Launch instances') {
            
            pillar_name = "ceph:common:benchmarking:scenarios:${BENCH_SCENARIO}"
            fio_cfg = salt.getPillar(saltMaster, 'I@ceph:mon', pillar_name)['return'][0].values()[0]
            writeUserDataFile(fio_cfg, "user-data.txt")

            cmd = "openstack --insecure security group create ${INSTANCE_NAME}-${env.BUILD_ID}"
            openstack.runOpenstackCommand(cmd, rcFile, openstackEnv)
            cmd = "openstack --insecure security group rule create --dst-port 80 ${INSTANCE_NAME}-${env.BUILD_ID}"
            openstack.runOpenstackCommand(cmd, rcFile, openstackEnv)
            if (DEBUG_MODE == 'true') {
                cmd = "openstack --insecure security group rule create --dst-port 22 ${INSTANCE_NAME}-${env.BUILD_ID}"
                openstack.runOpenstackCommand(cmd, rcFile, openstackEnv)
            }

            cmd = "openstack --insecure server group create --policy anti-affinity ${INSTANCE_NAME}-${env.BUILD_ID}"
            openstack.runOpenstackCommand(cmd, rcFile, openstackEnv)

            cmd = "openstack --insecure server group show -f value -c id ${INSTANCE_NAME}-${env.BUILD_ID}"
            group_id = openstack.runOpenstackCommand(cmd, rcFile, openstackEnv)

            for (int x=0; x<instanceCount; x++){
                cmd = "openstack --insecure volume show -f value -c id ${INSTANCE_NAME}-${env.BUILD_ID}-${x}"
                volume_id = openstack.runOpenstackCommand(cmd, rcFile, openstackEnv)

                cmd = "openstack --insecure server create \
                                 --image ${INSTANCE_IMAGE} \
                                 --network ${INSTANCE_NETWORK} \
                                 --key-name ${INSTANCE_KEY_NAME} \
                                 --security-group ${INSTANCE_NAME}-${env.BUILD_ID} \
                                 --flavor ${INSTANCE_FLAVOR} \
                                 --user-data user-data.txt \
                                 --block-device-mapping vdc=${volume_id} \
                                 --hint group=${group_id} \
                                 ${INSTANCE_NAME}-${env.BUILD_ID}-${x}"
                openstack.runOpenstackCommand(cmd, rcFile, openstackEnv)
            }
        }

        stage('Wait for instances') {
            for (int y=0; y<instanceCount; y++){
                cmd = "openstack --insecure server show -f value -c OS-EXT-STS:vm_state ${INSTANCE_NAME}-${env.BUILD_ID}-${y}"
                def statusCheckerCount = 1
                while (statusCheckerCount <= 250) {
                    status = openstack.runOpenstackCommand(cmd, rcFile, openstackEnv)
                    echo("[Instance] Status: ${status}, Check: ${statusCheckerCount}")

                    if (status.contains('error')) {
                        throw new Exception(status)

                    } else if (status.contains('active')) {
                        echo(status)
                        break
                    }

                    sleep(30)
                    statusCheckerCount++
                }
            }
            
        }

        stage('Wait for FIO') {
            // Wait for fio jobs to finish 
            // Curl results
            // Sleep for fio runtime before checking for completion
            sleep(salt.getPillar(saltMaster, 'I@ceph:mon', "ceph:common:benchmarking:scenarios:${BENCH_SCENARIO}")['return'][0].values()[0].runtime)

            for (int x=0; x<instanceCount; x++){
                cmd = "openstack --insecure server show -f value -c addresses ${INSTANCE_NAME}-${env.BUILD_ID}-${x}"
                instance_ip = openstack.runOpenstackCommand(cmd, rcFile, openstackEnv).split('=')[1]
                curlCmd = "curl http://${instance_ip}/bench_complete"
                def statusCheckerCount = 1
                while (statusCheckerCount <= 1200) {
                    rc = sh returnStatus: true, script: "${curlCmd}"
                    if (rc == 0)
                    {
                      break
                    }
                    sleep(30)
                    statusCheckerCount++
                }

                if (rc != 0)
                {
                  error("Check for FIO finished timed out")
                }
            }
        }

        stage('Gather Results') {
            for (int x=0; x<instanceCount; x++){
                def finshedChecker = 1
                while (finshedChecker <= 1200) {
                    bench_complete = sh returnStdout: true, script: "${curlCmd}"
                    if (bench_complete.contains("BENCH_COMPLETE"))
                    {
                        json = sh(returnStdout: true, script: "curl http://${instance_ip}/fio.json").trim()
                        echo(json)
                        jsono = new groovy.json.JsonSlurperClassic().parseText(json)
                        benchResults.putAt(x, jsono)
                        outFile = "${artifactsDir}${instance_ip}.json"
                        writeFile file: outFile, text: json
                        break
                    }
                    sleep(30)
                    finshedChecker++
                }
            }
        }

        stage('Cleanup instances') {
            // Delete instances
            if (DEBUG_MODE == 'false') {
                for (int x=0; x<instanceCount; x++){
                    cmd = "openstack --insecure server remove volume ${INSTANCE_NAME}-${env.BUILD_ID}-${x} ${INSTANCE_NAME}-${env.BUILD_ID}-${x}"
                    openstack.runOpenstackCommand(cmd, rcFile, openstackEnv)
                    cmd = "openstack --insecure server delete ${INSTANCE_NAME}-${env.BUILD_ID}-${x}"
                    openstack.runOpenstackCommand(cmd, rcFile, openstackEnv)
                    cmd = "openstack --insecure volume delete ${INSTANCE_NAME}-${env.BUILD_ID}-${x}"
                    openstack.runOpenstackCommand(cmd, rcFile, openstackEnv)
                }
                cmd = "openstack --insecure server group delete ${INSTANCE_NAME}-${env.BUILD_ID}"
                openstack.runOpenstackCommand(cmd, rcFile, openstackEnv)
                // Wait for server delete before deleting security group
                sleep(30)
                cmd = "openstack --insecure security group delete ${INSTANCE_NAME}-${env.BUILD_ID}"
                openstack.runOpenstackCommand(cmd, rcFile, openstackEnv)
            }
        }

        stage('Print Summary Results') {
            // Parse results
            // Calculate summary results
            // Write summary to stdout and file in workspace
            summary = ""
            for (t in [ 'read', 'write' ]) {
                _total_iops = 0
                _total_bw = 0
                _total_clat_mean = 0
                _total_clat_ninefive = 0
                _client_iops = []
                _client_bw = []
                _client_clat_mean = []
                _client_clat_ninefive = []

                for (int x=0; x<instanceCount; x++){
                    res = benchResults.getAt(x)

                    _total_iops += res.jobs[0]."${t}".iops
                    _total_bw += res.jobs[0]."${t}".bw_mean
                    _total_clat_mean += res.jobs[0]."${t}".clat.mean
                    _total_clat_ninefive += res.jobs[0]."${t}".clat.percentile.'95.000000'

                    _client_iops.putAt(x, res.jobs[0]."${t}".iops)
                    _client_bw.putAt(x, res.jobs[0]."${t}".bw_mean)
                    _client_clat_mean.putAt(x, res.jobs[0]."${t}".clat.mean)
                    _client_clat_ninefive.putAt(x, res.jobs[0]."${t}".clat.percentile.'95.000000')
                }
                _avg_iops = _total_iops / instanceCount
                _avg_bw = _total_bw / instanceCount
                _avg_clat_mean = _total_clat_mean / instanceCount
                _avg_clat_ninefive = _total_clat_ninefive / instanceCount

                summary_json = [
                  "${t}": [
                    client: [
                      iops: "${_client_iops.toString()}",
                      bw: "${_client_bw.toString()}",
                      clat_mean: "${_client_clat_mean.toString()}",
                      clat_ninefive: "${_client_clat_ninefive.toString()}"
                    ],
                    totals: [
                      iops: [
                        avg: "${_avg_iops}",
                        total: "${_total_iops}"
                      ],
                      bw: [
                        avg: "${_avg_bw}",
                        total: "${_total_bw}"
                      ],
                      clat_mean: [
                        avg: "${_avg_clat_mean}",
                        total: "${_total_clat_mean}"
                      ],
                      clat_ninefive: [
                        avg: "${_avg_clat_ninefive}",
                        total: "${_total_clat_ninefive}"
                      ]
                    ]
                  ]
                ]
                j = JsonOutput.toJson(summary_json)
                j = JsonOutput.prettyPrint(j)

                echo(j)
            }
        }

    } catch (Throwable e) {
        // If there was an error or exception thrown, the build failed
        currentBuild.result = "FAILURE"
        throw e
    }
}
