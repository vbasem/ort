/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

import com.vdurmont.semver4j.Requirement

import java.io.File
import java.net.URI
import java.util.SortedSet

import org.ossreviewtoolkit.analyzer.AbstractPackageManagerFactory
import org.ossreviewtoolkit.analyzer.PackageManager
import org.ossreviewtoolkit.downloader.VcsHost
import org.ossreviewtoolkit.downloader.VersionControlSystem
import org.ossreviewtoolkit.model.Hash
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.OrtIssue
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.PackageReference
import org.ossreviewtoolkit.model.Project
import org.ossreviewtoolkit.model.ProjectAnalyzerResult
import org.ossreviewtoolkit.model.RemoteArtifact
import org.ossreviewtoolkit.model.Scope
import org.ossreviewtoolkit.model.Severity
import org.ossreviewtoolkit.model.VcsInfo
import org.ossreviewtoolkit.model.VcsType
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.RepositoryConfiguration
import org.ossreviewtoolkit.model.createAndLogIssue
import org.ossreviewtoolkit.model.jsonMapper
import org.ossreviewtoolkit.model.yamlMapper
import org.ossreviewtoolkit.utils.CommandLineTool
import org.ossreviewtoolkit.utils.ProcessCapture
import org.ossreviewtoolkit.utils.normalizeVcsUrl
import org.ossreviewtoolkit.utils.textValueOrEmpty

/**
 * The [CocoaPods](https://cocoapods.org/) package manager for Objective-C.
 *
 * Steps to parse project with Podfile.lock
 *
 * 1. Parse Podfile.lock to get
 *      - Dependencies – Direct dependencies of this project
 *      - Pods – List with all Pods and their version and their dependencies with max depth of 1
 * 2. Recursively associate all pod-ids with Version and dependencies starting with first level Dependencies on
 * 3. Save everything in Scope and Analyzer Result
 */
class CocoaPods(
    name: String,
    analysisRoot: File,
    analyzerConfig: AnalyzerConfiguration,
    repoConfig: RepositoryConfiguration
) : PackageManager(name, analysisRoot, analyzerConfig, repoConfig), CommandLineTool {
    private var podSpecCache: MutableMap<String, PodSpec> = mutableMapOf()
    private var issues: MutableList<OrtIssue> = mutableListOf()

    class Factory : AbstractPackageManagerFactory<CocoaPods>("CocoaPods") {
        override val globsForDefinitionFiles = listOf("Podfile.lock", "Podfile")

        override fun create(
            analysisRoot: File,
            analyzerConfig: AnalyzerConfiguration,
            repoConfig: RepositoryConfiguration
        ) = CocoaPods(managerName, analysisRoot, analyzerConfig, repoConfig)
    }

    override fun command(workingDir: File?) = "pod"

    override fun getVersionRequirement(): Requirement = Requirement.buildIvy("[1.10.1,)")

    override fun getVersionArguments(): String = "--version --allow-root"

    override fun beforeResolution(definitionFiles: List<File>) {
        // We need the version arguments which were included in https://github.com/CocoaPods/CocoaPods/pull/10609
        checkVersion(analyzerConfig.ignoreToolVersions)

        // Update the global specs repo so we can resolve new versions
        run("repo", "update", "--allow-root", workingDir = definitionFiles.first().parentFile)
    }

    override fun resolveDependencies(definitionFile: File): List<ProjectAnalyzerResult> {
        val workingDir = definitionFile.parentFile
        val projectInfo = getProjectInfoFromVcs(workingDir)
        val packages = parseCocoapodsDependencies(definitionFile)
        val scope = Scope("dependencies", dependencies = packages.second)

        return listOf(
            ProjectAnalyzerResult(
                packages = packages.first,
                project = Project(
                    id = Identifier(
                        type = managerName,
                        namespace = "",
                        name = packageName(projectInfo.namespace, projectInfo.projectName),
                        version = projectInfo.revision.orEmpty()
                    ),
                    definitionFilePath = VersionControlSystem.getPathInfo(definitionFile).path,
                    authors = sortedSetOf(),
                    declaredLicenses = sortedSetOf(),
                    vcs = VcsInfo.EMPTY,
                    vcsProcessed = processProjectVcs(workingDir, VcsInfo.EMPTY),
                    scopeDependencies = sortedSetOf(scope),
                    homepageUrl = ""
                ),
                issues = issues
            )
        )
    }

    private fun parseCocoapodsDependencies(definitionFile: File):
            Pair<SortedSet<Package>, SortedSet<PackageReference>> {
        val podfileLock = PodfileLock.createFromYaml(definitionFile.readText())
        podfileLock.dependencies = podfileLock.dependencies.map { it.lookupVersion(podfileLock.pods) }.toSet()

        val allPodSpecs = podfileLock.pods.map { reference ->
            lookupPodspec(reference.id, definitionFile.parentFile).let { podSpec ->
                Package(
                    id = reference.id,
                    authors = sortedSetOf(),
                    declaredLicenses = listOf(podSpec.declaredLicense).toSortedSet(),
                    description = podSpec.description,
                    homepageUrl = podSpec.homepageUrl,
                    binaryArtifact = podSpec.remoteArtifact,
                    sourceArtifact = RemoteArtifact.EMPTY,
                    vcs = podSpec.vcs,
                    vcsProcessed = processPackageVcs(podSpec.vcs, podSpec.homepageUrl)
                )
            }
        }.toSortedSet()

        return Pair(allPodSpecs, podfileLock.dependencies.toSortedSet())
    }

    private fun getProjectInfoFromVcs(workingDir: File): CocoapodsProjectInfo {
        val workingTree = VersionControlSystem.forDirectory(workingDir)
        val vcsInfo = workingTree?.getInfo() ?: VcsInfo.EMPTY
        val normalizedVcsUrl = normalizeVcsUrl(vcsInfo.url)
        val vcsHost = VcsHost.toVcsHost(URI(normalizedVcsUrl))

        return CocoapodsProjectInfo(
            namespace = vcsHost?.getUserOrOrganization(normalizedVcsUrl),
            projectName = vcsHost?.getProject(normalizedVcsUrl)
                ?: workingDir.relativeTo(analysisRoot).invariantSeparatorsPath,
            revision = vcsInfo.revision
        )
    }

    private fun lookupPodspec(id: Identifier, workingDir: File): PodSpec {
        val namespaceOrName = id.name.substringBefore("/")
        podSpecCache[namespaceOrName]?.let { return it }

        val pathResult = ProcessCapture(command(workingDir),
            "spec", "which", namespaceOrName, "--version=${id.version}", "--allow-root", "--regex",
            workingDir = workingDir
        )

        if (pathResult.isError) {
            val issue = createAndLogIssue(
                managerName,
                message = pathResult.stdout.trim(),
                severity = Severity.ERROR
            )
            issues.add(issue)

            return PodSpec(
                id.namespace,
                id.name,
                id.version,
                "",
                "",
                "",
                VcsInfo.EMPTY,
                RemoteArtifact.EMPTY,
                setOf()
            )
        }

        val spec = run(
            "ipc", "spec", pathResult.stdout.trim(), "--allow-root",
            workingDir = workingDir
        ).stdout

        var podSpecs = PodSpec.createFromJson(spec)
        if (id.namespace.isNotEmpty()) { // Filter the subSpecs to find the matching pair
            podSpecs = podSpecs.filter { it.identifier.name == id.name }
        }

        val updatedPodSpec = podSpecs.first()
        podSpecCache[namespaceOrName] = updatedPodSpec
        return updatedPodSpec
    }
}

