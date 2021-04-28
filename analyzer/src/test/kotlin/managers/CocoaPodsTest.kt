/*
 * Copyright (C) 2021 Dr. Ing. h.c. F. Porsche AG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.analyzer.managers

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.shouldBe

import java.io.File

import org.ossreviewtoolkit.model.RemoteArtifact
import org.ossreviewtoolkit.model.VcsType

class CocoaPodsTest : StringSpec({
    "parse a regular Podfile" {
        val podfileLock = File("src/test/assets/cocoapods/Podfile-regular.lock")
        val result = PodfileLock.createFromYaml(podfileLock.readText())

        with(result.pods) {
            size shouldBe 10
            elementAt(0).apply {
                id.type shouldBe "Pod"
                id.namespace shouldBe ""
                id.name shouldBe "ISO8601DateFormatterValueTransformer"
                id.version shouldBe "0.6.1"
                dependencies.count() shouldBeExactly 1
            }

            elementAt(1).apply {
                id.type shouldBe "Pod"
                id.namespace shouldBe ""
                id.name shouldBe "RestKit"
                id.version shouldBe "0.27.3"
                dependencies.count() shouldBeExactly 1
            }

            elementAt(2).apply {
                id.type shouldBe "Pod"
                id.namespace shouldBe "RestKit"
                id.name shouldBe "Core"
                id.version shouldBe "0.27.3"
                dependencies.count() shouldBeExactly 3
            }

            elementAt(3).apply {
                id.type shouldBe "Pod"
                id.namespace shouldBe "RestKit"
                id.name shouldBe "CoreData"
                id.version shouldBe "0.27.3"
                dependencies.count() shouldBeExactly 1
            }

            elementAt(4).apply {
                id.type shouldBe "Pod"
                id.namespace shouldBe "RestKit"
                id.name shouldBe "Network"
                id.version shouldBe "0.27.3"
                dependencies.count() shouldBeExactly 3
            }

            elementAt(5).apply {
                id.type shouldBe "Pod"
                id.namespace shouldBe "RestKit"
                id.name shouldBe "ObjectMapping"
                id.version shouldBe "0.27.3"
                dependencies.count() shouldBeExactly 3
            }

            elementAt(6).apply {
                id.type shouldBe "Pod"
                id.namespace shouldBe "RestKit"
                id.name shouldBe "Support"
                id.version shouldBe "0.27.3"
                dependencies.count() shouldBeExactly 1
            }

            elementAt(7).apply {
                id.type shouldBe "Pod"
                id.namespace shouldBe ""
                id.name shouldBe "RKValueTransformers"
                id.version shouldBe "1.1.3"
                dependencies.count() shouldBeExactly 0
            }

            elementAt(8).apply {
                id.type shouldBe "Pod"
                id.namespace shouldBe ""
                id.name shouldBe "SOCKit"
                id.version shouldBe "1.1"
                dependencies.count() shouldBeExactly 0
            }

            elementAt(9).apply {
                id.type shouldBe "Pod"
                id.namespace shouldBe ""
                id.name shouldBe "TransitionKit"
                id.version shouldBe "2.2.1"
                dependencies.count() shouldBeExactly 0
            }
        }

        with(result.dependencies) {
            size shouldBe 1
            single().apply {
                id.type shouldBe "Pod"
                id.namespace shouldBe ""
                id.name shouldBe "RestKit"
                id.version shouldBe "0.27.3"
            }
        }
    }

    "parse a Podfile with a deep dependency tree" {
        val podfileLock = File("src/test/assets/cocoapods/Podfile-with-deep-tree.lock")
        val result = PodfileLock.createFromYaml(podfileLock.readText())

        with(result.dependencies) {
            size shouldBe 18
        }

        with(result.pods) {
            size shouldBe 41

            // Pod with name
            elementAt(13).apply {
                id.type shouldBe "Pod"
                id.namespace shouldBe "MaterialComponents"
                id.name shouldBe "private/Application"
                id.version shouldBe "113.1.0"
            }
        }
    }

    "parse a podspec without subspecs" {
        val podspec = File("src/test/assets/cocoapods/PodSpec-regular.json")
        val result = PodSpec.createFromJson(podspec.readText())

        with(result) {
            size shouldBe 1

            single().apply {
                identifier.type shouldBe "Pod"
                identifier.namespace shouldBe ""
                identifier.name shouldBe "Alamofire"
                identifier.version shouldBe "5.4.3"

                homepageUrl shouldBe "https://github.com/Alamofire/Alamofire"
                declaredLicense shouldBe "MIT"
                description shouldBe "Elegant HTTP Networking in Swift"

                vcs.type shouldBe VcsType.GIT
                vcs.url shouldBe "https://github.com/Alamofire/Alamofire.git"
                vcs.revision shouldBe "5.4.3"
                vcs.path shouldBe ""

                remoteArtifact shouldBe RemoteArtifact.EMPTY

                dependencies.count() shouldBeExactly 0
            }
        }
    }

    "parse a podspec with subspecs" {
        val podspec = File("src/test/assets/cocoapods/PodSpec-with-subspecs.json")
        val result = PodSpec.createFromJson(podspec.readText())

        with(result) {
            size shouldBe 9

            first().apply {
                identifier.type shouldBe "Pod"
                identifier.namespace shouldBe "RestKit"
                identifier.name shouldBe "Core"
                identifier.version shouldBe "0.27.3"

                homepageUrl shouldBe "https://github.com/RestKit/RestKit"
                declaredLicense shouldBe "Apache License, Version 2.0"
                description shouldBe
                        "RestKit is a framework for consuming and modeling RESTful web resources on iOS and OS X."

                vcs.type shouldBe VcsType.GIT
                vcs.url shouldBe "https://github.com/RestKit/RestKit.git"
                vcs.revision shouldBe "v0.27.3"
                vcs.path shouldBe ""

                remoteArtifact shouldBe RemoteArtifact.EMPTY

                dependencies.count() shouldBeExactly 0
            }
        }
    }
})
