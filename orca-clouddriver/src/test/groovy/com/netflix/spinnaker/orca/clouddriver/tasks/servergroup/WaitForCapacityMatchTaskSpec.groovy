/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.clouddriver.tasks.servergroup

import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution
import com.netflix.spinnaker.orca.clouddriver.CloudDriverService
import com.netflix.spinnaker.orca.pipeline.model.PipelineExecutionImpl
import com.netflix.spinnaker.orca.pipeline.model.StageExecutionImpl
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

class WaitForCapacityMatchTaskSpec extends Specification {

  CloudDriverService cloudDriverService = Mock()
  @Subject WaitForCapacityMatchTask task = new WaitForCapacityMatchTask() {

    @Override
    void verifyServerGroupsExist(StageExecution stage) {
      // do nothing
    }
  }

  void "should properly wait for a scale up operation"() {
    setup:
      task.cloudDriverService = cloudDriverService
      cloudDriverService.getServerGroup("test", "us-east-1", "kato-main-v000") >> serverGroup
      def context = [account: "test", "deploy.server.groups": ["us-east-1": ["kato-main-v000"]]]
      def stage = new StageExecutionImpl(PipelineExecutionImpl.newOrchestration("orca"), "resizeServerGroup", context)

    when:
      def result = task.execute(stage)

    then:
      result.status == ExecutionStatus.RUNNING

    when:
      serverGroup.instances.addAll([makeInstance("i-5678"), makeInstance("i-0000")])

    and:
      result = task.execute(stage)

    then:
      result.status == ExecutionStatus.SUCCEEDED

    where:
      serverGroup = [
            name: "kato-main-v000",
            region: "us-east-1",
            instances: [
              makeInstance("i-1234")
            ],
            asg: [
              desiredCapacity: 3
            ],
            capacity: [
              desired: 3
            ]
          ]
  }

  @Unroll
  void "should return status #status for a scale up operation when server group is not disabled and instance health is #healthState"() {
    setup:
    def serverGroup = [
          name: "kato-main-v000",
          region: "us-east-1",
          instances: [
            makeInstance("i-1234")
          ],
          asg: [
            minSize: 3,
            desiredCapacity: 3
          ],
          capacity: [
            min: 3,
            desired: 3
          ]
        ]

    task.cloudDriverService = cloudDriverService
    cloudDriverService.getServerGroup("test", "us-east-1", "kato-main-v000") >> serverGroup
    def context = [account: "test", "deploy.server.groups": ["us-east-1": ["kato-main-v000"]]]
    def stage = new StageExecutionImpl(PipelineExecutionImpl.newOrchestration("orca"), "resizeServerGroup", context)

    when:
    def result = task.execute(stage)

    then:
    result.status == ExecutionStatus.RUNNING

    when:
    serverGroup.instances.addAll([
      makeInstance("i-5678", healthState),
      makeInstance("i-0000", healthState)
      ])

    and:
    result = task.execute(stage)

    then:
    result.status == status

    where:
    healthState | status
    'Down'      | ExecutionStatus.RUNNING
    'Starting'  | ExecutionStatus.RUNNING
    'Up'        | ExecutionStatus.SUCCEEDED
  }

  void "should properly wait for a scale down operation"() {
    setup:
    task.cloudDriverService = cloudDriverService
    cloudDriverService.getServerGroup("test", "us-east-1", "kato-main-v000") >> serverGroup
    def context = [account: "test", "deploy.server.groups": ["us-east-1": ["kato-main-v000"]]]
    def stage = new StageExecutionImpl(PipelineExecutionImpl.newOrchestration("orca"), "resizeServerGroup", context)

    when:
    def result = task.execute(stage)

    then:
    result.status == ExecutionStatus.RUNNING

    when:
    serverGroup.instances = [makeInstance("i-0000")]

    and:
    result = task.execute(stage)

    then:
    result.status == ExecutionStatus.SUCCEEDED

    where:
    serverGroup = [
          name: "kato-main-v000",
          region: "us-east-1",
          instances: [
            makeInstance("i-1234"),
            makeInstance("i-5678"),
            makeInstance("i-0000")
          ],
          asg: [
            desiredCapacity: 1
          ],
          capacity: [
            desired: 1
          ]
        ]
  }