private data class CocoapodsProjectInfo(val namespace: String?, val projectName: String?, val revision: String?)

data class PodSpec(
    val namespace: String?,
    val name: String,
    val version: String,
    val homepageUrl: String,
    val declaredLicense: String,
    val description: String,
    val vcs: VcsInfo,
    val remoteArtifact: RemoteArtifact,
    var dependencies: Set<PodSpec>
) {
    var identifier: Identifier = Identifier("Pod", "", packageName(namespace, name), version)

    companion object Factory {
        fun createFromJson(spec: String): List<PodSpec> {
            val json = jsonMapper.readTree(spec)

            val gitUrl = json["source"]?.get("git")
            val gitTag = json["source"]?.get("tag")
            var vcs = VcsInfo.EMPTY
            if (gitUrl != null) {
                vcs = VcsInfo(VcsType.GIT, url = gitUrl.textValue(), revision = gitTag.textValueOrEmpty())
            }

            val httpUrl = json["source"]?.get("http")?.textValue()
            var remoteArtifact = RemoteArtifact.EMPTY
            if (httpUrl != null) {
                remoteArtifact = RemoteArtifact(httpUrl, Hash.NONE)
            }

            val licenseNode = json["license"]
            val license = if (licenseNode is ObjectNode) {
                licenseNode["type"].textValue()
            } else {
                licenseNode.textValue()
            }

            val name = json["name"].textValue()
            val version = json["version"].textValue()
            val homepage = json["homepage"].textValue()
            val summary = json["summary"].textValue()
            val subSpecs = json["subspecs"]?.asIterable()?.map { PodSubSpec.createFromJson(it) }?.map { subSpec ->
                    PodSpec(
                        name,
                        subSpec.name,
                        version,
                        homepage,
                        license,
                        summary,
                        vcs,
                        remoteArtifact,
                        setOf()
                    )
                } ?: listOf()

            return subSpecs.plus(
                PodSpec(
                    null,
                    name,
                    version,
                    homepage,
                    license,
                    summary,
                    vcs,
                    remoteArtifact,
                    setOf()
                )
            )
        }
    }

    fun merge(other: PodSpec): PodSpec {
        require(name == other.name && namespace == other.namespace && version == other.version) {
            "Cannot merge specs for different pods."
        }

        return PodSpec(namespace,
            name,
            version,
            homepageUrl.takeUnless { it.isEmpty() } ?: other.homepageUrl,
            declaredLicense.takeUnless { it.isEmpty() } ?: other.declaredLicense,
            description.takeUnless { it.isEmpty() } ?: other.description,
            vcs.takeUnless { it == VcsInfo.EMPTY } ?: other.vcs,
            remoteArtifact.takeUnless { it == RemoteArtifact.EMPTY } ?: other.remoteArtifact,
            dependencies.takeUnless { it.isEmpty() } ?: other.dependencies,
        )
    }
}

