/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
 * Copyright (C) 2021 Bosch.IO GmbH
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

package org.ossreviewtoolkit.analyzer.curation

import java.io.File

import org.ossreviewtoolkit.analyzer.PackageCurationProvider
import org.ossreviewtoolkit.model.FileFormat
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.PackageCuration
import org.ossreviewtoolkit.model.readValueOrDefault
import org.ossreviewtoolkit.utils.log

/**
 * A [PackageCurationProvider] that loads [PackageCuration]s from all [curationFiles]. Supports all file formats
 * specified in [FileFormat].
 */
class FilePackageCurationProvider(curationFiles: Collection<File>) : PackageCurationProvider {
    constructor(curationFile: File) : this(listOf(curationFile))

    internal val packageCurations by lazy {
        curationFiles.mapNotNull { curationFile ->
            runCatching {
                curationFile.readValueOrDefault(emptyList<PackageCuration>())
            }.onFailure {
                log.warn { "Failed parsing package curation from '${curationFile.absoluteFile}'." }
            }.getOrNull()
        }.flatten()
    }

    override fun getCurationsFor(pkgId: Identifier) = packageCurations.filter { it.isApplicable(pkgId) }
}
