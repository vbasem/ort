/*
 * Copyright (C) 2017-2020 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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

package org.ossreviewtoolkit.model.config

import com.fasterxml.jackson.annotation.JsonInclude

import org.ossreviewtoolkit.model.ArtifactProvenance
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Provenance
import org.ossreviewtoolkit.model.RepositoryProvenance
import org.ossreviewtoolkit.model.UnknownProvenance
import org.ossreviewtoolkit.model.VcsInfo
import org.ossreviewtoolkit.model.VcsType
import org.ossreviewtoolkit.utils.replaceCredentialsInUri

/**
 * A configuration for a specific package and provenance. It allows to setup [PathExclude]s and
 * [LicenseFindingCuration]s, similar to how it is done via the [RepositoryConfiguration] for projects.
 */
data class PackageConfiguration(
    /**
     * The identifier of the package this configuration applies to.
     */
    val id: Identifier,

    /**
     * The source artifact this configuration applies to.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val sourceArtifactUrl: String? = null,

    /**
     * The vcs and revision this configuration applies to.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val vcs: VcsMatcher? = null,

    /**
     * Path excludes.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    val pathExcludes: List<PathExclude> = emptyList(),

    /**
     * License finding curations.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    val licenseFindingCurations: List<LicenseFindingCuration> = emptyList()
) {
    init {
        require((sourceArtifactUrl == null) xor (vcs == null)) {
            "A package configuration can either apply to a source artifact or to a VCS, not to neither or both."
        }
    }

    fun matches(otherId: Identifier, provenance: Provenance): Boolean {
        if (id != otherId) return false

        return when (provenance) {
            is UnknownProvenance -> false
            is ArtifactProvenance -> sourceArtifactUrl != null && sourceArtifactUrl == provenance.sourceArtifact.url
            is RepositoryProvenance -> vcs != null && vcs.matches(provenance.vcsInfo)
        }
    }
}

/**
 * A matcher which matches its properties against [VcsInfo]s.
 */
data class VcsMatcher(
    /**
     * The [type] to match for equality against [VcsInfo.type].
     */
    val type: VcsType,

    /**
     * The [url] to match for equality against [VcsInfo.url].
     */
    val url: String,

    /**
     * The [url] to match for equality against [VcsInfo.resolvedRevision].
     */
    val revision: String,

    /**
     * The [path] to match for equality against [VcsInfo.path]. Must only be specified in case [type] equals
     * [VcsType.GIT_REPO].
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    val path: String? = null
) {
    init {
        require(url.isNotBlank() && revision.isNotBlank())

        if (type == VcsType.GIT_REPO) {
            require(!path.isNullOrBlank()) {
                "Matching against Git-Repo VCS info requires a non-blank path."
            }
        } else {
            require(path == null) {
                "A path must only be specified for matching Git-Repo VCS info."
            }
        }
    }

    fun matches(vcsInfo: VcsInfo): Boolean =
        type == vcsInfo.type && matchesWithoutCredentials(url, vcsInfo.url) && (path == null || path == vcsInfo.path) &&
                revision == vcsInfo.resolvedRevision
}

private fun matchesWithoutCredentials(lhs: String, rhs: String): Boolean =
    lhs.replaceCredentialsInUri() == rhs.replaceCredentialsInUri()
