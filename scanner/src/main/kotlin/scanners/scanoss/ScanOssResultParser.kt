/*
 * Copyright (C) 2020-2021 SCANOSS TECNOLOGIAS SL
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

package org.ossreviewtoolkit.scanner.scanners.scanoss

import com.fasterxml.jackson.databind.JsonNode

import java.io.File
import java.time.Instant

import org.ossreviewtoolkit.model.CopyrightFinding
import org.ossreviewtoolkit.model.LicenseFinding
import org.ossreviewtoolkit.model.ScanSummary
import org.ossreviewtoolkit.model.TextLocation
import org.ossreviewtoolkit.spdx.SpdxConstants
import org.ossreviewtoolkit.spdx.SpdxExpression
import org.ossreviewtoolkit.spdx.calculatePackageVerificationCode

/**
 * Generate a summary from the given raw SCANOSS [result], using [startTime] and [endTime] metadata. From the [scanPath]
 * the package verification code is generated.
 */
internal fun generateSummary(startTime: Instant, endTime: Instant, scanPath: File, result: JsonNode) =
    generateSummary(
        startTime,
        endTime,
        calculatePackageVerificationCode(scanPath),
        result
    )

/**
 * Generate a summary from the given raw SCANOSS [result], using [startTime], [endTime], and [verificationCode]
 * metadata. This variant can be used if the result is not read from a local file.
 */
internal fun generateSummary(startTime: Instant, endTime: Instant, verificationCode: String, result: JsonNode) =
    ScanSummary(
        startTime = startTime,
        endTime = endTime,
        fileCount = result.size(),
        packageVerificationCode = verificationCode,
        licenseFindings = getLicenseFindings(result).toSortedSet(),
        copyrightFindings = getCopyrightFindings(result).toSortedSet(),
        issues = emptyList()
    )

/**
 * Get the license findings from the given [result].
 */
private fun getLicenseFindings(result: JsonNode): List<LicenseFinding> {
    val licenseFindings = mutableListOf<LicenseFinding>()

    result.fields().asSequence().forEach { (filename, matches) ->
        matches.asSequence().forEach { match ->
            val licenses = match["licenses"]?.asSequence().orEmpty()
            licenses.mapTo(licenseFindings) {
                val licenseName = it["name"].asText()
                val licenseExpression = runCatching { SpdxExpression.parse(licenseName) }.getOrNull()

                val license = when {
                    licenseExpression == null -> SpdxConstants.NOASSERTION
                    licenseExpression.isValid() -> licenseName
                    else -> "${SpdxConstants.LICENSE_REF_PREFIX}scanoss-$licenseName"
                }

                LicenseFinding(
                    license = license,
                    location = TextLocation(
                        path = match["file"].textValue() ?: filename,
                        startLine = TextLocation.UNKNOWN_LINE,
                        endLine = TextLocation.UNKNOWN_LINE
                    )
                )
            }
        }
    }

    return licenseFindings
}

/**
 * Get the copyright findings from the given [result].
 */
private fun getCopyrightFindings(result: JsonNode): List<CopyrightFinding> {
    val copyrightFindings = mutableListOf<CopyrightFinding>()

    result.fields().asSequence().forEach { (filename, matches) ->
        matches.asSequence().forEach { match ->
            val copyrights = match["copyrights"]?.asSequence().orEmpty()
            copyrights.mapTo(copyrightFindings) {
                val copyrightName = it["name"].asText()

                CopyrightFinding(
                    statement = copyrightName,
                    location = TextLocation(
                        path = match["file"].textValue() ?: filename,
                        startLine = TextLocation.UNKNOWN_LINE,
                        endLine = TextLocation.UNKNOWN_LINE
                    )
                )
            }
        }
    }

    return copyrightFindings
}