  @Unroll
  void 'should use targetHealthyDeployPercentage (if available) when determining if scaling has succeeded'() {
    when:
    def context  = [
      capacity: [
        min: configured.min,
        max: configured.max,
        desired: configured.desired
      ],
      targetHealthyDeployPercentage: targetHealthyDeployPercentage,
      targetDesiredSize: targetHealthyDeployPercentage
        ? Math.round(targetHealthyDeployPercentage * configured.desired / 100) : null
    ]

    def serverGroup = [
      asg: [
        desiredCapacity: asg.desired
      ],
      capacity: [
        min: asg.min,
        max: asg.max,
        desired: asg.desired
      ]
    ]

    def instances = []
    (1..healthy).each {
      instances << [health: [[state: 'Up']]]
    }

    then:
    result == task.hasSucceeded(
      new StageExecutionImpl(PipelineExecutionImpl.newPipeline("orca"), "", "", context),
      serverGroup, instances, null
    )

    where:
    result || healthy | asg                             | configured                      | targetHealthyDeployPercentage
    false  || 5       | [min: 10, max: 15, desired: 15] | [min: 10, max: 15, desired: 15] | 85
    false  || 12      | [min: 10, max: 15, desired: 15] | [min: 10, max: 15, desired: 15] | 85
    true   || 13      | [min: 10, max: 15, desired: 15] | [min: 10, max: 15, desired: 15] | 85
    false  || 13      | [min: 10, max: 15, desired: 15] | [min: 10, max: 15, desired: 15] | null
  }

  @Unroll
  void 'should wait based on configured capacity when autoscaling is disabled'() {
    when:
    def serverGroup = [
      asg     : [
        desiredCapacity: asg.desired
      ],
      capacity: [
        min    : asg.min,
        max    : asg.max,
        desired: asg.desired
      ]
    ]

    def context = [
      source: [useSourceCapacity: false],
    ]

    if (configured) {
      context.capacity = [
        min    : configured.min,
        max    : configured.max,
        desired: configured.desired
      ]
    }

    def instances = []
    (1..healthy).each {
      instances << [health: [[state: 'Up']]]
    }

    then:
    result == task.hasSucceeded(
      new StageExecutionImpl(PipelineExecutionImpl.newPipeline("orca"), "", "", context),
      serverGroup, instances, null
    )

    where:
    result || healthy | asg                             | configured
    // scale down
    false  || 5       | [min: 3, max: 3, desired: 3]    | null
    true   || 3       | [min: 3, max: 3, desired: 3]    | null
    false  || 5       | [min: 10, max: 10, desired: 10] | [min: 3, max: 3, desired: 3]
    false  || 5       | [min: 10, max: 10, desired: 10] | [min: "3", max: "3", desired: "3"]
    true   || 5       | [min: 10, max: 10, desired: 10] | [min: 5, max: 5, desired: 5]
    true   || 5       | [min: 10, max: 10, desired: 10] | [min: "5", max: "5", desired: "5"]
    // scale up
    false  || 5       | [min: 5, max: 5, desired: 5]    | [min: 10, max: 10, desired: 10]
    false  || 5       | [min: 5, max: 5, desired: 5]    | [min: "10", max: "10", desired: "10"]
    true   || 3       | [min: 1, max: 1, desired: 1]    | [min: 3, max: 3, desired: 3]
    true   || 3       | [min: 1, max: 1, desired: 1]    | [min: "3", max: "3", desired: "3"]
    // asg value is used when autoscaling
    true   || 4       | [min: 3, max: 10, desired: 4]   | [min: 1, max: 50, desired: 5]
    true   || 4       | [min: 3, max: 10, desired: 4]   | [min: "1", max: "50", desired: "5"]
  }

  private static Map makeInstance(id, healthState = 'Up') {
    [instanceId: id, health: [ [ state: healthState ] ]]
  }
}