data class PodSubSpec(
    val name: String,
    val dependencies: Set<String>?
) {
    companion object Factory {
        fun createFromJson(json: JsonNode): PodSubSpec {
            return PodSubSpec(
                json["name"].textValue(),
                json["dependencies"]?.asIterable()?.map { it.textValue() }?.toSet() ?: setOf()
            )
        }
    }
}

data class PodfileLock(
    var dependencies: Set<PackageReference>,
    val pods: List<PackageReference>
) {
    companion object Factory {
        fun createFromYaml(spec: String): PodfileLock {
            val yaml = yamlMapper.readTree(spec)
            val pods = yaml["PODS"]
                .asIterable()
                .toPackageReferences()
                .updateVersions()
            val dependencies = yaml["DEPENDENCIES"]
                .asIterable()
                .toPackageReferences()
                .updateVersions(pods)
                .mapNotNull { dependency ->
                    pods
                        .findLast { it.id.namespace == dependency.id.namespace && it.id.name == dependency.id.name }
                        ?.let { podWithVersion ->
                            dependency.merge(podWithVersion)
                        }
                }
                .toSet()

            return PodfileLock(dependencies, pods)
        }
    }
}

private fun PackageReference.lookupVersion(references: List<PackageReference>): PackageReference {
    val referencePackage = references.find { it.id.name == id.name && it.id.namespace == id.namespace }
    val mergedPackage = merge(referencePackage!!)
    return PackageReference(
        mergedPackage.id,
        dependencies = mergedPackage.dependencies.map { it.lookupVersion(references) }.toSortedSet()
    )
}

private fun PackageReference.merge(other: PackageReference): PackageReference {
    require(id.name == other.id.name && id.namespace == other.id.namespace) {
        "Cannot merge references for different packages."
    }

    return PackageReference(
        Identifier(
            id.type,
            id.namespace,
            id.name,
            id.version.takeUnless { it.isEmpty() } ?: other.id.version
        ),
        dependencies = dependencies.takeUnless { it.isEmpty() } ?: other.dependencies
    )
}

private fun List<PackageReference>.updateVersions(references: List<PackageReference>? = null): List<PackageReference> {
    return map { reference ->
        val updatedDependencies = reference.dependencies.toList().updateVersions(references ?: this).toSortedSet()
        return@map PackageReference(reference.id, dependencies = updatedDependencies)
    }
}

private fun Iterable<JsonNode>.toPackageReferences(): List<PackageReference> = map { it.toPackageReference() }

private fun JsonNode.toPackageReference(): PackageReference {
    return if (this is ObjectNode) {
        val fieldName = fieldNames().asSequence().first()
        val dependencies = this[fieldName].mapNotNull { it.toPackageReference() }.toSortedSet()
        fieldName.toPackageReference(dependencies)
    } else {
        textValue().toPackageReference()
    }
}

private fun String.toPackageReference(dependencies: SortedSet<PackageReference> = sortedSetOf()): PackageReference {
    // Transform: AppCenter/Distribute (= 3.1.1)
    // into: name=Distribute, namespace=AppCenter, version=(= 3.1.1)
    val nameAndVersion = split(" (")
    val names = nameAndVersion.first().split("/")
    val rawVersion = "(${nameAndVersion.last()}"
    var namespace: String? = null
    val versionRegex = "\\((\\S+)\\)".toRegex()
    val version = versionRegex.find(rawVersion)?.groups?.last()?.value

    var name = names.last()
    if (names.count() >= 2) {
        namespace = names.first()
        name = names.subList(1, names.size).joinToString("/")
    }

    val identifier = Identifier("Pod", "", packageName(namespace, name), version.orEmpty())

    return PackageReference(identifier, dependencies = dependencies)
}

private fun packageName(namespace: String?, name: String?): String =
    if (namespace.isNullOrBlank()) {
        name.orEmpty()
    } else {
        "${namespace.orEmpty()}/${name.orEmpty()}"
    }
